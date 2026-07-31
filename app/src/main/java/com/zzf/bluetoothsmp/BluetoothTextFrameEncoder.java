package com.zzf.bluetoothsmp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Builds a text payload according to the selected device's encoding and frame mode. */
public final class BluetoothTextFrameEncoder {
    private BluetoothTextFrameEncoder() {
    }

    public static byte[] encode(String text, BluetoothFrameConfig config,
                                 BluetoothTextEncoding encoding) {
        BluetoothFrameConfig safeConfig = config == null
                ? BluetoothFrameConfig.defaultConfig() : config;
        BluetoothTextEncoding safeEncoding = encoding == null
                ? BluetoothTextEncoding.UTF_8 : encoding;
        byte[] body = BluetoothEncodingUtils.encode(text, safeEncoding);

        switch (safeConfig.getMode()) {
            case RAW:
            case TIMEOUT:
                return body;
            case FIXED_LENGTH:
                if (body.length != safeConfig.getFixedLength()) {
                    throw new IllegalArgumentException("text payload length must be "
                            + safeConfig.getFixedLength() + " bytes");
                }
                return body;
            case CRLF:
            case LF:
            case CR:
            case CUSTOM:
                return appendDelimiter(body, safeConfig.getDelimiter());
            default:
                throw new IllegalArgumentException("unsupported frame mode: "
                        + safeConfig.getMode());
        }
    }

    private static byte[] appendDelimiter(byte[] body, byte[] delimiter) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    body.length + delimiter.length);
            output.write(body);
            output.write(delimiter);
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
