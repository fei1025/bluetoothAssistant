package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public class BluetoothSendQueueTest {

    @Test
    public void slowQueuedObserverDoesNotHoldTheCancellationLock() throws Exception {
        CountDownLatch observerEntered = new CountDownLatch(1), observerRelease = new CountDownLatch(1);
        AtomicLong request = new AtomicLong();
        AtomicInteger writes = new AtomicInteger();
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> writes.incrementAndGet(),
                (id, status, pending, error) -> {
                    if (status == BluetoothSendStatus.QUEUED) {
                        request.set(id);
                        observerEntered.countDown();
                        try { observerRelease.await(2, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                });
        Thread producer = new Thread(() -> queue.enqueue(new byte[]{1}));
        Thread stopper = new Thread(() -> queue.cancel(request.get()));
        try {
            producer.start();
            assertTrue(observerEntered.await(1, TimeUnit.SECONDS));
            stopper.start();
            stopper.join(500);
            assertFalse("Stop cannot wait for a disk observer", stopper.isAlive());
            assertEquals(0L, queue.getPendingBytes());
            assertEquals(0, writes.get());
        } finally {
            observerRelease.countDown();
            producer.join(1000);
            stopper.join(1000);
            queue.close();
        }
    }

    @Test
    public void rejectsCountAndByteOverloadWithoutWritingRejectedRequests() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger(), rejections = new AtomicInteger();
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> {
            writes.incrementAndGet();
            started.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, (id, status, pending, error) -> {
            if (status == BluetoothSendStatus.FAILED && error instanceof BluetoothQueueFullException) rejections.incrementAndGet();
        }, 2, 4);
        try {
            queue.enqueue(new byte[]{1, 2, 3, 4});
            assertTrue(started.await(1, TimeUnit.SECONDS));
            queue.enqueue(new byte[]{5});
            assertEquals(1, rejections.get());
            assertEquals(4L, queue.getPendingBytes());
            assertFalse(queue.isClosed());
            assertEquals(1, writes.get());
        } finally { release.countDown(); queue.close(); }

        CountDownLatch countStarted = new CountDownLatch(1), countRelease = new CountDownLatch(1);
        BluetoothSendQueue countQueue = new BluetoothSendQueue(payload -> {
            countStarted.countDown();
            try { countRelease.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, (id, status, pending, error) -> {
            if (status == BluetoothSendStatus.FAILED && error instanceof BluetoothQueueFullException) rejections.incrementAndGet();
        }, 1, 100);
        try {
            countQueue.enqueue(new byte[]{1});
            assertTrue(countStarted.await(1, TimeUnit.SECONDS));
            countQueue.enqueue(new byte[]{2});
            assertEquals(2, rejections.get());
        } finally { countRelease.countDown(); countQueue.close(); }
    }

    @Test
    public void cancelingQueuedRequestReleasesItsByteReservation() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> {
            started.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, null, 3, 8);
        try {
            queue.enqueue(new byte[4]);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            long queued = queue.enqueue(new byte[4]);
            assertEquals(8L, queue.getPendingBytes());
            assertTrue(queue.cancel(queued));
            assertEquals(4L, queue.getPendingBytes());
            queue.enqueue(new byte[4]);
            assertEquals(8L, queue.getPendingBytes());
        } finally { release.countDown(); queue.close(); }
    }

    @Test
    public void canceledBlockedWriteRetainsBudgetUntilWriterActuallyReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> {
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (release.getCount() > 0 && System.nanoTime() < deadline) {
                try { release.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* Simulates a socket write that ignores interrupt. */ }
            }
        }, (id, status, pending, error) -> {
            if (error instanceof BluetoothQueueFullException) rejected.incrementAndGet();
        }, 2, 4);
        try {
            long active = queue.enqueue(new byte[4]);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(queue.cancel(active));
            assertEquals(4L, queue.getPendingBytes());
            queue.enqueue(new byte[4]);
            assertEquals(1, rejected.get());
        } finally { release.countDown(); queue.close(); }
    }

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
