package com.zzf.bluetoothsmp;

/** Ensures that one connection attempt publishes its failure only once. */
public final class BluetoothConnectionFailureGate {
    private boolean reported;

    public synchronized void reset() {
        reported = false;
    }

    public synchronized boolean tryReport() {
        if (reported) {
            return false;
        }
        reported = true;
        return true;
    }
}
