package com.zzf.bluetoothsmp;

/** Backoff policy shared by the runtime reconnect coordinator and tests. */
public final class BluetoothReconnectPolicy {
    private static final long[] DELAYS_MS = {1_000L, 2_000L, 5_000L, 10_000L, 30_000L};
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    private BluetoothReconnectPolicy() {
    }

    public static long delayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        int index = Math.min(attempt, DELAYS_MS.length) - 1;
        return DELAYS_MS[index];
    }
}
