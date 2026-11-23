package com.alex.videoconv;

import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.ExportException;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collections;

/**
 * Encoder AV1 phần mềm dùng libaom, cắm vào Transformer qua Codec.EncoderFactory.
 * Transformer vẽ khung hình lên Surface của ImageReader (RGBA) -> đổi sang I420 -> libaom -> gói AV1 -> muxer.
 * Âm thanh vẫn dùng encoder mặc định (audioFactory).
 */
@UnstableApi
public final class AomEncoderFactory implements Codec.EncoderFactory {
    private final Codec.EncoderFactory audioFactory;
    private final int bitrate;
    private final double fps;
    private final boolean fast;

    public AomEncoderFactory(Codec.EncoderFactory audioFactory, int bitrate, double fps, boolean fast) {
        this.audioFactory = audioFactory; this.bitrate = bitrate; this.fps = fps > 0 ? fps : 30.0; this.fast = fast;
    }

    @Override
    public Codec createForAudioEncoding(Format format) throws ExportException {
        return audioFactory.createForAudioEncoding(format);
    }

    @Override
    public Codec createForVideoEncoding(Format format) throws ExportException {
        if (!AomNative.available())
            throw ExportException.createForUnexpected(new IllegalStateException("Không nạp được libaom (aomjni)"));
        int w = Math.max(2, format.width) & ~1, h = Math.max(2, format.height) & ~1;   // kích thước chẵn
        return new AomCodec(w, h, fps, bitrate, fast);
    }

    @Override public boolean audioNeedsEncoding() { return audioFactory.audioNeedsEncoding(); }
    @Override public boolean videoNeedsEncoding() { return false; }

    // ---------------------------------------------------------------------------------------------
    private static final class Packet {
        final ByteBuffer data; final long ptsUs; final boolean key;
        Packet(ByteBuffer d, long p, boolean k) { data = d; ptsUs = p; key = k; }
    }

    private static final class AomCodec implements Codec {
        private final int w, h; private final double fps; private final int bitrate;
        private final long handle;
        private final ImageReader reader;
        private final HandlerThread thread;
        private final Handler handler;
        private final ArrayDeque<Packet> queue = new ArrayDeque<>();
        private final Format outFormat;
        private final Format cfgFormat;
        private volatile String error;
        private volatile boolean eosSignalled, flushed, released;

        AomCodec(int w, int h, double fps, int bitrate, boolean fast) throws ExportException {
            this.w = w; this.h = h; this.fps = fps; this.bitrate = bitrate;
            int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
            int num = (int) Math.round(fps * 1000), den = 1000;
            handle = AomNative.nCreate(w, h, num, den, Math.max(50, bitrate / 1000), fast ? 9 : 6, threads, fast);
            if (handle == 0)
                throw ExportException.createForUnexpected(new IllegalStateException("libaom không khởi tạo được (" + w + "×" + h + ")"));
            byte[] av1c = AomNative.nAv1C(handle);
            Format.Builder fb = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1)
                    .setWidth(w).setHeight(h).setFrameRate((float) fps).setAverageBitrate(bitrate);
            cfgFormat = fb.build();
            outFormat = av1c != null ? fb.setInitializationData(Collections.singletonList(av1c)).build() : cfgFormat;
            thread = new HandlerThread("aom-enc", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            handler = new Handler(thread.getLooper());
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4);
            reader.setOnImageAvailableListener(r -> pump(true), handler);
        }

        // Lấy hết khung đang chờ trong ImageReader, mã hoá, đẩy gói vào hàng đợi
        private void pump(boolean unused) {
            if (released || error != null) return;
            try {
                Image img;
                while ((img = reader.acquireNextImage()) != null) {
                    try {
                        Image.Plane p = img.getPlanes()[0];
                        long pts = img.getTimestamp() / 1000L;
                        int n = AomNative.nEncode(handle, p.getBuffer(), p.getRowStride(), pts, false);
                        if (n < 0) { error = AomNative.nError(handle); return; }
                        collect();
                    } finally { img.close(); }
                }
            } catch (Throwable t) { error = String.valueOf(t.getMessage()); }
        }

        private void collect() {
            byte[] b;
            while ((b = AomNative.nNext(handle)) != null) {
                long pts = 0; for (int i = 0; i < 8; i++) pts = (pts << 8) | (b[1 + i] & 0xFFL);
                ByteBuffer d = ByteBuffer.allocateDirect(b.length - 9);
                d.put(b, 9, b.length - 9); d.flip();
                synchronized (queue) { queue.add(new Packet(d, pts, b[0] != 0)); }
            }
        }

        private ExportException fail() { return ExportException.createForUnexpected(new IllegalStateException("libaom: " + error)); }

        @Override public Format getConfigurationFormat() { return cfgFormat; }
        @Override public String getName() { return "libaom"; }
        public int getMaxPendingFrameCount() { return Integer.MAX_VALUE; }
        @Override public Surface getInputSurface() { return reader.getSurface(); }

        @Override public void signalEndOfInputStream() throws ExportException {
            if (error != null) throw fail();
            eosSignalled = true;
            handler.post(() -> {
                pump(true);
                if (error == null && !released) {
                    if (AomNative.nFlush(handle) < 0) error = AomNative.nError(handle); else collect();
                }
                flushed = true;
            });
        }

        @Override public boolean maybeDequeueInputBuffer(DecoderInputBuffer inputBuffer) { return false; }
        @Override public void queueInputBuffer(DecoderInputBuffer inputBuffer) { throw new UnsupportedOperationException(); }

        @Override public Format getOutputFormat() throws ExportException { if (error != null) throw fail(); return outFormat; }

        @Override public ByteBuffer getOutputBuffer() throws ExportException {
            if (error != null) throw fail();
            synchronized (queue) { Packet p = queue.peek(); if (p == null) return null; p.data.rewind(); return p.data; }
        }

        @Override public MediaCodec.BufferInfo getOutputBufferInfo() throws ExportException {
            if (error != null) throw fail();
            synchronized (queue) {
                Packet p = queue.peek(); if (p == null) return null;
                MediaCodec.BufferInfo i = new MediaCodec.BufferInfo();
                i.set(0, p.data.capacity(), p.ptsUs, p.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                return i;
            }
        }

        @Override public void releaseOutputBuffer(boolean render) { synchronized (queue) { queue.poll(); } }
        @Override public void releaseOutputBuffer(long renderPresentationTimeUs) { synchronized (queue) { queue.poll(); } }

        @Override public boolean isEnded() { synchronized (queue) { return eosSignalled && flushed && queue.isEmpty(); } }

        @Override public void release() {
            if (released) return;
            released = true;
            try { reader.setOnImageAvailableListener(null, null); } catch (Throwable ignored) {}
            // dừng thread xong mới giải phóng bộ nhớ native để tránh đua
            handler.post(() -> { AomNative.nDestroy(handle); try { reader.close(); } catch (Throwable ignored) {} thread.quitSafely(); });
        }
    }
}
