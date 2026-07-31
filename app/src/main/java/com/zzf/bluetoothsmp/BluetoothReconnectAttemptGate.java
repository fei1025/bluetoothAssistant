package com.zzf.bluetoothsmp;

/**
 * Prevents stale delayed callbacks and duplicate failures from advancing one
 * reconnect task more than once.
 */
public final class BluetoothReconnectAttemptGate {
    private int scheduledAttempt;
    private int activeAttempt;

    public synchronized void schedule(int attempt) {
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        scheduledAttempt = attempt;
        activeAttempt = 0;
    }

    public synchronized boolean claim(int attempt) {
        if (attempt <= 0 || attempt != scheduledAttempt || activeAttempt != 0) {
            return false;
        }
        activeAttempt = attempt;
        return true;
    }

    public synchronized boolean fail(int attempt) {
        if (attempt <= 0 || attempt != activeAttempt) {
            return false;
        }
        activeAttempt = 0;
        return true;
    }

    public synchronized boolean complete(int attempt) {
        if (attempt <= 0 || attempt != activeAttempt) {
            return false;
        }
        scheduledAttempt = 0;
        activeAttempt = 0;
        return true;
    }

    public synchronized void cancel() {
        scheduledAttempt = 0;
        activeAttempt = 0;
    }
}
