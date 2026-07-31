package com.zzf.bluetoothsmp.utils;

import androidx.annotation.Nullable;

import java.util.Locale;

public final class BluetoothAddressUtils {

    private BluetoothAddressUtils() {
    }

    @Nullable
    public static String normalize(@Nullable String address) {
        if (address == null) {
            return null;
        }
        String normalized = address.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /** Masks the first three octets for reports shown or copied by the user. */
    @Nullable
    public static String mask(@Nullable String address) {
        String normalized = normalize(address);
        if (normalized == null) {
            return null;
        }
        String[] octets = normalized.split(":");
        if (octets.length == 6) {
            return "XX:XX:XX:" + octets[3] + ":" + octets[4] + ":" + octets[5];
        }
        if (normalized.length() <= 4) {
            return "XXXX";
        }
        return "XXXX" + normalized.substring(normalized.length() - 4);
    }
}
