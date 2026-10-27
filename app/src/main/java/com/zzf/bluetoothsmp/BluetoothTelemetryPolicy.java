package com.zzf.bluetoothsmp;

import java.io.IOException;

/** Pure telemetry classification helpers that can be verified without Firebase. */
final class BluetoothTelemetryPolicy {
    private BluetoothTelemetryPolicy() {
    }

    static boolean shouldRecordNonFatal(Throwable error) {
        if (error == null) {
            return false;
        }
        // Bluetooth transport and configuration failures are expected operational
        // outcomes. Analytics records them through connect_failed; Crashlytics is
        // reserved for unexpected application/runtime failures.
        return !(error instanceof IOException)
                && !(error instanceof SecurityException)
                && !(error instanceof IllegalArgumentException);
    }

    static String uuidType(String uuid, String defaultUuid) {
        if (uuid == null || uuid.trim().isEmpty()) {
            return "unknown";
        }
        return defaultUuid != null && defaultUuid.equalsIgnoreCase(uuid)
                ? "default_spp" : "custom";
    }

    static long elapsedMillis(long startedAtMillis, long nowMillis) {
        if (startedAtMillis <= 0L || nowMillis <= startedAtMillis) {
            return 0L;
        }
        return nowMillis - startedAtMillis;
    }
}
