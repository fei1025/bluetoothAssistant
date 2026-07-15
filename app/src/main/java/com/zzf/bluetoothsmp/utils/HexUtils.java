package com.zzf.bluetoothsmp.utils;

import java.nio.charset.StandardCharsets;

/**
 * Hex and ASCII conversion utilities for Bluetooth debugging
 */
public class HexUtils {

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    /**
     * Convert byte array to hex string (e.g., "48 65 6C 6C 6F")
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        char[] hexChars = new char[bytes.length * 3];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 3] = HEX_ARRAY[v >>> 4];
            hexChars[i * 3 + 1] = HEX_ARRAY[v & 0x0F];
            if (i < bytes.length - 1) {
                hexChars[i * 3 + 2] = ' ';
            }
        }
        return new String(hexChars).trim();
    }

    /**
     * Convert byte array to hex string without spaces (e.g., "48656C6C6F")
     */
    public static String bytesToHexString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_ARRAY[v >>> 4];
            hexChars[i * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * Convert hex string (with or without spaces) to byte array
     */
    public static byte[] hexStringToBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        // Remove all spaces
        hex = hex.replaceAll("\\s", "");
        if (hex.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int index = i * 2;
            try {
                bytes[i] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
            } catch (NumberFormatException e) {
                return new byte[0];
            }
        }
        return bytes;
    }

    /**
     * Convert byte array to ASCII string (printable chars only)
     * Non-printable chars are replaced with '.'
     */
    public static String bytesToAscii(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            if (b >= 32 && b <= 126) {
                sb.append((char) b);
            } else {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    /**
     * Validate if string is valid hex
     */
    public static boolean isValidHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return false;
        }
        hex = hex.replaceAll("\\s", "");
        if (hex.length() % 2 != 0) {
            return false;
        }
        return hex.matches("^[0-9A-Fa-f]+$");
    }

    /**
     * Convert string to UTF-8 bytes
     */
    public static byte[] stringToBytes(String str) {
        if (str == null) {
            return new byte[0];
        }
        return str.getBytes(StandardCharsets.UTF_8);
    }
}
