package com.winlator.alsaserver;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.winlator.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One sound stream from a game (Wine's ALSA output through Winlator's android_aserver plugin),
 * played with an Android AudioTrack. From Winlator 11.2 (LGPL-2.1, github.com/brunodev85/winlator);
 * the protocol must match the plugin in the 11.2 runtime.
 */
public class ALSAClient {
    private static short framesPerBuffer = 256;
    private ByteBuffer auxBuffer;
    private int bufferCapacity;
    private int bufferSize;
    private byte frameBytes;
    protected final Options options;
    private int position;
    private ByteBuffer sharedBuffer;
    private DataType dataType = DataType.U8;
    private AudioTrack audioTrack = null;
    private byte channels = 2;
    private int sampleRate = 0;
    private short previousUnderrunCount = 0;

    public enum DataType {
        U8(1), S16LE(2), S16BE(2), FLOATLE(4), FLOATBE(4);

        public final byte byteCount;

        DataType(int byteCount) {
            this.byteCount = (byte)byteCount;
        }
    }

    public static class Options {
        public short latencyMillis = 16;
        public byte performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
        public float volume = 1.0f;
    }

    public ALSAClient(Options options) {
        this.options = options;
    }

    /** Every stream that has a sound track, so a mute reaches the ones already playing. */
    private static final Set<ALSAClient> tracks = ConcurrentHashMap.newKeySet();
    private static volatile boolean muted = false;

    /**
     * Mutes or unmutes every game sound stream, also the ones that open later (Quest Steam: a game
     * getting ready in the background mustn't be heard).
     */
    public static void setMuted(boolean mute) {
        muted = mute;
        for (ALSAClient client : tracks) client.applyVolume();
    }

    private void applyVolume() {
        AudioTrack track = audioTrack;
        if (track == null) return;
        try {
            track.setVolume(muted ? 0f : options.volume);
        }
        catch (Exception e) {
            // Released meanwhile.
        }
    }

    public void release() {
        tracks.remove(this);
        if (sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
            sharedBuffer = null;
        }
        if (audioTrack != null) {
            audioTrack.pause();
            audioTrack.flush();
            audioTrack.release();
            audioTrack = null;
        }
    }

    public static int getPCMEncoding(DataType dataType) {
        switch (dataType) {
            case U8: return AudioFormat.ENCODING_PCM_8BIT;
            case S16LE:
            case S16BE: return AudioFormat.ENCODING_PCM_16BIT;
            case FLOATLE:
            case FLOATBE: return AudioFormat.ENCODING_PCM_FLOAT;
            default: return AudioFormat.ENCODING_DEFAULT;
        }
    }

    public static int getChannelConfig(int channels) {
        return channels <= 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
    }

    public void prepare() {
        position = 0;
        previousUnderrunCount = 0;
        frameBytes = (byte)(channels * dataType.byteCount);
        release();
        if (!isValidBufferSize()) return;
        AudioFormat format = new AudioFormat.Builder()
            .setEncoding(getPCMEncoding(dataType))
            .setSampleRate(sampleRate)
            .setChannelMask(getChannelConfig(channels))
            .build();
        audioTrack = new AudioTrack.Builder()
            .setPerformanceMode(options.performanceMode)
            .setAudioFormat(format)
            .setBufferSizeInBytes(getBufferSizeInBytes())
            .build();
        bufferCapacity = audioTrack.getBufferCapacityInFrames();
        tracks.add(this);
        if (muted || options.volume != 1.0f) applyVolume();
        audioTrack.play();
    }

    public void start() {
        if (audioTrack != null && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) audioTrack.play();
    }

    public void stop() {
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.flush();
        }
    }

    public void pause() {
        if (audioTrack != null) audioTrack.pause();
    }

    public void drain() {
        if (audioTrack != null) audioTrack.flush();
    }

    public void writeDataToTrack(ByteBuffer data) {
        if (dataType == DataType.S16LE || dataType == DataType.FLOATLE) data.order(ByteOrder.LITTLE_ENDIAN);
        else if (dataType == DataType.S16BE || dataType == DataType.FLOATBE) data.order(ByteOrder.BIG_ENDIAN);
        if (audioTrack == null) return;
        data.position(0);
        do {
            try {
                int bytesWritten = audioTrack.write(data, data.remaining(), AudioTrack.WRITE_BLOCKING);
                if (bytesWritten < 0) break;
                increaseBufferSizeIfUnderrunOccurs();
            }
            catch (Exception e) {
                break;
            }
        }
        while (data.position() != data.limit());
        position += data.position();
        data.rewind();
    }

    private void increaseBufferSizeIfUnderrunOccurs() {
        int underrunCount = audioTrack.getUnderrunCount();
        if (underrunCount > previousUnderrunCount && bufferSize < bufferCapacity) {
            previousUnderrunCount = (short)underrunCount;
            bufferSize += framesPerBuffer;
            audioTrack.setBufferSizeInFrames(bufferSize);
        }
    }

    public int pointer() {
        return audioTrack != null && frameBytes > 0 ? position / frameBytes : 0;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public void setChannels(int channels) {
        this.channels = (byte)channels;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public ByteBuffer getSharedBuffer() {
        return sharedBuffer;
    }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        if (sharedBuffer != null) {
            auxBuffer = ByteBuffer.allocateDirect(getBufferSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
            this.sharedBuffer = sharedBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }
        else {
            auxBuffer = null;
            this.sharedBuffer = null;
        }
    }

    public ByteBuffer getAuxBuffer() {
        return auxBuffer;
    }

    public int getBufferSizeInBytes() {
        return bufferSize * frameBytes;
    }

    public static int latencyMillisToBufferSize(int latencyMillis, int channels, DataType dataType, int sampleRate) {
        int frameBytes = dataType.byteCount * channels;
        int frames = (int)((latencyMillis * sampleRate) / 1000.0f);
        int bufferSize = Math.max(framesPerBuffer, (frames / framesPerBuffer) * framesPerBuffer);
        return bufferSize * frameBytes;
    }

    private boolean isValidBufferSize() {
        return frameBytes > 0 && bufferSize % frameBytes == 0 && bufferSize > 0;
    }

    public static void assignFramesPerBuffer(Context context) {
        try {
            AudioManager am = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            framesPerBuffer = Short.parseShort(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
            if (framesPerBuffer == 0) framesPerBuffer = 256;
        }
        catch (Exception e) {
            framesPerBuffer = 256;
        }
    }
}
