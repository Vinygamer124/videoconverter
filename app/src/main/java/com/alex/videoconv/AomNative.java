package com.alex.videoconv;

// Cầu nối JNI tới libaom (xem src/main/cpp/aomjni.c)
public final class AomNative {
    private static boolean loaded;
    static {
        try { System.loadLibrary("aomjni"); loaded = true; } catch (Throwable t) { loaded = false; }
    }
    private AomNative() {}
    public static boolean available() { return loaded; }

    static native long nCreate(int w, int h, int fpsNum, int fpsDen, int bitrateKbps, int cpuUsed, int threads, boolean fast);
    static native byte[] nAv1C(long h);
    static native int nEncode(long h, java.nio.ByteBuffer rgba, int rowStride, long ptsUs, boolean key);
    static native int nFlush(long h);
    static native byte[] nNext(long h);
    static native String nError(long h);
    static native void nDestroy(long h);
}
