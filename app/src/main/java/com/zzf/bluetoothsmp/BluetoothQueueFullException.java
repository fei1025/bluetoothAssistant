package com.zzf.bluetoothsmp;

import java.io.IOException;

/** Admission failure, not a broken Bluetooth transport. */
public final class BluetoothQueueFullException extends IOException {
    public BluetoothQueueFullException(String detail) { super("QUEUE_FULL: " + detail); }
}
