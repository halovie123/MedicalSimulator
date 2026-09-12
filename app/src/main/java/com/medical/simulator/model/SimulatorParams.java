package com.medical.simulator.model;

/**
 * Holds all configurable parameters for the ESP32 PPG medical simulator.
 * Provides JSON serialisation for BLE command packets.
 *
 * BLE JSON format (app → ESP32):
 *   {"cmd":"setParams","hr":75,"spo2":98,"rr":16,"pi":2.50,"noise":0.10,"mode":"Normal"}
 */
public class SimulatorParams {

    // ─── Condition Mode Constants ──────────────────────────────────────────────
    public static final String MODE_NORMAL           = "Normal";
    public static final String MODE_ARRHYTHMIA       = "Arrhythmia";
    public static final String MODE_WEAK_PERFUSION   = "WeakPerfusion";
    public static final String MODE_VASOCONSTRICTION = "Vasoconstriction";
    public static final String MODE_STRONG_PERFUSION = "StrongPerfusion";
    public static final String MODE_VASODILATION     = "Vasodilation";

    public static final String[] ALL_MODES = {
        MODE_NORMAL, MODE_ARRHYTHMIA, MODE_WEAK_PERFUSION,
        MODE_VASOCONSTRICTION, MODE_STRONG_PERFUSION, MODE_VASODILATION
    };

    public static final String[] MODE_LABELS = {
        "Normal", "Arrhythmia", "Weak Perf.", "Vasoconstric.", "Strong Perf.", "Vasodilation"
    };

    // ─── Parameter Bounds ──────────────────────────────────────────────────────
    public static final int HR_MIN    = 20,   HR_MAX    = 300;
    public static final int SPO2_MIN  = 70,   SPO2_MAX  = 100;
    public static final int RR_MIN    = 4,    RR_MAX    = 60;
    public static final float PI_MIN   = 0.02f, PI_MAX  = 20.0f;
    public static final float NOISE_MIN = 0.0f, NOISE_MAX = 1.0f;

    // ─── AC/DC Amplitude Bounds (mV) ───────────────────────────────────────────
    public static final float AC_MV_MIN = 0f,    AC_MV_MAX = 1500f;
    public static final float DC_MV_MIN = 0f,    DC_MV_MAX = 1500f;

    // ─── Fields ───────────────────────────────────────────────────────────────
    private int   heartRate       = 75;
    private int   spo2            = 98;
    private int   respiratoryRate = 16;
    private float perfusionIndex  = 2.5f;
    private float noiseLevel      = 0.10f;
    private int condition = 0;
    private float acIrMv  = 45.0f;
    private float acRedMv = 45.0f;
    private float dcIrMv  = 1500.0f;
    private float dcRedMv = 1500.0f;
    public SimulatorParams() { /* defaults set above */ }

    public static final int CONDITION_NORMAL = 0;
    public static final int CONDITION_ARRHYTHMIA = 1;
    public static final int CONDITION_WEAK_PERF = 2;
    public static final int CONDITION_VASOCONSTR = 3;
    public static final int CONDITION_STRONG_PERF = 4;
    public static final int CONDITION_VASODILAT = 5;

    // ─── Heart Rate ───────────────────────────────────────────────────────────
    public int getHeartRate()         { return heartRate; }
    public void setHeartRate(int hr)  { heartRate = clamp(hr, HR_MIN, HR_MAX); }

    // ─── SpO2 ─────────────────────────────────────────────────────────────────
    public int getSpo2()              { return spo2; }
    public void setSpo2(int s)        { spo2 = clamp(s, SPO2_MIN, SPO2_MAX); }

    // ─── Respiratory Rate ─────────────────────────────────────────────────────
    public int getRespiratoryRate()       { return respiratoryRate; }
    public void setRespiratoryRate(int rr){ respiratoryRate = clamp(rr, RR_MIN, RR_MAX); }

    // ─── Perfusion Index ──────────────────────────────────────────────────────
    public float getPerfusionIndex()          { return perfusionIndex; }
    public void setPerfusionIndex(float pi)   { perfusionIndex = clampF(pi, PI_MIN, PI_MAX); }

    // ─── Noise Level ──────────────────────────────────────────────────────────
    public float getNoiseLevel()              { return noiseLevel; }
    public void setNoiseLevel(float noise)    { noiseLevel = clampF(noise, NOISE_MIN, NOISE_MAX); }

    // ─── Condition Mode ───────────────────────────────────────────────────────
    public int getCondition() {
        return condition;
    }

    public void setCondition(int cond) {
        condition = Math.max(0, Math.min(cond, 5));
    }

    // ─── AC/DC Amplitude (mV) ─────────────────────────────────────────────────
    public float getAcIrMv()                  { return acIrMv; }
    public void setAcIrMv(float v)            { acIrMv = clampF(v, AC_MV_MIN, AC_MV_MAX); }

    public float getAcRedMv()                 { return acRedMv; }
    public void setAcRedMv(float v)           { acRedMv = clampF(v, AC_MV_MIN, AC_MV_MAX); }

    public float getDcIrMv()                  { return dcIrMv; }
    public void setDcIrMv(float v)            { dcIrMv = clampF(v, DC_MV_MIN, DC_MV_MAX); }

    public float getDcRedMv()                 { return dcRedMv; }
    public void setDcRedMv(float v)           { dcRedMv = clampF(v, DC_MV_MIN, DC_MV_MAX); }

    // ─── JSON Serialisation ───────────────────────────────────────────────────

    /** Serialise to JSON command packet for the device (delta merge, all keys optional). */
    public String toCommandJson() {
        return "{"
                + "\"hr\":" + heartRate
                + ",\"spo2\":" + spo2
                + ",\"rr\":" + respiratoryRate
                + ",\"pi\":" + String.format(java.util.Locale.US, "%.2f", perfusionIndex)
                + ",\"noise\":" + String.format(java.util.Locale.US, "%.2f", noiseLevel)
                + ",\"condition\":" + condition
                + ",\"ac_ir_mv\":" + String.format(java.util.Locale.US, "%.2f", acIrMv)
                + ",\"ac_red_mv\":" + String.format(java.util.Locale.US, "%.2f", acRedMv)
                + ",\"dc_ir_mv\":" + String.format(java.util.Locale.US, "%.2f", dcIrMv)
                + ",\"dc_red_mv\":" + String.format(java.util.Locale.US, "%.2f", dcRedMv)
                + ",\"origin\":\"android\""
                + "}";
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private static int   clamp(int v, int min, int max)       { return Math.max(min, Math.min(max, v)); }
    private static float clampF(float v, float min, float max){ return Math.max(min, Math.min(max, v)); }

    @Override
    public String toString() { return toCommandJson(); }
}
