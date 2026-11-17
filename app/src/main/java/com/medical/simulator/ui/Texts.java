package com.medical.simulator.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * The fixed labels of the Pi pages in the Pi's two languages (PPG_simulator_raspi ui/i18n.py and the COPY
 * tables of ui/frames/*). The phone follows the language the Pi reports, so both screens read the same; texts
 * that change at run time (status lines, received-signal badges) come from the Pi itself.
 */
public final class Texts {
    private Texts() {}

    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> VI = new HashMap<>();
    private static String language = "en";

    public static final String[] CONDITIONS_EN = {"Normal", "Arrhythmia", "Weak perfusion", "Vasoconstriction",
            "Strong perfusion", "Vasodilation"};
    public static final String[] CONDITIONS_VI = {"Bình thường", "Loạn nhịp", "Tưới máu yếu", "Co mạch",
            "Tưới máu mạnh", "Giãn mạch"};

    private static void put(String key, String en, String vi) {
        EN.put(key, en);
        VI.put(key, vi);
    }

    static {
        // rail and footer
        put("monitor", "Classic / Monitor", "Cổ điển / Theo dõi");
        put("calibration", "Calibration / RX", "Hiệu chuẩn / RX");
        put("recordings", "Recordings", "Bản ghi");
        put("neural", "PPG morphology", "Hình thái PPG");
        put("settings", "Signal setup", "Thiết lập tín hiệu");
        put("mode_dry_run", "DRY RUN", "MÔ PHỎNG");
        put("mode_hardware", "HARDWARE", "PHẦN CỨNG");
        put("mode_no_dac", "NO DAC", "KHÔNG CÓ DAC");
        put("tx_model", "TX model", "Mô hình TX");
        put("dac_target", "DAC target", "DAC đích");
        put("buffer", "Buffer", "Bộ đệm");
        put("lost", "Lost", "Mất mẫu");
        put("clipped", "Clipped", "Cắt đỉnh");
        put("research", "Research simulator", "Trình mô phỏng nghiên cứu");
        put("connected", "Connected", "Đã kết nối");
        put("connecting", "Connecting…", "Đang kết nối…");
        put("disconnected", "Not connected", "Chưa kết nối");
        put("scan_hint", "Tap the Bluetooth icon to connect to the Pi (Bluetooth or internet)",
                "Chạm biểu tượng Bluetooth để kết nối với Pi (Bluetooth hoặc Internet)");
        put("old_pi", "This Pi build only accepts Monitor setpoints (no BLE protocol v3)",
                "Bản app trên Pi chỉ nhận thông số Monitor (chưa có giao thức BLE v3)");
        put("waiting_pi", "Waiting for the Pi…", "Đang chờ Pi…");
        put("disconnect_q", "Disconnect from the Pi?", "Ngắt kết nối với Pi?");
        put("disconnect", "Disconnect", "Ngắt kết nối");
        put("cancel", "Cancel", "Huỷ");
        // connection: Bluetooth or the internet (MQTT broker)
        put("link_choose", "Connect to the Pi", "Kết nối với Pi");
        put("link_ble", "Bluetooth (nearby)", "Bluetooth (ở gần)");
        put("link_mqtt", "Internet (MQTT broker)", "Internet (máy chủ MQTT)");
        put("via_ble", "Bluetooth", "Bluetooth");
        put("via_mqtt", "Internet", "Internet");
        put("broker_host", "Broker host or IP", "Máy chủ broker (tên hoặc IP)");
        put("port", "Port", "Cổng");
        put("tls", "TLS encryption (required over the internet)", "Mã hoá TLS (bắt buộc khi qua Internet)");
        put("device_id", "Pi device id", "Mã thiết bị Pi");
        put("username", "User name", "Tên đăng nhập");
        put("password", "Password", "Mật khẩu");
        put("ca_import", "Import CA…", "Nhập CA…");
        put("ca_none", "Trust: system CAs", "Tin cậy: CA của hệ thống");
        put("ca_loaded", "Trust: private CA only", "Tin cậy: chỉ CA riêng");
        put("connect", "Connect", "Kết nối");
        put("pi_offline", "broker reached, Pi offline", "đã tới broker, Pi chưa kết nối");
        put("tls_off", "no TLS", "không TLS");
        put("pair_connect", "Connect to %s", "Kết nối với %s");
        put("pair_scan", "Scan the Pi's QR code", "Quét mã QR của Pi");
        put("link_mqtt_manual", "Internet (manual settings)", "Internet (nhập tay)");
        put("qr_prompt", "Point the camera at the QR code on the Pi (Phone button on its menu)",
                "Hướng camera vào mã QR trên Pi (nút Điện thoại trên menu của Pi)");
        put("qr_bad", "Not a simulator code: %s", "Không phải mã của trình mô phỏng: %s");
        put("pair_saved", "Paired with %s", "Đã ghép đôi với %s");
        put("ca_paired", "Trust: CA of the paired Pi", "Tin cậy: CA của Pi đã ghép đôi");
        // monitor
        put("start", "Start output", "Phát");
        put("stop", "Stop output", "Dừng phát");
        put("record", "Record CSV", "Ghi CSV");
        put("save_record", "Save CSV", "Lưu CSV");
        put("tx_card", "TX output signal", "Tín hiệu phát TX");
        put("rx_card", "RX received signal", "Tín hiệu thu RX");
        put("beep_on", "Beep: on", "Tiếng bíp: bật");
        put("beep_off", "Beep: off", "Tiếng bíp: tắt");
        put("hr", "Heart rate", "Nhịp tim");
        put("spo2", "SpO₂ target", "SpO₂ mục tiêu");
        put("rr", "Respiration", "Nhịp thở");
        put("condition", "Condition", "Tình trạng");
        put("badge_red_manual", "RED AC manual", "Tách AC RED");
        put("gauss", "3-Gaussian", "3-Gaussian");
        // calibration
        put("cal_title", "Calibration & acquisition", "Hiệu chuẩn & thu tín hiệu");
        put("cal_subtitle", "Set IR/RED levels and Start; the dot blinks green while received = sent ± tolerance. Leaving stops it.",
                "Đặt mức IR/RED rồi Bắt đầu; chấm xanh nhấp nháy khi mức thu = mức phát ± dung sai. Rời trang sẽ dừng.");
        put("cal_controls", "Test signal (sent mV)", "Tín hiệu thử (mV phát)");
        put("frequency", "Frequency Hz", "Tần số Hz");
        put("ir_level", "IR level mV", "IR mức mV");
        put("red_level", "RED level mV", "RED mức mV");
        put("swing", "Sine amplitude ± mV", "Biên độ sine ± mV");
        put("tolerance", "Tolerance ± mV", "Dung sai ± mV");
        put("cal_start", "Start", "Bắt đầu");
        put("cal_stop", "Stop", "Dừng");
        put("cal_tx", "TX output (DAC mV)", "Tín hiệu phát TX (mV DAC)");
        put("cal_rx", "RX received (OPT101 mV)", "Tín hiệu thu RX (mV OPT101)");
        put("delta", "Δ = received − sent (mV)", "Δ = mức thu − mức phát (mV)");
        put("spo2_cal", "SpO₂ calibration", "Hiệu chuẩn SpO₂");
        put("cal_idle", "Output stopped", "Chưa phát");
        // recordings
        put("rec_title", "Recordings", "Bản ghi");
        put("rec_subtitle", "TX model commands recorded at 100 Hz. Playback is on screen only; it does not drive the LEDs.",
                "Lệnh mô hình TX đã ghi ở 100 Hz. Phát lại chỉ trên màn hình, không điều khiển LED.");
        put("sessions", "Recorded sessions", "Phiên đã ghi");
        put("select", "Select a recording", "Chọn một bản ghi");
        put("empty", "No saved recordings. Start output, then Record CSV.", "Chưa có bản ghi. Hãy Phát rồi bấm Ghi CSV.");
        put("play", "Play", "Phát lại");
        put("pause", "Pause", "Tạm dừng");
        put("replay", "Replay", "Phát lại từ đầu");
        put("none", "No recording selected", "Chưa chọn bản ghi");
        put("scope", "Recordings hold TX model commands only. RX on the right is the live ADC, not part of the recording.",
                "Bản ghi chỉ chứa lệnh mô hình TX. RX bên phải là ADC đọc lúc này, không thuộc bản ghi.");
        put("rec_rx", "Live RX · OPT101", "RX trực tiếp · OPT101");
        // neural
        put("nn_title", "PPG · Gaussian and GAN sequences", "PPG · Gaussian và chuỗi GAN");
        put("generate", "New sequence", "Sinh đoạn mới");
        put("nn_hr", "Requested heart rate / bpm", "Nhịp tim yêu cầu / bpm");
        put("hr_min", "HR min / bpm", "HR tối thiểu / bpm");
        put("hr_max", "HR max / bpm", "HR tối đa / bpm");
        put("dc", "DC / mV", "DC / mV");
        put("ac", "IR AC / mV", "AC IR / mV");
        put("fitted", "Fitted Gaussian", "Gaussian đã fit");
        put("lsm", "LSM-GAN · exploratory", "LSM-GAN · thăm dò");
        put("psf", "PSF-GAN · experimental", "PSF-GAN · thử nghiệm");
        put("apft", "APFT-GAN · ~1 min", "APFT-GAN · ~1 phút");
        put("with_notch", "Visible notch", "Có notch rõ");
        put("no_notch", "No visible notch", "Không có notch rõ");
        put("play_led", "Play LED · 30 s", "Phát LED · 30 s");
        put("stop_output", "Stop output", "Dừng đầu ra");
        put("source", "Waveform source", "Nguồn dạng sóng");
        put("seek", "Position", "Vị trí");
        put("nn_tx", "TX output signal", "Tín hiệu phát TX");
        put("nn_rx", "RX received signal · OPT101", "Tín hiệu thu RX · OPT101");
        put("model_on_pi", "Models run on the Pi; the phone only controls them.",
                "Mô hình chạy trên Pi; điện thoại chỉ điều khiển.");
        // signal setup
        put("ac_ir", "AC IR mV", "AC IR mV");
        put("ac_red", "AC RED mV", "AC RED mV");
        put("dc_ir", "DC IR mV", "DC IR mV");
        put("dc_red", "DC RED mV", "DC RED mV");
        put("noise", "Noise level", "Mức nhiễu");
        put("setup_note", "Changes apply at once. RED AC set here no longer follows the SpO₂ target.",
                "Thay đổi áp dụng ngay. Đặt AC RED ở đây sẽ tách khỏi SpO₂ mục tiêu.");
        put("close", "Close", "Đóng");
    }

    /** Follow the Pi's language ("en" or "vi"). Returns true when it changed. */
    public static boolean setLanguage(String lang) {
        String next = "vi".equals(lang) ? "vi" : "en";
        boolean changed = !next.equals(language);
        language = next;
        return changed;
    }

    public static String language() { return language; }

    public static String t(String key) {
        Map<String, String> map = "vi".equals(language) ? VI : EN;
        String s = map.get(key);
        return s != null ? s : (EN.containsKey(key) ? EN.get(key) : key);
    }

    public static String[] conditions() {
        return "vi".equals(language) ? CONDITIONS_VI : CONDITIONS_EN;
    }
}
