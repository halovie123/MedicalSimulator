package com.medical.simulator.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Realtime PPG Waveform Renderer — SurfaceView với render thread riêng.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  THIẾT KẾ                                                       │
 * │  • Nền đen tuyệt đối (hospital monitor style)                  │
 * │  • Sóng neon cyan #55E8D2                                       │
 * │  • Cuộn phải → trái mượt mà ở 60fps                            │
 * │  • Buffer 150 điểm = 3 giây (ở 50Hz)                           │
 * │  • Trục Y TỰ ĐỘNG CO GIÃN (autoscale): dữ liệu Pi gửi theo mV   │
 * │    (0–3000 mV) nên trục Y theo dõi min/max của cửa sổ hiển thị, │
 * │    làm mượt bằng lerp để không nhảy loạn.                       │
 * │  • Zero allocation trong hot path — không rác GC               │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Cách dùng trong MainActivity:
 *   bleManager.getPpgSample().observe(this, value -> {
 *       if (value != null) waveformView.addSample(value);
 *   });
 */
public class WaveformSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    // ─── Hằng số cấu hình ────────────────────────────────────────────────────

    /**
     * Số điểm lưu trong buffer.
     * 150 điểm / 50Hz = 3 giây hiển thị trên màn hình.
     * Tăng lên 200 nếu muốn 4 giây (cần nhiều CPU hơn chút).
     */
    private static final int BUFFER_SIZE = 150;

    /**
     * Trục Y TỰ ĐỘNG CO GIÃN (millivolt scale).
     *
     * Thiết bị (Raspberry Pi / ESP32) gửi biên độ hiển thị theo mV —
     * có thể là 0–300 mV hoặc lên tới ~3000 mV tuỳ thiết lập DC+AC.
     * Renderer theo dõi min/max của cửa sổ 150 điểm rồi map vào canvas,
     * với headroom 12% hai đầu và làm mượt (lerp) để trục không giật.
     */
    private static final float SCALE_MIN_SPAN    = 50f;   // span tối thiểu (mV) — đường phẳng không bị phóng quá mức
    private static final float SCALE_HEADROOM    = 0.12f; // 12% headroom trên/dưới
    private static final float SCALE_SMOOTHING   = 0.15f; // hệ số lerp mỗi frame (0..1)
    private static final float SCALE_DEFAULT_MIN = 0f;
    private static final float SCALE_DEFAULT_MAX = 100f;
    private static final int SCALE_LABEL_UPDATE_FRAMES = 6;

    /** Biên độ scale hiện tại (đơn vị mV) — cập nhật mượt mỗi frame */
    private float scaleMin = SCALE_DEFAULT_MIN;
    private float scaleMax = SCALE_DEFAULT_MAX;
    private String scaleLabel = "0–100 mV";
    private int scaleLabelFrame;

    /** FPS mục tiêu render thread */
    private static final int  TARGET_FPS      = 60;
    private static final long FRAME_PERIOD_NS = 1_000_000_000L / TARGET_FPS;

    /** Grid: số cột và hàng */
    private static final int GRID_COLS = 6;
    private static final int GRID_ROWS = 4;

    // ─── Sliding Window Buffer (FIFO circular) ────────────────────────────────
    //
    // BƯỚC 3 theo mô tả của bạn: mảng đệm tịnh tiến
    // Dùng circular buffer thay vì Array.copy() để không tốn CPU ở 50Hz.

    /** Mảng đệm tròn chứa biên độ hiển thị (mV) từ thiết bị */
    private final float[] ring = new float[BUFFER_SIZE];

    /** Chỉ số ghi tiếp theo (wraps around) */
    private int  writeIdx  = 0;

    /** Tổng số điểm đã được thêm vào (unbounded, dùng để tính count) */
    private int  totalAdded = 0;

    /** Lock cho thread-safety giữa BLE thread và render thread */
    private final Object ringLock = new Object();

    // ─── Render thread ────────────────────────────────────────────────────────
    private RenderThread      renderThread;
    private final AtomicBoolean active = new AtomicBoolean(false);

    // ─── Pre-allocated drawing objects — KHÔNG new() trong render loop ────────
    private final Paint bgPaint         = new Paint();
    private final Paint gridMinorPaint  = new Paint();
    private final Paint gridMajorPaint  = new Paint();
    private final Paint waveformPaint   = new Paint();
    private final Paint scanLinePaint   = new Paint();
    private final Paint labelPaint      = new Paint();

    /**
     * Pre-allocated line buffer: mỗi segment cần (x0,y0,x1,y1) = 4 floats.
     * (BUFFER_SIZE - 1) segments × 4 = tối đa 596 floats.
     */
    private final float[] lineBuffer = new float[(BUFFER_SIZE - 1) * 4];

    /** Snapshot buffer — copy ring buffer vào đây mỗi frame để tránh lock lâu */
    private final float[] snapshot = new float[BUFFER_SIZE];

    // ─── Constructor ──────────────────────────────────────────────────────────

    public WaveformSurfaceView(Context context) {
        this(context, null);
    }

    public WaveformSurfaceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WaveformSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        getHolder().addCallback(this);
        initPaints();
    }

    // ─── Init paints (gọi 1 lần duy nhất) ───────────────────────────────────

    private void initPaints() {
        bgPaint.setColor(Color.rgb(13, 23, 30));
        bgPaint.setStyle(Paint.Style.FILL);

        gridMinorPaint.setColor(0x243D6870);
        gridMinorPaint.setStyle(Paint.Style.STROKE);
        gridMinorPaint.setStrokeWidth(0.5f);
        gridMinorPaint.setAntiAlias(false);

        gridMajorPaint.setColor(0x40537D83);
        gridMajorPaint.setStyle(Paint.Style.STROKE);
        gridMajorPaint.setStrokeWidth(1.0f);
        gridMajorPaint.setAntiAlias(false);

        // Sóng neon xanh — đây là màu quan trọng nhất
        waveformPaint.setColor(0xFF55E8D2);
        waveformPaint.setStyle(Paint.Style.STROKE);
        waveformPaint.setStrokeWidth(2.5f);
        waveformPaint.setAntiAlias(true);
        waveformPaint.setStrokeCap(Paint.Cap.ROUND);
        waveformPaint.setStrokeJoin(Paint.Join.ROUND);

        scanLinePaint.setColor(0xCC9AF59F);
        scanLinePaint.setStyle(Paint.Style.STROKE);
        scanLinePaint.setStrokeWidth(1.5f);

        labelPaint.setColor(0xFF72BDB8);
        labelPaint.setTextSize(18f);
        labelPaint.setAntiAlias(true);
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * BƯỚC 3 (theo mô tả của bạn): Thêm điểm mới vào cuối buffer FIFO.
     *
     * Gọi từ BLE callback thread, thread-safe.
     * @param value Biên độ hiển thị (mV) từ thiết bị, vd 45.20 hoặc 1520.00
     */
    public void addSample(float value) {
        if (!Float.isFinite(value)) return;
        synchronized (ringLock) {
            ring[writeIdx % BUFFER_SIZE] = value;
            writeIdx++;
            if (totalAdded < BUFFER_SIZE) totalAdded++;
        }
    }

    /** Reset sạch buffer khi disconnect */
    public void reset() {
        synchronized (ringLock) {
            writeIdx   = 0;
            totalAdded = 0;
            // Không cần xoá mảng — totalAdded = 0 là đủ
        }
        scaleMin = SCALE_DEFAULT_MIN;
        scaleMax = SCALE_DEFAULT_MAX;
        scaleLabel = "0–100 mV";
        scaleLabelFrame = 0;
    }

    // ─── SurfaceHolder.Callback ───────────────────────────────────────────────

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        active.set(true);
        renderThread = new RenderThread(holder);
        renderThread.start();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int fmt, int w, int h) {
        // Không cần làm gì — render thread đọc getWidth()/getHeight() mỗi frame
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        active.set(false);
        if (renderThread != null) {
            renderThread.interrupt();
            try { renderThread.join(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ─── Render Thread ────────────────────────────────────────────────────────

    private final class RenderThread extends Thread {

        private final SurfaceHolder holder;

        RenderThread(SurfaceHolder holder) {
            super("PPG-RenderThread");
            this.holder = holder;
            setDaemon(true);
            // Do not starve the Android main thread. The previous MAX-1
            // priority made slider touch events and BLE status delivery hitch
            // on older phones while the canvas was rendering.
            setPriority(Thread.NORM_PRIORITY);
        }

        @Override
        public void run() {
            long nextFrameNs = System.nanoTime();

            while (active.get() && !isInterrupted()) {
                Canvas canvas = null;
                try {
                    canvas = holder.lockCanvas();
                    if (canvas != null) {
                        renderFrame(canvas, getWidth(), getHeight());
                    }
                } finally {
                    if (canvas != null) {
                        try { holder.unlockCanvasAndPost(canvas); }
                        catch (Exception ignored) {}
                    }
                }

                // Giới hạn FPS — tránh ngốn CPU
                nextFrameNs += FRAME_PERIOD_NS;
                long sleepNs = nextFrameNs - System.nanoTime();
                if (sleepNs > 0) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000L, (int)(sleepNs % 1_000_000L));
                    } catch (InterruptedException e) {
                        break;
                    }
                } else {
                    // Deadline trễ — reset để không tích lũy debt
                    nextFrameNs = System.nanoTime();
                }
            }
        }
    }

    // ─── Frame rendering ──────────────────────────────────────────────────────

    private void renderFrame(Canvas canvas, int W, int H) {
        if (W <= 0 || H <= 0) return;

        // ── Nền đen ──
        canvas.drawRect(0, 0, W, H, bgPaint);

        // ── Grid ──
        drawGrid(canvas, W, H);

        // ── Snapshot buffer (lock ngắn nhất có thể) ──
        int count;
        int head;
        synchronized (ringLock) {
            count = totalAdded;
            head  = writeIdx;
            if (count == 0) return;
            // Copy BUFFER_SIZE điểm gần nhất từ ring vào snapshot
            for (int i = 0; i < BUFFER_SIZE; i++) {
                int srcIdx = (head - BUFFER_SIZE + i + BUFFER_SIZE * 4) % BUFFER_SIZE;
                snapshot[i] = ring[srcIdx];
            }
        }

        // Số điểm thực sự có để vẽ (lúc đầu chưa đủ 150)
        final int drawCount = Math.min(count, BUFFER_SIZE);
        if (drawCount < 2) return;

        // Tính offset: chỉ vẽ drawCount điểm gần nhất (right-aligned)
        final int startIdx = BUFFER_SIZE - drawCount;

        // ── BƯỚC 4: Vẽ sóng với trục Y tự động co giãn (mV) ──
        //
        // 1) Tính min/max dữ liệu của cửa sổ hiển thị
        // 2) Thêm headroom 12% hai đầu, span tối thiểu 50 mV
        // 3) Lerp về min/max hiện tại (làm mượt, không nhảy)
        // 4) Map value → pixel:
        //      yPixel = vPad + drawH * (1 - (value - scaleMin) / scaleSpan)

        float dataMin = Float.MAX_VALUE;
        float dataMax = -Float.MAX_VALUE;
        for (int i = startIdx; i < BUFFER_SIZE; i++) {
            final float v = snapshot[i];
            if (v < dataMin) dataMin = v;
            if (v > dataMax) dataMax = v;
        }

        float span = dataMax - dataMin;
        if (span < SCALE_MIN_SPAN) {
            final float mid = (dataMax + dataMin) * 0.5f;
            dataMin = mid - SCALE_MIN_SPAN * 0.5f;
            dataMax = mid + SCALE_MIN_SPAN * 0.5f;
            span = SCALE_MIN_SPAN;
        }
        dataMin -= span * SCALE_HEADROOM;
        dataMax += span * SCALE_HEADROOM;

        scaleMin += (dataMin - scaleMin) * SCALE_SMOOTHING;
        scaleMax += (dataMax - scaleMax) * SCALE_SMOOTHING;
        final float scaleSpan = Math.max(SCALE_MIN_SPAN, scaleMax - scaleMin);

        final float vPad  = H * 0.06f;        // padding trên/dưới 6% chiều cao
        final float drawH = H - 2.0f * vPad;  // chiều cao vùng sóng

        final float xStep = (float) W / (BUFFER_SIZE - 1);

        // Build mảng line segments (x0,y0,x1,y1) — không alloc gì mới
        int lineCount = 0;
        for (int i = startIdx; i < BUFFER_SIZE - 1; i++) {
            final float x0 = i * xStep;
            final float x1 = (i + 1) * xStep;

            // Clamp về vùng scale hiện tại trước khi map
            final float v0 = Math.max(scaleMin, Math.min(scaleMax, snapshot[i]));
            final float v1 = Math.max(scaleMin, Math.min(scaleMax, snapshot[i + 1]));

            final float y0 = vPad + drawH * (1.0f - (v0 - scaleMin) / scaleSpan);
            final float y1 = vPad + drawH * (1.0f - (v1 - scaleMin) / scaleSpan);

            final int base = lineCount * 4;
            lineBuffer[base]     = x0;
            lineBuffer[base + 1] = y0;
            lineBuffer[base + 2] = x1;
            lineBuffer[base + 3] = y1;
            lineCount++;
        }

        // Vẽ tất cả segments một lần — hiệu quả nhất
        if (lineCount > 0) {
            canvas.drawLines(lineBuffer, 0, lineCount * 4, waveformPaint);
        }

        // ── Scan cursor (đường dọc tại vị trí đang viết) ──
        final float cursorX = (float)(startIdx + drawCount - 1) * xStep;
        canvas.drawLine(cursorX, vPad, cursorX, H - vPad, scanLinePaint);

        // ── Label thông tin (trục Y hiện tại theo mV) ──
        // Formatting every 60 Hz frame allocates temporary strings and adds
        // avoidable GC pressure during slider gestures.
        if (++scaleLabelFrame >= SCALE_LABEL_UPDATE_FRAMES) {
            scaleLabelFrame = 0;
            scaleLabel = String.format(Locale.US, "%.0f–%.0f mV", scaleMin, scaleMax);
        }
        canvas.drawText(scaleLabel, 6, H - 8, labelPaint);
        canvas.drawText("3s", W - 28, H - 8, labelPaint);
    }

    // ─── Grid ─────────────────────────────────────────────────────────────────

    private void drawGrid(Canvas canvas, int W, int H) {
        // Đường dọc (cột)
        for (int c = 0; c <= GRID_COLS; c++) {
            final float x = (float) c * W / GRID_COLS;
            canvas.drawLine(x, 0, x, H, c == 0 || c == GRID_COLS || c == GRID_COLS / 2
                    ? gridMajorPaint : gridMinorPaint);
        }
        // Đường ngang (hàng) — chia đều vùng vẽ (trục Y autoscale theo mV)
        for (int r = 0; r <= GRID_ROWS; r++) {
            final float y = (float) r * H / GRID_ROWS;
            canvas.drawLine(0, y, W, y, r == 0 || r == GRID_ROWS || r == GRID_ROWS / 2
                    ? gridMajorPaint : gridMinorPaint);
        }
    }
}
