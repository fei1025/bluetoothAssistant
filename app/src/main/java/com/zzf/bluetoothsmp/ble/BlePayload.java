package com.zzf.bluetoothsmp.ble;

import com.zzf.bluetoothsmp.BluetoothEncodingUtils;
import com.zzf.bluetoothsmp.BluetoothTextEncoding;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

public final class BlePayload {
    public static final int MAX_SEND_BYTES = 64 * 1024;
    private BlePayload() { }

    /** ATT attribute values are at most 512 bytes, even with an MTU of 517. */
    public static int chunkSize(int mtu) { return Math.min(512, Math.max(20, mtu - 3)); }

    public static byte[] chunk(byte[] payload, int offset, int mtu) {
        if (offset < 0 || offset >= payload.length) throw new IllegalArgumentException("offset");
        return Arrays.copyOfRange(payload, offset, Math.min(payload.length, offset + chunkSize(mtu)));
    }

    public static byte[] encode(String text, BluetoothTextEncoding encoding, String suffix) {
        if (text == null || text.length() > MAX_SEND_BYTES * 3) throw new IllegalArgumentException("size");
        byte[] bytes = BluetoothEncodingUtils.encode(text, encoding);
        // Hex is an explicit binary payload; a text line ending never changes it.
        byte[] tail = encoding == BluetoothTextEncoding.HEX ? new byte[0]
                : BluetoothEncodingUtils.encode(suffix, encoding);
        if (bytes.length + tail.length == 0 || bytes.length + tail.length > MAX_SEND_BYTES) {
            throw new IllegalArgumentException("size");
        }
        byte[] result = Arrays.copyOf(bytes, bytes.length + tail.length);
        System.arraycopy(tail, 0, result, bytes.length, tail.length);
        return result;
    }

    public static UUID uuid(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("[0-9a-f]{4}")) normalized = "0000" + normalized;
        if (normalized.matches("[0-9a-f]{8}")) normalized += "-0000-1000-8000-00805f9b34fb";
        if (normalized.length() != 36) throw new IllegalArgumentException("UUID");
        return UUID.fromString(normalized);
    }
}
