package com.zzf.bluetoothsmp.plugin;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;
public class PluginEventBufferTest {
    @Test public void deliveryDoesNotReleaseCapacityUntilAcknowledged() {
        PluginEventBuffer queue = new PluginEventBuffer();
        for (int i=0;i<256;i++) assertTrue(queue.offer("event"));
        assertEquals(16, queue.peek(16).size()); assertFalse(queue.offer("overflow"));
        assertEquals(256, queue.count()); queue.drain(16); assertTrue(queue.offer("next"));
    }
    @Test public void bytesAreBoundedAndOversizeNeverEvictsEarlierProtocolData() {
        PluginEventBuffer queue = new PluginEventBuffer(); assertTrue(queue.offer("first"));
        assertFalse(queue.offer(String.join("", Collections.nCopies(1024 * 1024, "x"))));
        assertEquals("first", queue.peek(1).get(0)); queue.clear(); assertEquals(0, queue.count());
    }
}
