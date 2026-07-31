package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.utils.HexUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

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
