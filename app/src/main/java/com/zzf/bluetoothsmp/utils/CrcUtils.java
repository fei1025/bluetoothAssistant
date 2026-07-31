package com.zzf.bluetoothsmp.utils;

/** Checksum and CRC helpers for common embedded-device protocols. */
public final class CrcUtils {
    public enum Algorithm {
        SUM8,
        XOR,
        CRC8,
        CRC16_MODBUS,
        CRC16_CCITT
    }

    private CrcUtils() {
    }

    public static int calculate(byte[] data, Algorithm algorithm) {
        if (data == null || algorithm == null) {
            throw new IllegalArgumentException("data and algorithm are required");
        }
        switch (algorithm) {
            case SUM8:
                int sum = 0;
                for (byte value : data) {
                    sum = (sum + (value & 0xFF)) & 0xFF;
                }
                return sum;
            case XOR:
                int xor = 0;
                for (byte value : data) {
                    xor ^= value & 0xFF;
                }
                return xor;
            case CRC8:
                return crc8(data);
            case CRC16_MODBUS:
                return crc16Modbus(data);
            case CRC16_CCITT:
                return crc16Ccitt(data);
            default:
                throw new IllegalArgumentException("Unsupported algorithm: " + algorithm);
        }
    }

    public static byte[] checksumBytes(byte[] data, Algorithm algorithm, boolean littleEndian) {
        int value = calculate(data, algorithm);
        if (algorithm == Algorithm.SUM8 || algorithm == Algorithm.XOR || algorithm == Algorithm.CRC8) {
            return new byte[]{(byte) value};
        }
        if (littleEndian) {
            return new byte[]{(byte) value, (byte) (value >>> 8)};
        }
        return new byte[]{(byte) (value >>> 8), (byte) value};
    }

    public static String appendChecksumHex(String hex, Algorithm algorithm, boolean littleEndian) {
        if (!HexUtils.isValidHex(hex)) {
            throw new IllegalArgumentException("Invalid hex input");
        }
        byte[] data = HexUtils.hexStringToBytes(hex);
        byte[] checksum = checksumBytes(data, algorithm, littleEndian);
        byte[] result = new byte[data.length + checksum.length];
        System.arraycopy(data, 0, result, 0, data.length);
        System.arraycopy(checksum, 0, result, data.length, checksum.length);
        return HexUtils.bytesToHex(result);
    }

    private static int crc8(byte[] data) {
        int crc = 0;
        for (byte value : data) {
            crc ^= value & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    private static int crc16Modbus(byte[] data) {
        int crc = 0xFFFF;
        for (byte value : data) {
            crc ^= value & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc & 0xFFFF;
    }

    private static int crc16Ccitt(byte[] data) {
        int crc = 0xFFFF;
        for (byte value : data) {
            crc ^= (value & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }
}
