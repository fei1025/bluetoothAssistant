package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.entity.Msg;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** A bounded message bus with a separate reserved lane for disconnect/control events. */
public final class BluetoothMessageQueue extends AbstractQueue<Msg> implements BlockingQueue<Msg> {
    private static final class Entry {
        final Msg message;
        final long bytes;
        Entry(Msg message) { this.message = message; bytes = message.estimatedQueueBytes(); }
    }
    private final ArrayDeque<Entry> data = new ArrayDeque<>();
    private final ArrayDeque<Entry> control = new ArrayDeque<>();
    private final int maxCount, maxControlCount;
    private final long maxBytes, maxControlBytes;
    private long dataBytes, controlBytes;

    public BluetoothMessageQueue(int maxCount, long maxBytes, int maxControlCount, long maxControlBytes) {
        if (maxCount <= 0 || maxBytes <= 0 || maxControlCount <= 0 || maxControlBytes <= 0) {
            throw new IllegalArgumentException("queue budgets must be positive");
        }
        this.maxCount = maxCount; this.maxBytes = maxBytes;
        this.maxControlCount = maxControlCount; this.maxControlBytes = maxControlBytes;
    }
    private boolean isControl(Msg message) { return message.getStateType() != 0; }
    private boolean fits(Entry entry) {
        return isControl(entry.message)
                ? control.size() < maxControlCount && entry.bytes <= maxControlBytes - controlBytes
                : data.size() < maxCount && entry.bytes <= maxBytes - dataBytes;
    }
    private boolean oversized(Entry entry) {
        return entry.bytes > (isControl(entry.message) ? maxControlBytes : maxBytes);
    }
    private void insert(Entry entry) {
        if (isControl(entry.message)) { control.addLast(entry); controlBytes += entry.bytes; }
        else { data.addLast(entry); dataBytes += entry.bytes; }
        notifyAll();
    }
    @Override public synchronized boolean offer(Msg message) {
        if (message == null) throw new NullPointerException("message");
        Entry entry = new Entry(message);
        if (!fits(entry)) return false;
        insert(entry); return true;
    }
    @Override public synchronized void put(Msg message) throws InterruptedException {
        if (message == null) throw new NullPointerException("message");
        Entry entry = new Entry(message);
        if (oversized(entry)) throw new IllegalArgumentException("MESSAGE_EXCEEDS_QUEUE_BYTE_BUDGET");
        while (!fits(entry)) wait();
        insert(entry);
    }
    @Override public synchronized boolean offer(Msg message, long timeout, TimeUnit unit) throws InterruptedException {
        if (message == null) throw new NullPointerException("message");
        Entry entry = new Entry(message);
        if (oversized(entry)) return false;
        long remaining = unit.toNanos(timeout), deadline = System.nanoTime() + remaining;
        while (!fits(entry)) {
            if (remaining <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
        insert(entry); return true;
    }
    @Override public synchronized Msg poll() {
        Entry entry = control.pollFirst();
        if (entry != null) controlBytes -= entry.bytes;
        else { entry = data.pollFirst(); if (entry != null) dataBytes -= entry.bytes; }
        if (entry == null) return null;
        notifyAll(); return entry.message;
    }
    @Override public synchronized Msg take() throws InterruptedException {
        while (size() == 0) wait();
        return poll();
    }
    public synchronized Msg takeData() throws InterruptedException {
        while (data.isEmpty()) wait();
        Entry entry = data.removeFirst(); dataBytes -= entry.bytes; notifyAll(); return entry.message;
    }
    public synchronized Msg takeControl() throws InterruptedException {
        while (control.isEmpty()) wait();
        Entry entry = control.removeFirst(); controlBytes -= entry.bytes; notifyAll(); return entry.message;
    }
    @Override public synchronized Msg poll(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout), deadline = System.nanoTime() + remaining;
        while (size() == 0) {
            if (remaining <= 0) return null;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
        return poll();
    }
    @Override public synchronized Msg peek() {
        Entry entry = control.peekFirst();
        if (entry == null) entry = data.peekFirst();
        return entry == null ? null : entry.message;
    }
    @Override public synchronized int size() { return data.size() + control.size(); }
    public synchronized long getPendingBytes() { return dataBytes + controlBytes; }
    @Override public synchronized int remainingCapacity() { return Math.max(0, maxCount - data.size()); }
    @Override public synchronized void clear() {
        data.clear(); control.clear(); dataBytes = controlBytes = 0L; notifyAll();
    }
    @Override public synchronized boolean remove(Object value) {
        for (ArrayDeque<Entry> lane : java.util.Arrays.asList(control, data)) {
            Iterator<Entry> iterator = lane.iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (entry.message == value) {
                    iterator.remove();
                    if (lane == control) controlBytes -= entry.bytes; else dataBytes -= entry.bytes;
                    notifyAll(); return true;
                }
            }
        }
        return false;
    }
    @Override public synchronized Iterator<Msg> iterator() {
        ArrayList<Msg> snapshot = new ArrayList<>();
        for (Entry entry : control) snapshot.add(entry.message);
        for (Entry entry : data) snapshot.add(entry.message);
        Iterator<Msg> delegate = snapshot.iterator();
        return new Iterator<Msg>() {
            Msg last;
            @Override public boolean hasNext() { return delegate.hasNext(); }
            @Override public Msg next() { if (!delegate.hasNext()) throw new NoSuchElementException(); return last = delegate.next(); }
            @Override public void remove() {
                if (last == null) throw new IllegalStateException();
                BluetoothMessageQueue.this.remove(last); last = null;
            }
        };
    }
    @Override public int drainTo(Collection<? super Msg> target) { return drainTo(target, Integer.MAX_VALUE); }
    @Override public synchronized int drainTo(Collection<? super Msg> target, int limit) {
        if (target == this) throw new IllegalArgumentException("cannot drain into this queue");
        if (target == null) throw new NullPointerException("target");
        int count = 0;
        while (count < limit && size() > 0) { target.add(peek()); poll(); count++; }
        return count;
    }
}
