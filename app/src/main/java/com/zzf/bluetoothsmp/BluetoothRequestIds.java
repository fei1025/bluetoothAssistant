package com.zzf.bluetoothsmp;

import java.util.concurrent.atomic.AtomicLong;

/** Request IDs are unique across every connection in this process. */
public final class BluetoothRequestIds {
    private static final AtomicLong NEXT = new AtomicLong();
    private BluetoothRequestIds() { }
    public static long next() { return NEXT.incrementAndGet(); }
    public static long current() { return NEXT.get(); }
}
