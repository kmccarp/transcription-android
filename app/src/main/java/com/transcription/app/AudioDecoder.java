package com.transcription.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import androidx.annotation.NonNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Decodes any audio file that Android's {@link MediaCodec} can play (e.g. the
 * Ogg/Opus voice notes WhatsApp produces) into a {@code float[]} of 16 kHz
 * mono samples in the range [-1, 1] — exactly what whisper.cpp wants.
 *
 * <p>Resampling is a naive linear interpolation. Good enough for human voice.
 * The big-ticket item is the codec itself; we let the platform do that.
 */
public final class AudioDecoder {

    /** Whisper expects 16 kHz mono. */
    public static final int TARGET_RATE = 16_000;

    /** Hard cap (~10 minutes of audio at 16 kHz mono). */
    private static final int MAX_SAMPLES = 16_000 * 60 * 10;

    private AudioDecoder() {}

    /**
     * Convenience: copy {@code in} to a temp file (MediaExtractor needs a
     * seekable source) and decode it.
     */
    public static @NonNull float[] decode(@NonNull InputStream in,
                                          @NonNull File scratchDir) throws IOException {
        File tmp = File.createTempFile("share-", ".audio", scratchDir);
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return decode(tmp);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Decodes a file path. */
    public static @NonNull float[] decode(@NonNull File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int audioTrack = selectAudioTrack(extractor);
            if (audioTrack < 0) {
                throw new IOException("No audio track in " + file.getName());
            }
            extractor.selectTrack(audioTrack);
            MediaFormat fmt = extractor.getTrackFormat(audioTrack);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IOException("Audio track has no MIME");
            }

            int sourceRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : TARGET_RATE;
            int channels   = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            short[] mono = decodeToMonoPcm16(extractor, fmt, channels);
            return resampleAndNormalize(mono, sourceRate);
        } finally {
            extractor.release();
        }
    }

    private static int selectAudioTrack(MediaExtractor ex) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String m = f.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) return i;
        }
        return -1;
    }

    private static short[] decodeToMonoPcm16(MediaExtractor extractor,
                                             MediaFormat sourceFormat,
                                             int channels) throws IOException {
        String mime = sourceFormat.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        try {
            sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible /* harmless ignored */);
            codec.configure(sourceFormat, null, null, 0);
            codec.start();

            ShortBufferList buf = new ShortBufferList();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIdx);
                        if (in == null) continue;
                        in.clear();
                        int read = extractor.readSampleData(in, 0);
                        if (read < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long ts = extractor.getSampleTime();
                            codec.queueInputBuffer(inIdx, 0, read, ts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIdx);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        out.order(ByteOrder.LITTLE_ENDIAN);
                        appendPcm16Mono(buf, out, channels);
                        if (buf.size() > MAX_SAMPLES) {
                            throw new IOException(
                                    "Audio exceeds " + (MAX_SAMPLES / TARGET_RATE) + " s cap");
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            return buf.toArray();
        } finally {
            codec.stop();
            codec.release();
        }
    }

    private static void appendPcm16Mono(ShortBufferList buf, ByteBuffer in, int channels) {
        // Decoder output is interleaved 16-bit PCM. Average channels to mono.
        int shorts = in.remaining() / 2;
        if (channels <= 1) {
            for (int i = 0; i < shorts; i++) {
                buf.add(in.getShort());
            }
            return;
        }
        int frames = shorts / channels;
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += in.getShort();
            }
            buf.add((short) (sum / channels));
        }
    }

    /** Linear-interpolation resampler from {@code sourceRate} to 16 kHz. */
    private static float[] resampleAndNormalize(short[] in, int sourceRate) {
        if (in.length == 0) return new float[0];
        if (sourceRate == TARGET_RATE) {
            float[] out = new float[in.length];
            for (int i = 0; i < in.length; i++) out[i] = in[i] / 32768f;
            return out;
        }
        long outLenLong = (long) in.length * TARGET_RATE / sourceRate;
        int outLen = (int) Math.min(outLenLong, MAX_SAMPLES);
        float[] out = new float[outLen];
        double step = (double) sourceRate / TARGET_RATE;
        for (int i = 0; i < outLen; i++) {
            double srcIdx = i * step;
            int lo = (int) srcIdx;
            int hi = Math.min(lo + 1, in.length - 1);
            double frac = srcIdx - lo;
            float lv = in[lo] / 32768f;
            float hv = in[hi] / 32768f;
            out[i] = (float) (lv + (hv - lv) * frac);
        }
        return out;
    }

    /** Tiny growable buffer of shorts (saves allocating Short boxes). */
    private static final class ShortBufferList {
        private short[] data = new short[16 * 1024];
        private int size;

        void add(short v) {
            if (size == data.length) {
                short[] grown = new short[data.length * 2];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = v;
        }

        int size() { return size; }

        short[] toArray() {
            short[] copy = new short[size];
            System.arraycopy(data, 0, copy, 0, size);
            return copy;
        }
    }
}
