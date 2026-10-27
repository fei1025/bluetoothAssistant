package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zzf.bluetoothsmp.entity.Msg;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class BluetoothSendBatchTest {
    @Test
    public void stopCancelsOrdinaryAndSegmentedBacklogWithoutCancelingOtherSenders() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch sent = new CountDownLatch(3);
        List<Integer> writes = Collections.synchronizedList(new ArrayList<>());
        BluetoothSendBatch stopped = new BluetoothSendBatch();
        BluetoothSendBatch restarted = new BluetoothSendBatch();
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> {
            if (payload[0] == 9) {
                firstStarted.countDown();
                awaitUninterruptibly(release);
            }
            writes.add((int) payload[0]);
        }, (id, status, pending, error) -> {
            if (status == BluetoothSendStatus.SENT) sent.countDown();
        });
        try {
            queue.enqueue(new byte[]{9});
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            queue.enqueue(new byte[]{1}, stopped);
            queue.enqueueSegmented(new byte[]{2, 2, 2, 2}, 2, 0, stopped);
            queue.enqueue(new byte[]{3}); // Unrelated chat send.
            queue.enqueue(new byte[]{4}, restarted);
            stopped.cancel();
            stopped.cancel(); // Idempotent, including when a new batch exists.
            assertTrue(stopped.isCanceled());
            assertFalse(restarted.isCanceled());
            assertEquals(3, queue.getPendingCount());
            release.countDown();
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(9, 3, 4), writes);
        } finally {
            release.countDown();
            queue.close();
        }
    }

    @Test
    public void canceledMessagesArrivingFromCentralQueueCannotBeSent() throws Exception {
        BluetoothSendBatch batch = new BluetoothSendBatch();
        LinkedBlockingQueue<Msg> centralQueue = new LinkedBlockingQueue<>();
        Msg delayed = new Msg(new byte[]{1}, Msg.TYPE_SENT, "AA:BB:CC:DD:EE:FF");
        delayed.setSendBatch(batch);
        centralQueue.put(delayed);
        // Model delivery that passed its first check immediately before Stop.
        Msg delivered = centralQueue.take();
        assertFalse(delivered.isSendCanceled());
        batch.cancel();
        AtomicInteger writes = new AtomicInteger();
        List<BluetoothSendStatus> statuses = new ArrayList<>();
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> writes.incrementAndGet(),
                (id, status, pending, error) -> statuses.add(status));
        try {
            assertTrue(delivered.isSendCanceled());
            queue.enqueue(delivered.getPayload(), delivered.getSendBatch());
            queue.enqueueSegmented(new byte[]{2, 3}, 1, 0, delivered.getSendBatch());
            assertEquals(0, writes.get());
            assertEquals(0, queue.getPendingCount());
            assertEquals(Arrays.asList(BluetoothSendStatus.CANCELED,
                    BluetoothSendStatus.CANCELED), statuses);
        } finally {
            queue.close();
        }
    }

    @Test
    public void cancellationRacingWithEnqueueRegistrationDoesNotLosePendingRequest() throws Exception {
        BluetoothSendBatch batch = new BluetoothSendBatch();
        CountDownLatch queued = new CountDownLatch(1);
        CountDownLatch allowSubmit = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> writes.incrementAndGet(),
                (id, status, pending, error) -> {
                    if (status == BluetoothSendStatus.QUEUED) {
                        queued.countDown();
                        awaitUninterruptibly(allowSubmit);
                    }
                });
        Thread delivery = new Thread(() -> {
            try { queue.enqueue(new byte[]{1}, batch); }
            catch (Throwable error) { failure.set(error); }
        });
        Thread stopper = new Thread(batch::cancel);
        try {
            delivery.start();
            assertTrue(queued.await(2, TimeUnit.SECONDS));
            stopper.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!batch.isCanceled() && System.nanoTime() < deadline) Thread.yield();
            assertTrue(batch.isCanceled());
            allowSubmit.countDown();
            delivery.join(2000);
            stopper.join(2000);
            assertFalse(delivery.isAlive());
            assertFalse(stopper.isAlive());
            assertEquals(null, failure.get());
            assertEquals(0, queue.getPendingCount());
            assertEquals(0, writes.get());
        } finally {
            allowSubmit.countDown();
            queue.close();
        }
    }

    @Test
    public void cancellationDuringSendingCallbackPreventsFirstWrite() throws Exception {
        BluetoothSendBatch batch = new BluetoothSendBatch();
        CountDownLatch canceled = new CountDownLatch(1);
        CountDownLatch unrelatedSent = new CountDownLatch(1);
        List<Integer> writes = Collections.synchronizedList(new ArrayList<>());
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> writes.add((int) payload[0]),
                (id, status, pending, error) -> {
                    if (id == 1 && status == BluetoothSendStatus.SENDING) batch.cancel();
                    if (id == 1 && status == BluetoothSendStatus.CANCELED) canceled.countDown();
                    if (id == 2 && status == BluetoothSendStatus.SENT) unrelatedSent.countDown();
                });
        try {
            queue.enqueue(1L, new byte[]{1}, batch);
            queue.enqueue(2L, new byte[]{9}, null);
            assertTrue(canceled.await(2, TimeUnit.SECONDS));
            assertTrue(unrelatedSent.await(2, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList(9), writes);
        } finally {
            queue.close();
        }
    }

    @Test
    public void cancelActiveBatchPreventsLaterChunksAndPreservesConnectionForChat() throws Exception {
        BluetoothSendBatch batch = new BluetoothSendBatch();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch chatSent = new CountDownLatch(1);
        List<Integer> writes = Collections.synchronizedList(new ArrayList<>());
        BluetoothSendQueue queue = new BluetoothSendQueue(payload -> {
            writes.add((int) payload[0]);
            if (payload[0] == 1) {
                firstStarted.countDown();
                awaitUninterruptibly(release);
            }
        }, (id, status, pending, error) -> {
            if (id == 3 && status == BluetoothSendStatus.SENT) chatSent.countDown();
        });
        try {
            queue.enqueueSegmented(1L, new byte[]{1, 1, 2, 2, 3, 3}, 2, 0, batch);
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            queue.enqueue(2L, new byte[]{4}, batch);
            queue.enqueue(3L, new byte[]{9}, null);
            batch.cancel();
            release.countDown();
            assertTrue(chatSent.await(2, TimeUnit.SECONDS));
            // The already-started chunk cannot be recalled; every later batch write is skipped.
            assertEquals(Arrays.asList(1, 9), writes);
        } finally {
            release.countDown();
            queue.close();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
