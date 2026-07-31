package com.zzf.bluetoothsmp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Incremental, bounded decoder for common SPP framing strategies. */
public final class StreamFrameDecoder {
    private final BluetoothFrameConfig config;
    private final ByteArrayOutputStream currentFrame = new ByteArrayOutputStream();
    private final ByteArrayOutputStream discardWindow = new ByteArrayOutputStream();
    private int droppedFrameCount;
    private boolean discardingOversizedFrame;
    private long lastDataTimestamp = -1L;

    public StreamFrameDecoder(BluetoothFrameConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    public synchronized List<byte[]> append(byte[] bytes, int length) {
        return append(bytes, length, System.currentTimeMillis());
    }

    public synchronized List<byte[]> append(byte[] bytes, int length, long nowMillis) {
        if (bytes == null || length < 0 || length > bytes.length) {
            throw new IllegalArgumentException("invalid input buffer");
        }
        if (length == 0) {
            return new ArrayList<>();
        }
        lastDataTimestamp = nowMillis;
        List<byte[]> frames;
        switch (config.getMode()) {
            case RAW:
                frames = appendRaw(bytes, length);
                break;
            case FIXED_LENGTH:
                frames = appendFixed(bytes, length);
                break;
            case TIMEOUT:
                frames = appendTimeout(bytes, length);
                break;
            case CRLF:
            case LF:
            case CR:
            case CUSTOM:
            default:
                frames = appendDelimited(bytes, length);
                break;
        }
        if (config.getMode() != BluetoothFrameMode.RAW) {
            lastDataTimestamp = currentFrame.size() == 0 && !discardingOversizedFrame
                    ? -1L : nowMillis;
        }
        return frames;
    }

    public synchronized List<byte[]> pollTimeout(long nowMillis) {
        List<byte[]> frames = new ArrayList<>();
        long timeoutMillis = config.getTimeoutMillis();
        if (timeoutMillis <= 0 || lastDataTimestamp < 0
                || nowMillis - lastDataTimestamp < timeoutMillis) {
            return frames;
        }
        if (discardingOversizedFrame) {
            droppedFrameCount++;
            resetDiscarding();
        } else if (currentFrame.size() > 0) {
            frames.add(currentFrame.toByteArray());
            currentFrame.reset();
        }
        lastDataTimestamp = -1L;
        return frames;
    }

    public synchronized int getDroppedFrameCount() {
        return droppedFrameCount;
    }

    public synchronized int getBufferedByteCount() {
        return currentFrame.size();
    }

    private List<byte[]> appendRaw(byte[] bytes, int length) {
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset < length) {
            int chunkLength = Math.min(config.getMaxFrameBytes(), length - offset);
            frames.add(Arrays.copyOfRange(bytes, offset, offset + chunkLength));
            offset += chunkLength;
        }
        lastDataTimestamp = -1L;
        return frames;
    }

    private List<byte[]> appendFixed(byte[] bytes, int length) {
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            currentFrame.write(bytes[i]);
            if (currentFrame.size() == config.getFixedLength()) {
                frames.add(currentFrame.toByteArray());
                currentFrame.reset();
                lastDataTimestamp = -1L;
            }
        }
        return frames;
    }

    private List<byte[]> appendTimeout(byte[] bytes, int length) {
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            currentFrame.write(bytes[i]);
            if (currentFrame.size() > config.getMaxFrameBytes()) {
                currentFrame.reset();
                droppedFrameCount++;
                lastDataTimestamp = -1L;
            }
        }
        return frames;
    }

    private List<byte[]> appendDelimited(byte[] bytes, int length) {
        List<byte[]> frames = new ArrayList<>();
        byte[] delimiter = config.getDelimiter();
        for (int i = 0; i < length; i++) {
            byte value = bytes[i];
            if (discardingOversizedFrame) {
                appendDiscardByte(value, delimiter);
                if (endsWith(discardWindow, delimiter)) {
                    droppedFrameCount++;
                    resetDiscarding();
                }
                continue;
            }

            currentFrame.write(value);
            if (endsWith(currentFrame, delimiter)) {
                byte[] all = currentFrame.toByteArray();
                int payloadLength = all.length - delimiter.length;
                if (payloadLength > config.getMaxFrameBytes()) {
                    droppedFrameCount++;
                } else {
                    frames.add(Arrays.copyOf(all, payloadLength));
                }
                currentFrame.reset();
                lastDataTimestamp = -1L;
            } else if (currentFrame.size() > config.getMaxFrameBytes() + delimiter.length) {
                beginDiscarding(currentFrame, delimiter);
            }
        }
        return frames;
    }

    private void beginDiscarding(ByteArrayOutputStream source, byte[] delimiter) {
        byte[] all = source.toByteArray();
        discardWindow.reset();
        int keep = Math.min(Math.max(0, delimiter.length - 1), all.length);
        if (keep > 0) {
            discardWindow.write(all, all.length - keep, keep);
        }
        source.reset();
        discardingOversizedFrame = true;
    }

    private void appendDiscardByte(byte value, byte[] delimiter) {
        discardWindow.write(value);
        int maxWindow = Math.max(1, delimiter.length);
        if (discardWindow.size() > maxWindow) {
            byte[] all = discardWindow.toByteArray();
            discardWindow.reset();
            discardWindow.write(all, all.length - maxWindow, maxWindow);
        }
    }

    private boolean endsWith(ByteArrayOutputStream source, byte[] suffix) {
        if (suffix.length == 0 || source.size() < suffix.length) {
            return false;
        }
        byte[] all = source.toByteArray();
        int start = all.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (all[start + i] != suffix[i]) {
                return false;
            }
        }
        return true;
    }

    private void resetDiscarding() {
        discardingOversizedFrame = false;
        discardWindow.reset();
        currentFrame.reset();
        lastDataTimestamp = -1L;
    }
}
