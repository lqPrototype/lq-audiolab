package com.aozora.audio;

/** Stable JNI boundary for the bundled WebRTC Audio Processing Module. */
public final class AozoraWebRtcApm {
    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("aozora_apm");
            loaded = true;
        } catch (Throwable error) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private AozoraWebRtcApm() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static native long nativeCreate(
        int sampleRate,
        int channels,
        boolean echoCancellation,
        boolean noiseSuppression,
        boolean gainControl
    );
    public static native int nativeProcessReverse(long handle, short[] samples);
    public static native int nativeProcessCapture(long handle, short[] samples);
    public static native void nativeSetStreamDelay(long handle, int delayMs);
    public static native int nativeGetVoiceDetected(long handle);
    public static native void nativeRelease(long handle);
}
