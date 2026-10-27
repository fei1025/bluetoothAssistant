package com.zzf.bluetoothsmp.ble;

import java.util.List;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class BleLogStoreTest {
    @Test public void countLimitEvictsOldestAndReportsLoss() {
        BleLogStore store = new BleLogStore();
        for (int i = 0; i < 600; i++) store.add("packet " + i, new byte[]{1});
        assertEquals(512, store.snapshot().size());
        assertEquals(88, store.dropped());
        assertEquals("packet 88", store.snapshot().get(0).label);
    }

    @Test public void byteLimitIncludesMetadataAndSnapshotOwnsRawBytes() {
        BleLogStore store = new BleLogStore();
        byte[] packet = new byte[65536]; Arrays.fill(packet, (byte) 0xA5);
        store.add("first", packet);
        List<BleLogStore.Entry> snapshot = store.snapshot();
        packet[0] = 0;
        assertTrue(snapshot.get(0).hex().startsWith("A5 A5"));
        for (int i = 0; i < 30; i++) store.add("packet", packet);
        int bytes = 0;
        for (BleLogStore.Entry entry : store.snapshot()) bytes += entry.size();
        assertTrue(bytes <= BleLogStore.MAX_BYTES);
        assertTrue(store.dropped() > 0);
        assertTrue(snapshot.get(0).hex().startsWith("A5 A5"));
        assertTrue(snapshot.get(0).preview(true).length() < 2000);
        store.clear();
        assertEquals(0, store.snapshot().size());
        assertEquals(0, store.dropped());
    }
}
