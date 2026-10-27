package com.zzf.bluetoothsmp.ble;

import java.util.ArrayDeque;

/** Main-thread confined. Reservations include the active ATT procedure. */
final class BleRequestQueue<T> {
    private final ArrayDeque<Entry<T>> pending = new ArrayDeque<>();
    private final int maxCount;
    private final int maxBytes;
    private Entry<T> active;
    private int bytes;

    BleRequestQueue(int maxCount, int maxBytes) {
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
    }

    boolean offer(T request, int size) {
        if (request == null || size < 0 || count() >= maxCount || size > maxBytes - bytes) return false;
        pending.addLast(new Entry<>(request, size));
        bytes += size;
        return true;
    }

    T startNext() {
        if (active != null) return null;
        active = pending.pollFirst();
        return current();
    }

    T current() { return active == null ? null : active.value; }
    int count() { return pending.size() + (active == null ? 0 : 1); }
    int bytes() { return bytes; }

    void finish() {
        if (active != null) bytes -= active.bytes;
        active = null;
    }

    void clear() {
        pending.clear();
        active = null;
        bytes = 0;
    }

    private static final class Entry<T> {
        final T value;
        final int bytes;
        Entry(T value, int bytes) { this.value = value; this.bytes = bytes; }
    }
}
