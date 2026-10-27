package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.entity.LogItem;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.utils.HexUtils;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/** Bounded background previews and a bounded full-byte export cache. No UI/transport IO. */
public final class DebugLogPipeline implements AutoCloseable {
    public static final int PREVIEW_BYTES = 512;
    public static final int PREVIEW_CHARS = 4096;
    private static final int MAX_PENDING = 128;
    private static final long MAX_PENDING_BYTES = 4L * 1024 * 1024;
    private final Object lock = new Object();
    private final ArrayDeque<Work> pending = new ArrayDeque<>();
    private final DebugLogStore logs = new DebugLogStore(500, 4L * 1024 * 1024);
    private final String truncated;
    private final Thread worker;
    private long pendingBytes;
    private long rejected;
    private long generation;
    private long revision;
    private int active;
    private boolean closed;

    public DebugLogPipeline(String truncated) {
        this.truncated = truncated;
        worker = new Thread(this::run, "debug-log-preview");
        worker.setDaemon(true); worker.start();
    }

    public boolean offer(Msg message, boolean sent) {
        long bytes = message.estimatedQueueBytes();
        synchronized (lock) {
            if (closed || pending.size() + active >= MAX_PENDING || bytes > MAX_PENDING_BYTES - pendingBytes) {
                rejected++; return false;
            }
            pending.addLast(new Work(message, sent, bytes, generation));
            pendingBytes += bytes; lock.notifyAll(); return true;
        }
    }

    private void run() {
        while (true) {
            Work work;
            synchronized (lock) {
                while (!closed && pending.isEmpty()) {
                    try { lock.wait(); } catch (InterruptedException stopped) { return; }
                }
                if (closed) return;
                work = pending.removeFirst();
                active++;
                // Include the currently converting item in the admission budget.
            }
            try {
                Msg message = work.message;
                synchronized (lock) {
                    if (work.generation != generation) continue;
                    if (logs.updateStatus(message.getMessageId(), message.getSessionId(),
                            status(message), message.getSendStatusVersion())) { revision++; continue; }
                }
                byte[] raw = message.getPayloadOrUtf8();
                byte[] prefix = Arrays.copyOf(raw, Math.min(PREVIEW_BYTES, raw.length));
                String text = message.getContent();
                if (text == null) text = BluetoothEncodingUtils.decode(prefix, message.getTextEncoding());
                if (text.endsWith("\r\n")) text = text.substring(0, text.length() - 2);
                String content = text.length() > PREVIEW_CHARS
                        ? text.substring(0, Math.max(0, PREVIEW_CHARS - truncated.length())) + truncated : text;
                LogItem item = new LogItem(content, raw, work.sent ? LogItem.TYPE_SENT : LogItem.TYPE_RECEIVED,
                        message.getBluetoothAdd());
                item.setMessageId(message.getMessageId()); item.setSessionId(message.getSessionId());
                item.setSendStatus(status(message)); item.setSendStatusVersion(message.getSendStatusVersion());
                item.setTextEncoding(message.getTextEncoding());
                item.setTimestamp(message.getSenTime() == null ? new Date() : new Date(message.getSenTime().getTime()));
                String suffix = raw.length > PREVIEW_BYTES ? truncated : "";
                item.setHexContent(HexUtils.bytesToHex(prefix) + suffix);
                item.setAsciiContent(HexUtils.bytesToAscii(prefix) + suffix);
                synchronized (lock) {
                    if (work.generation == generation) {
                        if (!logs.add(item)) rejected++;
                        revision++;
                    }
                }
            } catch (RuntimeException malformedPreview) {
                synchronized (lock) { rejected++; }
            } finally {
                synchronized (lock) { pendingBytes -= work.bytes; active--; }
            }
        }
    }

    private static String status(Msg message) {
        return message.getSendStatus() == null ? null : message.getSendStatus().name();
    }

    public List<LogItem> snapshot() { synchronized (lock) { return logs.snapshot(); } }
    public long revision() { synchronized (lock) { return revision; } }
    public int pendingCount() { synchronized (lock) { return pending.size() + active; } }
    public long pendingBytes() { synchronized (lock) { return pendingBytes; } }
    public long rejectedCount() { synchronized (lock) { return rejected; } }
    public void clear() {
        synchronized (lock) {
            generation++;
            for (Work work : pending) pendingBytes -= work.bytes;
            pending.clear(); logs.clear(); revision++;
        }
    }

    @Override public void close() {
        synchronized (lock) { closed = true; pending.clear(); lock.notifyAll(); }
        worker.interrupt();
    }

    private static final class Work {
        final Msg message; final boolean sent; final long bytes; final long generation;
        Work(Msg message, boolean sent, long bytes, long generation) {
            this.message = message; this.sent = sent; this.bytes = bytes; this.generation = generation;
        }
    }
}
