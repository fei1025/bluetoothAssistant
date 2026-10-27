package com.zzf.bluetoothsmp;

import android.content.Context;

/** User intent for the local SPP listener, independent of socket/process lifetime. */
public final class BluetoothServerSettings {
    private static final String PREFERENCES = "bluetooth_server";
    private static final String USER_ENABLED = "user_enabled";

    private BluetoothServerSettings() {
    }

    public static boolean isEnabled(Context context) {
        // Preserve automatic listening for existing installations until the user disables it.
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(USER_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean(USER_ENABLED, enabled).apply();
    }
}
