package com.zzf.bluetoothsmp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Incremental, bounded decoder. Delimiter matching is linear in the input size. */
public final class StreamFrameDecoder {
    private final BluetoothFrameConfig config;
    private final byte[] delimiter;
    private final int[] delimiterPrefix;
    private byte[] buffer = new byte[128];
    private int size;
    private int matchedDelimiter;
    private int droppedFrameCount;
    private boolean discardingOversizedFrame;
    private long lastDataTimestamp = Long.MIN_VALUE;

    public StreamFrameDecoder(BluetoothFrameConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
        delimiter = config.getDelimiter();
        delimiterPrefix = new int[delimiter.length];
        for (int i = 1, matched = 0; i < delimiter.length; i++) {
            while (matched > 0 && delimiter[i] != delimiter[matched]) matched = delimiterPrefix[matched - 1];
            if (delimiter[i] == delimiter[matched]) matched++;
            delimiterPrefix[i] = matched;
        }
    }

    public synchronized List<byte[]> append(byte[] bytes, int length) {
        return append(bytes, length, monotonicMillis());
    }

    public synchronized List<byte[]> append(byte[] bytes, int length, long nowMillis) {
        if (bytes == null || length < 0 || length > bytes.length) throw new IllegalArgumentException("invalid input buffer");
        List<byte[]> frames = new ArrayList<>();
        // A delayed timer must not merge two bursts that were separated by an idle gap.
        flushExpired(nowMillis, frames);
        if (length == 0) return frames;
        if (config.getMode() == BluetoothFrameMode.RAW) {
            for (int offset = 0; offset < length;) {
                int count = Math.min(config.getMaxFrameBytes(), length - offset);
                frames.add(Arrays.copyOfRange(bytes, offset, offset + count));
                offset += count;
            }
            lastDataTimestamp = Long.MIN_VALUE;
            return frames;
        }
        for (int i = 0; i < length; i++) {
            byte value = bytes[i];
            switch (config.getMode()) {
                case FIXED_LENGTH:
                    appendByte(value);
                    if (size == config.getFixedLength()) emit(frames, size);
                    break;
                case TIMEOUT:
                    if (!discardingOversizedFrame) {
                        if (size == config.getMaxFrameBytes()) {
                            size = 0;
                            discardingOversizedFrame = true;
                        } else appendByte(value);
                    }
                    break;
                default:
                    appendDelimited(value, frames);
                    break;
            }
        }
        lastDataTimestamp = size > 0 || discardingOversizedFrame ? nowMillis : Long.MIN_VALUE;
        return frames;
    }

    public synchronized List<byte[]> pollTimeout(long nowMillis) {
        List<byte[]> frames = new ArrayList<>();
        flushExpired(nowMillis, frames);
        return frames;
    }

    private void flushExpired(long nowMillis, List<byte[]> frames) {
        long timeout = config.getTimeoutMillis();
        if (timeout <= 0 || lastDataTimestamp == Long.MIN_VALUE || nowMillis - lastDataTimestamp < timeout) return;
        if (discardingOversizedFrame) droppedFrameCount++;
        else if (size > 0) frames.add(Arrays.copyOf(buffer, size));
        resetFrame();
    }

    private void appendDelimited(byte value, List<byte[]> frames) {
        while (matchedDelimiter > 0 && value != delimiter[matchedDelimiter]) {
            matchedDelimiter = delimiterPrefix[matchedDelimiter - 1];
        }
        if (value == delimiter[matchedDelimiter]) matchedDelimiter++;
        if (!discardingOversizedFrame) appendByte(value);
        if (matchedDelimiter == delimiter.length) {
            if (discardingOversizedFrame || size - delimiter.length > config.getMaxFrameBytes()) droppedFrameCount++;
            else frames.add(Arrays.copyOf(buffer, size - delimiter.length));
            resetFrame();
        } else if (!discardingOversizedFrame
                && (long) size > (long) config.getMaxFrameBytes() + delimiter.length) {
            size = 0;
            discardingOversizedFrame = true;
        }
    }

    private void appendByte(byte value) {
        if (size == buffer.length) {
            long ceiling = Math.min(Integer.MAX_VALUE - 8L,
                    (long) config.getMaxFrameBytes() + delimiter.length + 1L);
            int next = (int) Math.min(ceiling, Math.max((long) size + 1L, 2L * buffer.length));
            buffer = Arrays.copyOf(buffer, next);
        }
        buffer[size++] = value;
    }

    private void emit(List<byte[]> frames, int count) {
        frames.add(Arrays.copyOf(buffer, count));
        resetFrame();
    }
    private void resetFrame() {
        size = 0;
        matchedDelimiter = 0;
        discardingOversizedFrame = false;
        lastDataTimestamp = Long.MIN_VALUE;
    }
    public synchronized int getDroppedFrameCount() { return droppedFrameCount; }
    public synchronized int getBufferedByteCount() { return size; }
    public static long monotonicMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
}
