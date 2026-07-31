package com.zzf.bluetoothsmp;

import android.content.Context;
import android.content.SharedPreferences;

public final class BluetoothTextEncodingStore {
    private static final String PREFS = "bluetooth_protocol";
    private static final String PREFIX = "encoding.";

    private BluetoothTextEncodingStore() {
    }

    public static BluetoothTextEncoding get(Context context, String address) {
        if (context == null || address == null || address.trim().isEmpty()) {
            return BluetoothTextEncoding.UTF_8;
        }
        String key = address.trim().toUpperCase(java.util.Locale.ROOT);
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            return BluetoothTextEncoding.valueOf(preferences.getString(
                    PREFIX + key, BluetoothTextEncoding.UTF_8.name()));
        } catch (IllegalArgumentException ignored) {
            return BluetoothTextEncoding.UTF_8;
        }
    }

    public static void save(Context context, String address, BluetoothTextEncoding encoding) {
        if (context == null || address == null || address.trim().isEmpty() || encoding == null) {
            return;
        }
        String key = address.trim().toUpperCase(java.util.Locale.ROOT);
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREFIX + key, encoding.name()).apply();
    }
}
