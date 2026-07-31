package com.zzf.bluetoothsmp;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One serial writer for one Bluetooth connection. The queue owns ordering and
 * lifecycle; the caller only supplies the actual stream write operation.
 */
public final class BluetoothSendQueue implements AutoCloseable {

    public interface Writer {
        void write(byte[] payload) throws IOException;
    }

    public interface Listener {
        void onStatus(long requestId, BluetoothSendStatus status, int pendingCount,
                      Throwable error);

        default void onProgress(long requestId, int sentBytes, int totalBytes) {
        }
    }

    private final ExecutorService executor;
    private final Writer writer;
    private final Listener listener;
    private final AtomicLong nextRequestId = new AtomicLong(0L);
    private final ConcurrentMap<Long, SendTask> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public BluetoothSendQueue(Writer writer, Listener listener) {
        if (writer == null) {
            throw new IllegalArgumentException("writer must not be null");
        }
        this.writer = writer;
        this.listener = listener;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bt-send-queue");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized long enqueue(byte[] payload) {
        long requestId = nextRequestId.incrementAndGet();
        return enqueueInternal(requestId, payload, 0, 0L);
    }

    public synchronized long enqueueSegmented(byte[] payload, int segmentSize,
                                               long intervalMillis) {
        if (segmentSize <= 0) {
            throw new IllegalArgumentException("segmentSize must be positive");
        }
        if (intervalMillis < 0) {
            throw new IllegalArgumentException("intervalMillis must not be negative");
        }
        long requestId = nextRequestId.incrementAndGet();
        return enqueueInternal(requestId, payload, segmentSize, intervalMillis);
    }

    private long enqueueInternal(long requestId, byte[] payload, int segmentSize,
                                 long intervalMillis) {
        SendTask task = new SendTask(requestId,
                payload == null ? new byte[0] : payload.clone(), segmentSize, intervalMillis);
        if (closed.get()) {
            task.cancel();
            return requestId;
        }
        tasks.put(requestId, task);
        task.publish(BluetoothSendStatus.QUEUED, null);
        task.future = executor.submit(task);
        return requestId;
    }

    public synchronized boolean cancel(long requestId) {
        SendTask task = tasks.get(requestId);
        if (task == null) {
            return false;
        }
        task.cancel();
        return true;
    }

    public synchronized int cancelSegmented() {
        int canceled = 0;
        for (SendTask task : tasks.values()) {
            if (task.segmentSize > 0) {
                task.cancel();
                canceled++;
            }
        }
        return canceled;
    }

    public int getPendingCount() {
        return tasks.size();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (SendTask task : tasks.values()) {
            task.cancel();
        }
        executor.shutdownNow();
    }

    private final class SendTask implements Runnable {
        private final long requestId;
        private final byte[] payload;
        private final int segmentSize;
        private final long intervalMillis;
        private final AtomicReference<BluetoothSendStatus> status =
                new AtomicReference<>(BluetoothSendStatus.QUEUED);
        private volatile Future<?> future;

        private SendTask(long requestId, byte[] payload, int segmentSize, long intervalMillis) {
            this.requestId = requestId;
            this.payload = payload;
            this.segmentSize = segmentSize;
            this.intervalMillis = intervalMillis;
        }

        @Override
        public void run() {
            if (!status.compareAndSet(BluetoothSendStatus.QUEUED,
                    BluetoothSendStatus.SENDING)) {
                tasks.remove(requestId, this);
                return;
            }
            publish(BluetoothSendStatus.SENDING, null);
            try {
                if (segmentSize <= 0 || segmentSize >= payload.length) {
                    writer.write(payload);
                    if (segmentSize > 0) {
                        publishProgress(payload.length, payload.length);
                    }
                } else {
                    int sentBytes = 0;
                    while (sentBytes < payload.length) {
                        if (status.get() != BluetoothSendStatus.SENDING) {
                            return;
                        }
                        int end = Math.min(payload.length, sentBytes + segmentSize);
                        writer.write(Arrays.copyOfRange(payload, sentBytes, end));
                        if (status.get() != BluetoothSendStatus.SENDING) {
                            return;
                        }
                        sentBytes = end;
                        publishProgress(sentBytes, payload.length);
                        if (sentBytes < payload.length && intervalMillis > 0) {
                            Thread.sleep(intervalMillis);
                        }
                    }
                }
                if (status.compareAndSet(BluetoothSendStatus.SENDING,
                        BluetoothSendStatus.SENT)) {
                    publish(BluetoothSendStatus.SENT, null);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (status.compareAndSet(BluetoothSendStatus.SENDING,
                        BluetoothSendStatus.CANCELED)) {
                    publish(BluetoothSendStatus.CANCELED, exception);
                }
            } catch (IOException | RuntimeException exception) {
                if (status.compareAndSet(BluetoothSendStatus.SENDING,
                        BluetoothSendStatus.FAILED)) {
                    publish(BluetoothSendStatus.FAILED, exception);
                }
            } finally {
                tasks.remove(requestId, this);
            }
        }

        private void cancel() {
            BluetoothSendStatus current = status.get();
            while (current == BluetoothSendStatus.QUEUED
                    || current == BluetoothSendStatus.SENDING) {
                if (status.compareAndSet(current, BluetoothSendStatus.CANCELED)) {
                    publish(BluetoothSendStatus.CANCELED, null);
                    tasks.remove(requestId, this);
                    if (future != null) {
                        future.cancel(true);
                    }
                    return;
                }
                current = status.get();
            }
            tasks.remove(requestId, this);
        }

        private void publish(BluetoothSendStatus newStatus, Throwable error) {
            if (listener == null) {
                return;
            }
            try {
                listener.onStatus(requestId, newStatus, tasks.size(), error);
            } catch (RuntimeException ignored) {
                // A status observer must not break the serial writer.
            }
        }

        private void publishProgress(int sentBytes, int totalBytes) {
            if (listener == null) {
                return;
            }
            try {
                listener.onProgress(requestId, sentBytes, totalBytes);
            } catch (RuntimeException ignored) {
                // A progress observer must not break the serial writer.
            }
        }
    }
}
