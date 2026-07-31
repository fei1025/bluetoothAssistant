package com.zzf.bluetoothsmp;

import android.content.Context;
import android.content.SharedPreferences;

import com.zzf.bluetoothsmp.utils.HexUtils;

public final class BluetoothProtocolConfigStore {
    private static final String PREFS = "bluetooth_protocol";
    private static final String MODE = "mode.";
    private static final String MAX_FRAME = "max.";
    private static final String FIXED_LENGTH = "fixed.";
    private static final String DELIMITER = "delimiter.";
    private static final String TIMEOUT = "timeout.";

    private BluetoothProtocolConfigStore() {
    }

    public static BluetoothFrameConfig get(Context context, String address) {
        BluetoothFrameConfig defaults = BluetoothFrameConfig.defaultConfig();
        if (context == null || address == null || address.trim().isEmpty()) {
            return defaults;
        }
        String key = address.trim().toUpperCase(java.util.Locale.ROOT);
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        BluetoothFrameMode mode;
        try {
            mode = BluetoothFrameMode.valueOf(preferences.getString(MODE + key,
                    defaults.getMode().name()));
        } catch (IllegalArgumentException ignored) {
            mode = defaults.getMode();
        }
        int maxFrame = positiveOrDefault(preferences.getInt(MAX_FRAME + key,
                defaults.getMaxFrameBytes()), defaults.getMaxFrameBytes());
        int fixedLength = positiveOrDefault(preferences.getInt(FIXED_LENGTH + key,
                defaults.getFixedLength()), defaults.getFixedLength());
        long timeout = Math.max(0L, preferences.getLong(TIMEOUT + key,
                defaults.getTimeoutMillis()));
        byte[] delimiter = HexUtils.hexStringToBytes(preferences.getString(
                DELIMITER + key, "0D0A"));
        if (delimiter.length == 0) {
            delimiter = defaults.getDelimiter();
        }
        try {
            return new BluetoothFrameConfig(mode, maxFrame, fixedLength, delimiter, timeout);
        } catch (IllegalArgumentException ignored) {
            return defaults;
        }
    }

    public static void save(Context context, String address, BluetoothFrameConfig config) {
        if (context == null || address == null || address.trim().isEmpty() || config == null) {
            return;
        }
        String key = address.trim().toUpperCase(java.util.Locale.ROOT);
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(MODE + key, config.getMode().name())
                .putInt(MAX_FRAME + key, config.getMaxFrameBytes())
                .putInt(FIXED_LENGTH + key, config.getFixedLength())
                .putString(DELIMITER + key, HexUtils.bytesToHexString(config.getDelimiter()))
                .putLong(TIMEOUT + key, config.getTimeoutMillis())
                .apply();
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
