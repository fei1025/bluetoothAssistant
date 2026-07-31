package com.zzf.bluetoothsmp;

import java.util.List;

/** Incrementally extracts CRLF-delimited frames from a byte stream. */
public final class CrlfFrameDecoder {
    public static final int DEFAULT_MAX_FRAME_BYTES = 64 * 1024;
    private final StreamFrameDecoder decoder;

    public CrlfFrameDecoder() {
        this(DEFAULT_MAX_FRAME_BYTES);
    }

    public CrlfFrameDecoder(int maxFrameBytes) {
        decoder = new StreamFrameDecoder(new BluetoothFrameConfig(BluetoothFrameMode.CRLF,
                maxFrameBytes, 1, new byte[]{'\r', '\n'}, 0L));
    }

    public List<byte[]> append(byte[] bytes, int length) {
        return decoder.append(bytes, length);
    }

    public int getDroppedFrameCount() {
        return decoder.getDroppedFrameCount();
    }
}
