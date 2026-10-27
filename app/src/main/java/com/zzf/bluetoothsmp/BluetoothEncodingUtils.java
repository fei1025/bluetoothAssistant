package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.utils.HexUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

public final class BluetoothEncodingUtils {
    private BluetoothEncodingUtils() {
    }

    public static byte[] encode(String value, BluetoothTextEncoding encoding) {
        String safeValue = value == null ? "" : value;
        if (encoding == BluetoothTextEncoding.HEX) {
            if (!HexUtils.isValidHex(safeValue)) {
                throw new IllegalArgumentException("HEX text must contain valid bytes");
            }
            return HexUtils.hexStringToBytes(safeValue);
        }
        return safeValue.getBytes(charsetFor(encoding));
    }

    public static String decode(byte[] bytes, BluetoothTextEncoding encoding) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        if (encoding == BluetoothTextEncoding.HEX) {
            return HexUtils.bytesToHex(safeBytes);
        }
        return new String(safeBytes, charsetFor(encoding));
    }

    /** One instance per RAW session; byte reads need not end on a character boundary. */
    public static final class StreamingTextDecoder {
        private final BluetoothTextEncoding encoding;
        private final CharsetDecoder decoder;
        private byte[] pending = new byte[0];

        public StreamingTextDecoder(BluetoothTextEncoding encoding) {
            this.encoding = encoding;
            decoder = encoding == BluetoothTextEncoding.HEX ? null : charsetFor(encoding).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
        }

        public synchronized String decode(byte[] bytes) {
            if (decoder == null) return BluetoothEncodingUtils.decode(bytes, encoding);
            byte[] input = Arrays.copyOf(pending, pending.length + bytes.length);
            System.arraycopy(bytes, 0, input, pending.length, bytes.length);
            ByteBuffer source = ByteBuffer.wrap(input);
            CharBuffer target = CharBuffer.allocate(Math.max(1,
                    (int) Math.ceil(input.length * decoder.maxCharsPerByte())));
            decoder.decode(source, target, false);
            pending = new byte[source.remaining()];
            source.get(pending);
            target.flip();
            return target.toString();
        }
    }

    private static Charset charsetFor(BluetoothTextEncoding encoding) {
        if (encoding == BluetoothTextEncoding.GBK) {
            return Charset.forName("GBK");
        }
        if (encoding == BluetoothTextEncoding.ASCII) {
            return StandardCharsets.US_ASCII;
        }
        return StandardCharsets.UTF_8;
    }
}
