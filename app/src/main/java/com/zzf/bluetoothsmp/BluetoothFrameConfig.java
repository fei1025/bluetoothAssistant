package com.zzf.bluetoothsmp;

import java.util.Arrays;

public final class BluetoothFrameConfig {
    public static final int DEFAULT_MAX_FRAME_BYTES = 64 * 1024;
    public static final long DEFAULT_TIMEOUT_MILLIS = 1000L;

    private final BluetoothFrameMode mode;
    private final int maxFrameBytes;
    private final int fixedLength;
    private final byte[] delimiter;
    private final long timeoutMillis;

    public BluetoothFrameConfig(BluetoothFrameMode mode, int maxFrameBytes, int fixedLength,
                                byte[] delimiter, long timeoutMillis) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        if (fixedLength <= 0 || fixedLength > maxFrameBytes) {
            throw new IllegalArgumentException("fixedLength must be within maxFrameBytes");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        byte[] safeDelimiter = delimiter == null ? new byte[0] : delimiter.clone();
        if ((mode == BluetoothFrameMode.CRLF || mode == BluetoothFrameMode.LF
                || mode == BluetoothFrameMode.CR || mode == BluetoothFrameMode.CUSTOM)
                && safeDelimiter.length == 0) {
            throw new IllegalArgumentException("delimiter must not be empty for delimiter mode");
        }
        this.mode = mode;
        this.maxFrameBytes = maxFrameBytes;
        this.fixedLength = fixedLength;
        this.delimiter = safeDelimiter;
        this.timeoutMillis = timeoutMillis;
    }

    public static BluetoothFrameConfig defaultConfig() {
        return new BluetoothFrameConfig(BluetoothFrameMode.CRLF, DEFAULT_MAX_FRAME_BYTES,
                1, new byte[]{'\r', '\n'}, 0L);
    }

    public BluetoothFrameMode getMode() {
        return mode;
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public int getFixedLength() {
        return fixedLength;
    }

    public byte[] getDelimiter() {
        return delimiter.clone();
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof BluetoothFrameConfig)) return false;
        BluetoothFrameConfig other = (BluetoothFrameConfig) object;
        return maxFrameBytes == other.maxFrameBytes
                && fixedLength == other.fixedLength
                && timeoutMillis == other.timeoutMillis
                && mode == other.mode
                && Arrays.equals(delimiter, other.delimiter);
    }

    @Override
    public int hashCode() {
        int result = mode.hashCode();
        result = 31 * result + maxFrameBytes;
        result = 31 * result + fixedLength;
        result = 31 * result + Arrays.hashCode(delimiter);
        result = 31 * result + (int) (timeoutMillis ^ (timeoutMillis >>> 32));
        return result;
    }
}
