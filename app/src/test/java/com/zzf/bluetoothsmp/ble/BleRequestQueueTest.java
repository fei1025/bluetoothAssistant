package com.zzf.bluetoothsmp.ble;

import org.junit.Test;
import static org.junit.Assert.*;

public class BleRequestQueueTest {
    @Test public void onlyOneProcedureMayStartAndPendingRemainsFifo() {
        BleRequestQueue<String> queue = new BleRequestQueue<>(3, 100);
        assertTrue(queue.offer("read", 10));
        assertTrue(queue.offer("subscribe", 20));
        assertTrue(queue.offer("write", 30));
        assertEquals("read", queue.startNext());
        assertNull(queue.startNext());
        assertEquals("read", queue.current());
        queue.finish();
        assertEquals("subscribe", queue.startNext());
        queue.finish();
        assertEquals("write", queue.startNext());
        queue.finish();
        assertNull(queue.startNext());
        assertEquals(0, queue.bytes());
    }

    @Test public void activeRequestKeepsCountAndBytesReservedUntilCallback() {
        BleRequestQueue<String> queue = new BleRequestQueue<>(2, 100);
        assertTrue(queue.offer("active", 60));
        assertEquals("active", queue.startNext());
        assertFalse(queue.offer("over-bytes", 41));
        assertTrue(queue.offer("pending", 40));
        assertFalse(queue.offer("over-count", 0));
        assertEquals(100, queue.bytes());
        assertEquals(2, queue.count());
        queue.finish();
        assertEquals(40, queue.bytes());
        assertTrue(queue.offer("new", 60));
        assertEquals("pending", queue.startNext());
    }

    @Test public void disconnectDiscardsActiveAndQueuedWithoutLeakingReservations() {
        BleRequestQueue<String> queue = new BleRequestQueue<>(2, 100);
        queue.offer("old-active", 60);
        queue.startNext();
        queue.offer("old-pending", 40);
        queue.clear();
        assertNull(queue.current());
        assertNull(queue.startNext());
        assertEquals(0, queue.count());
        assertEquals(0, queue.bytes());
        assertTrue(queue.offer("new-session", 100));
        assertEquals("new-session", queue.startNext());
    }
}
