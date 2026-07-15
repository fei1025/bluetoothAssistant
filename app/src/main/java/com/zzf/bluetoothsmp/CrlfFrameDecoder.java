package com.zzf.bluetoothsmp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Incrementally extracts CRLF-delimited frames from a byte stream. */
public final class CrlfFrameDecoder {
    private final ByteArrayOutputStream currentFrame = new ByteArrayOutputStream();
    private boolean pendingCarriageReturn;

    public List<byte[]> append(byte[] bytes, int length) {
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            byte value = bytes[i];
            if (pendingCarriageReturn) {
                if (value == '\n') {
                    frames.add(currentFrame.toByteArray());
                    currentFrame.reset();
                    pendingCarriageReturn = false;
                    continue;
                }
                currentFrame.write('\r');
                pendingCarriageReturn = false;
            }
            if (value == '\r') {
                pendingCarriageReturn = true;
            } else {
                currentFrame.write(value);
            }
        }
        return frames;
    }
}
