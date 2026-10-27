package com.zzf.bluetoothsmp;
import com.zzf.bluetoothsmp.entity.LogItem;
import java.util.ArrayList;
import java.util.List;

/** Byte- and count-bounded display cache. History persistence is independent. */
public final class DebugLogStore {
    private final int maxItems;
    private final long maxBytes;
    private final List<LogItem> items = new ArrayList<>();
    private long bytes;
    public DebugLogStore(int maxItems, long maxBytes) {
        if (maxItems < 1 || maxBytes < 1) throw new IllegalArgumentException("Invalid debug log budget");
        this.maxItems = maxItems; this.maxBytes = maxBytes;
    }
    public synchronized boolean add(LogItem item) {
        if (item == null) return false;
        if (updateStatus(item.getMessageId(), item.getSessionId(), item.getSendStatus(), item.getSendStatusVersion())) return true;
        long size = item.estimatedBytes();
        if (size > maxBytes) return false;
        while (!items.isEmpty() && (items.size() >= maxItems || bytes + size > maxBytes)) {
            bytes -= items.remove(items.size() - 1).estimatedBytes();
        }
        items.add(0, item); bytes += size; return true;
    }
    public synchronized boolean updateStatus(String id, String session, String status, long version) {
        if (id == null) return false;
        for (LogItem item : items) {
            if (id.equals(item.getMessageId()) && java.util.Objects.equals(session, item.getSessionId())) {
                if (version >= item.getSendStatusVersion()) {
                    item.setSendStatus(status); item.setSendStatusVersion(version);
                }
                return true;
            }
        }
        return false;
    }
    public synchronized int size() { return items.size(); }
    public synchronized LogItem get(int index) { return items.get(index); }
    public synchronized long getBytes() { return bytes; }
    public synchronized List<LogItem> snapshot() { return new ArrayList<>(items); }
    public synchronized void clear() { items.clear(); bytes = 0; }
}
