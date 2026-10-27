package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.entity.Msg;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class BluetoothMessageQueueTest {
    @Test public void fullDataLaneStillAcceptsAndPrioritizesDisconnect() {
        BluetoothMessageQueue queue = new BluetoothMessageQueue(1, 1024, 1, 1024);
        Msg data = new Msg("payload", Msg.TYPE_RECEIVED, "AA");
        Msg disconnect = new Msg("AA");
        disconnect.setStateType(1);
        assertTrue(queue.offer(data));
        assertFalse(queue.offer(new Msg("next", Msg.TYPE_RECEIVED, "AA")));
        assertTrue(queue.offer(disconnect));
        assertSame(disconnect, queue.poll());
        assertSame(data, queue.poll());
        assertEquals(0L, queue.getPendingBytes());
    }

    @Test public void byteBudgetAndIteratorRemovalAreAccountedIndependentlyFromCount() {
        BluetoothMessageQueue queue = new BluetoothMessageQueue(10, 500, 2, 1024);
        Msg first = new Msg(new byte[100], Msg.TYPE_RECEIVED, "AA");
        assertTrue(queue.offer(first));
        assertFalse(queue.offer(new Msg(new byte[100], Msg.TYPE_RECEIVED, "AA")));
        java.util.Iterator<Msg> iterator = queue.iterator();
        assertSame(first, iterator.next());
        iterator.remove();
        assertEquals(0L, queue.getPendingBytes());
        assertTrue(queue.offer(new Msg(new byte[100], Msg.TYPE_RECEIVED, "AA")));
        queue.clear();
        assertEquals(0L, queue.getPendingBytes());
    }

    @Test public void blockedReceiverCanBeInterruptedDuringTeardown() throws Exception {
        BluetoothMessageQueue queue = new BluetoothMessageQueue(1, 1024, 1, 1024);
        assertTrue(queue.offer(new Msg("one", Msg.TYPE_RECEIVED, "AA")));
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        Thread receiver = new Thread(() -> {
            entered.countDown();
            try { queue.put(new Msg("two", Msg.TYPE_RECEIVED, "AA")); }
            catch (InterruptedException expected) { interrupted.countDown(); }
        });
        receiver.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        receiver.interrupt();
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        receiver.join(1000);
        assertEquals(1, queue.size());
    }

    @Test public void oversizedSingleFrameCannotWaitForever() throws Exception {
        BluetoothMessageQueue queue = new BluetoothMessageQueue(10, 100, 1, 1024);
        assertFalse(queue.offer(new Msg("large", Msg.TYPE_RECEIVED, "AA")));
        try {
            queue.put(new Msg("large", Msg.TYPE_RECEIVED, "AA"));
            fail("oversized put must not wait for capacity it can never obtain");
        } catch (IllegalArgumentException expected) { }
    }
}
