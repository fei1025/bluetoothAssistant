package com.zzf.bluetoothsmp.plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

/** Awaiting bridge delivery counts against both limits; no silent eviction of protocol bytes. */
public final class PluginEventBuffer {
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private int bytes;
    public synchronized boolean offer(String event) {
        int size = event.getBytes(StandardCharsets.UTF_8).length;
        if (events.size() >= 256 || bytes + size > 1024 * 1024) return false;
        events.add(event); bytes += size; return true;
    }
    public synchronized List<String> drain(int count) {
        List<String> batch = new ArrayList<>();
        while (!events.isEmpty() && batch.size() < count) {
            String event = events.remove(); bytes -= event.getBytes(StandardCharsets.UTF_8).length; batch.add(event);
        }
        return batch;
    }
    public synchronized List<String> peek(int count) {
        List<String> result = new ArrayList<>();
        for (String event : events) { if (result.size() == count) break; result.add(event); }
        return result;
    }
    public synchronized void clear() { events.clear(); bytes = 0; }
    public synchronized int count() { return events.size(); }
}
