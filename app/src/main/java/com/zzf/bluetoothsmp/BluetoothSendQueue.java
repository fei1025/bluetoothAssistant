package com.zzf.bluetoothsmp;

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final ConcurrentMap<Long, SendTask> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final int maxRequests;
    private final long maxPendingBytes;
    private long pendingBytes;

    public BluetoothSendQueue(Writer writer, Listener listener) {
        this(writer, listener, 128, 2L * 1024L * 1024L);
    }

    public BluetoothSendQueue(Writer writer, Listener listener, int maxRequests, long maxPendingBytes) {
        if (writer == null) {
            throw new IllegalArgumentException("writer must not be null");
        }
        this.writer = writer;
        this.listener = listener;
        if (maxRequests <= 0 || maxPendingBytes <= 0) throw new IllegalArgumentException("queue budgets must be positive");
        this.maxRequests = maxRequests;
        this.maxPendingBytes = maxPendingBytes;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxRequests), runnable -> {
            Thread thread = new Thread(runnable, "bt-send-queue");
            thread.setDaemon(true);
            return thread;
        });
    }

    public long enqueue(byte[] payload) {
        return enqueue(payload, null);
    }

    public long enqueue(byte[] payload, BluetoothSendBatch batch) {
        long requestId = BluetoothRequestIds.next();
        return enqueueInternal(requestId, payload, 0, 0L, batch);
    }

    public long enqueue(long requestId, byte[] payload, BluetoothSendBatch batch) {
        return enqueueInternal(requestId, payload, 0, 0L, batch);
    }

    public long enqueueSegmented(long requestId, byte[] payload, int segmentSize,
                                               long intervalMillis, BluetoothSendBatch batch) {
        if (segmentSize <= 0 || intervalMillis < 0) throw new IllegalArgumentException("invalid segmentation");
        return enqueueInternal(requestId, payload, segmentSize, intervalMillis, batch);
    }

    public long enqueueSegmented(byte[] payload, int segmentSize,
                                               long intervalMillis) {
        return enqueueSegmented(payload, segmentSize, intervalMillis, null);
    }

    public long enqueueSegmented(byte[] payload, int segmentSize,
                                               long intervalMillis, BluetoothSendBatch batch) {
        if (segmentSize <= 0) {
            throw new IllegalArgumentException("segmentSize must be positive");
        }
        if (intervalMillis < 0) {
            throw new IllegalArgumentException("intervalMillis must not be negative");
        }
        long requestId = BluetoothRequestIds.next();
        return enqueueInternal(requestId, payload, segmentSize, intervalMillis, batch);
    }

    private long enqueueInternal(long requestId, byte[] payload, int segmentSize,
                                 long intervalMillis, BluetoothSendBatch batch) {
        int bytes = payload == null ? 0 : payload.length;
        SendTask task = null;
        boolean canceled = false;
        Throwable rejection = null;
        synchronized (this) {
            if (closed.get() || (batch != null && batch.isCanceled())) canceled = true;
            else if (tasks.containsKey(requestId)) throw new IllegalArgumentException("duplicate request ID");
            // Check before cloning: rejected requests must not allocate another payload copy.
            else if (tasks.size() >= maxRequests || bytes > maxPendingBytes - pendingBytes) {
                rejection = new BluetoothQueueFullException("send request/byte budget exceeded");
            } else {
                task = new SendTask(requestId, payload == null ? new byte[0] : payload.clone(), segmentSize, intervalMillis, batch);
                tasks.put(requestId, task);
                pendingBytes += bytes;
                task.reserved = true;
                canceled = batch != null && !batch.register(task.cancelBatchRequest);
            }
        }
        // Observers can apply disk backpressure. They must never own the queue lock.
        if (task == null) {
            notifyStatus(requestId, canceled ? BluetoothSendStatus.CANCELED : BluetoothSendStatus.FAILED, rejection);
            return requestId;
        }
        if (canceled) { task.cancel(); return requestId; }
        task.publish(BluetoothSendStatus.QUEUED, null);
        synchronized (this) {
            if (task.status.get().isTerminal()) return requestId;
            if (closed.get() || task.isBatchCanceled()) canceled = true;
            else {
                try {
                    task.future = executor.submit(task);
                    if (task.status.get().isTerminal()) task.future.cancel(true);
                } catch (RejectedExecutionException rejected) { canceled = true; }
            }
        }
        if (canceled) task.cancel();
        return requestId;
    }

    public boolean cancel(long requestId) {
        SendTask task = tasks.get(requestId);
        if (task == null) {
            return false;
        }
        return task.cancel();
    }

    public int cancelSegmented() {
        int canceled = 0;
        for (SendTask task : tasks.values()) {
            if (task.segmentSize > 0 && !task.status.get().isTerminal()) {
                if (task.cancel()) canceled++;
            }
        }
        return canceled;
    }

    public int getPendingCount() {
        return tasks.size();
    }

    public synchronized long getPendingBytes() { return pendingBytes; }
    public synchronized boolean canAccept(int payloadBytes) {
        return !closed.get() && payloadBytes >= 0 && tasks.size() < maxRequests
                && payloadBytes <= maxPendingBytes - pendingBytes;
    }

    private void release(SendTask task) {
        synchronized (this) {
            if (tasks.remove(task.requestId, task) && task.reserved) {
                pendingBytes -= task.payload.length;
                task.reserved = false;
            }
        }
        task.unregisterBatch();
    }

    private void notifyStatus(long requestId, BluetoothSendStatus status, Throwable error) {
        if (listener != null) {
            try { listener.onStatus(requestId, status, tasks.size(), error); }
            catch (RuntimeException ignored) { }
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        List<SendTask> pending;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            pending = new ArrayList<>(tasks.values());
        }
        for (SendTask task : pending) task.cancel();
        executor.shutdownNow();
    }

    private final class SendTask implements Runnable {
        private final long requestId;
        private final byte[] payload;
        private final int segmentSize;
        private final long intervalMillis;
        private final BluetoothSendBatch batch;
        private final Runnable cancelBatchRequest;
        private final AtomicReference<BluetoothSendStatus> status =
                new AtomicReference<>(BluetoothSendStatus.QUEUED);
        private volatile Future<?> future;
        private final AtomicBoolean started = new AtomicBoolean();
        private boolean reserved;

        private SendTask(long requestId, byte[] payload, int segmentSize, long intervalMillis,
                         BluetoothSendBatch batch) {
            this.requestId = requestId;
            this.payload = payload;
            this.segmentSize = segmentSize;
            this.intervalMillis = intervalMillis;
            this.batch = batch;
            this.cancelBatchRequest = () -> BluetoothSendQueue.this.cancel(requestId);
        }

        @Override
        public void run() {
            started.set(true);
            if (isBatchCanceled()) {
                cancel();
                release(this);
                return;
            }
            if (!status.compareAndSet(BluetoothSendStatus.QUEUED,
                    BluetoothSendStatus.SENDING)) {
                release(this);
                return;
            }
            publish(BluetoothSendStatus.SENDING, null);
            try {
                if (cancelBeforeWrite()) {
                    return;
                }
                if (segmentSize <= 0 || segmentSize >= payload.length) {
                    writer.write(payload);
                    if (segmentSize > 0) {
                        publishProgress(payload.length, payload.length);
                    }
                } else {
                    int sentBytes = 0;
                    while (sentBytes < payload.length) {
                        if (cancelBeforeWrite()) {
                            return;
                        }
                        int end = Math.min(payload.length, sentBytes + segmentSize);
                        writer.write(Arrays.copyOfRange(payload, sentBytes, end));
                        if (cancelBeforeWrite()) {
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
                release(this);
            }
        }

        private boolean cancel() {
            BluetoothSendStatus current = status.get();
            while (current == BluetoothSendStatus.QUEUED
                    || current == BluetoothSendStatus.SENDING) {
                if (status.compareAndSet(current, BluetoothSendStatus.CANCELED)) {
                    publish(BluetoothSendStatus.CANCELED, null);
                    unregisterBatch();
                    if (future != null) {
                        future.cancel(true);
                        if (future instanceof Runnable) ((ThreadPoolExecutor) executor).remove((Runnable) future);
                    }
                    if (!started.get()) release(this);
                    return true;
                }
                current = status.get();
            }
            if (!started.get()) release(this);
            return false;
        }

        private boolean isBatchCanceled() {
            return batch != null && batch.isCanceled();
        }

        private boolean cancelBeforeWrite() {
            if (isBatchCanceled()) {
                cancel();
            }
            return status.get() != BluetoothSendStatus.SENDING;
        }

        private void unregisterBatch() {
            if (batch != null) {
                batch.unregister(cancelBatchRequest);
            }
        }

        private void publish(BluetoothSendStatus newStatus, Throwable error) {
            if (newStatus == BluetoothSendStatus.QUEUED && status.get() != BluetoothSendStatus.QUEUED) return;
            notifyStatus(requestId, newStatus, error);
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
