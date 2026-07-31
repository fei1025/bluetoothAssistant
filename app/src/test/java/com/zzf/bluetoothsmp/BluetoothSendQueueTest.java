package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class BluetoothSendQueueTest {

    @Test
    public void writesInOrderAndReportsSuccessfulLifecycle() throws Exception {
        List<Integer> writes = Collections.synchronizedList(new ArrayList<>());
        List<BluetoothSendStatus> statuses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch sent = new CountDownLatch(2);
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> {
            writes.add((int) payload[0]);
        }, (id, status, pending, error) -> {
            statuses.add(status);
            if (status == BluetoothSendStatus.SENT) {
                sent.countDown();
            }
        });

        queue.enqueue(new byte[]{1});
        queue.enqueue(new byte[]{2});

        assertTrue(sent.await(2, TimeUnit.SECONDS));
        queue.close();
        assertEquals(Arrays.asList(1, 2), writes);
        assertEquals(2, Collections.frequency(statuses, BluetoothSendStatus.QUEUED));
        assertEquals(2, Collections.frequency(statuses, BluetoothSendStatus.SENDING));
        assertEquals(2, Collections.frequency(statuses, BluetoothSendStatus.SENT));
    }

    @Test
    public void reportsFailureWithoutStoppingQueue() throws Exception {
        List<BluetoothSendStatus> statuses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch failed = new CountDownLatch(1);
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> {
            throw new IOException("test write failure");
        }, (id, status, pending, error) -> {
            statuses.add(status);
            if (status == BluetoothSendStatus.FAILED) {
                failed.countDown();
            }
        });

        queue.enqueue(new byte[]{1});

        assertTrue(failed.await(2, TimeUnit.SECONDS));
        queue.close();
        assertTrue(statuses.contains(BluetoothSendStatus.FAILED));
    }

    @Test
    public void enqueueAfterCloseIsCanceled() {
        List<BluetoothSendStatus> statuses = Collections.synchronizedList(new ArrayList<>());
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> {
        }, (id, status, pending, error) -> statuses.add(status));
        queue.close();

        queue.enqueue(new byte[]{1});

        assertEquals(Collections.singletonList(BluetoothSendStatus.CANCELED), statuses);
    }

    @Test
    public void segmentedSendPreservesOrderAndReportsProgress() throws Exception {
        List<byte[]> writes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch sent = new CountDownLatch(1);
        List<Integer> progress = Collections.synchronizedList(new ArrayList<>());
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> writes.add(payload),
                new BluetoothSendQueue.Listener() {
                    @Override
                    public void onStatus(long id, BluetoothSendStatus status, int pending,
                                          Throwable error) {
                    if (status == BluetoothSendStatus.SENT) {
                        sent.countDown();
                    }
                    }

                    @Override
                    public void onProgress(long id, int sentBytes, int totalBytes) {
                        progress.add(sentBytes);
                    }
                });

        queue.enqueueSegmented(new byte[]{1, 2, 3, 4, 5}, 2, 0);

        assertTrue(sent.await(2, TimeUnit.SECONDS));
        queue.close();
        assertEquals(3, writes.size());
        assertArrayEquals(new byte[]{1, 2}, writes.get(0));
        assertArrayEquals(new byte[]{3, 4}, writes.get(1));
        assertArrayEquals(new byte[]{5}, writes.get(2));
        assertEquals(Arrays.asList(2, 4, 5), progress);
    }

    @Test
    public void cancelSegmentedDoesNotCancelOrdinaryQueuedWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ordinaryWritten = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        List<byte[]> writes = Collections.synchronizedList(new ArrayList<>());
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> {
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            writes.add(payload);
            ordinaryWritten.countDown();
        }, (id, status, pending, error) -> {
            if (status == BluetoothSendStatus.CANCELED) {
                canceled.countDown();
            }
        });

        queue.enqueue(new byte[]{9});
        queue.enqueueSegmented(new byte[]{1, 2, 3, 4}, 2, 0);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(1, queue.cancelSegmented());
        release.countDown();

        assertTrue(canceled.await(2, TimeUnit.SECONDS));
        assertTrue(ordinaryWritten.await(2, TimeUnit.SECONDS));
        queue.close();
        assertEquals(1, writes.size());
        assertArrayEquals(new byte[]{9}, writes.get(0));
    }

    @Test
    public void cancelActiveSegmentedSendDoesNotWriteLaterSegments() throws Exception {
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        AtomicInteger writeCount = new AtomicInteger();
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> {
            if (writeCount.incrementAndGet() == 1) {
                firstWriteStarted.countDown();
                try {
                    releaseFirstWrite.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        }, (id, status, pending, error) -> {
            if (status == BluetoothSendStatus.CANCELED) {
                canceled.countDown();
            }
        });

        queue.enqueueSegmented(new byte[]{1, 2, 3, 4}, 2, 0);

        assertTrue(firstWriteStarted.await(2, TimeUnit.SECONDS));
        assertEquals(1, queue.cancelSegmented());
        releaseFirstWrite.countDown();

        assertTrue(canceled.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (queue.getPendingCount() != 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        queue.close();
        assertEquals(1, writeCount.get());
    }
}
