package com.zzf.bluetoothsmp.ble;

import com.zzf.bluetoothsmp.utils.HexUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded raw capture, independent of the shorter UI preview. */
final class BleLogStore {
    static final int MAX_ENTRIES = 512;
    static final int MAX_BYTES = 1024 * 1024;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private int bytes;
    private long dropped;

    void add(String label, byte[] payload) {
        byte[] raw = payload == null ? new byte[0] : payload.clone();
        Entry entry = new Entry(System.currentTimeMillis(), label, raw);
        if (entry.size() > MAX_BYTES) { dropped++; return; }
        while (!entries.isEmpty() && (entries.size() >= MAX_ENTRIES || bytes + entry.size() > MAX_BYTES)) {
            bytes -= entries.removeFirst().size();
            dropped++;
        }
        entries.addLast(entry);
        bytes += entry.size();
    }

    List<Entry> snapshot() { return new ArrayList<>(entries); }
    long dropped() { return dropped; }
    void clear() { entries.clear(); bytes = 0; dropped = 0; }

    static final class Entry {
        final long time;
        final String label;
        private final byte[] raw;
        Entry(long time, String label, byte[] raw) { this.time = time; this.label = label; this.raw = raw; }
        int size() { return raw.length + label.length() * 2 + 64; }
        String hex() { return HexUtils.bytesToHex(raw); }
        String preview(boolean hex) {
            byte[] small = Arrays.copyOf(raw, Math.min(512, raw.length));
            return label + (raw.length == 0 ? "" : " (" + raw.length + " B)\n"
                    + (hex ? HexUtils.bytesToHex(small) : new String(small, StandardCharsets.UTF_8)))
                    + (raw.length > 512 ? " …" : "");
        }
    }
}
