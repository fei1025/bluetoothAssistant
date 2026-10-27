package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.entity.LogItem;
import com.zzf.bluetoothsmp.entity.Msg;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class DebugLogPipelineTest {
    @Test public void previewsAreBoundedAndOriginalBytesRemainExportable() throws Exception {
        byte[] payload = new byte[8192]; Arrays.fill(payload, (byte) 'A');
        Msg message = new Msg(payload, Msg.TYPE_RECEIVED, "02:00:00:00:00:01");
        message.setSessionId("current-session");
        try (DebugLogPipeline pipeline = new DebugLogPipeline("[truncated]")) {
            assertTrue(pipeline.offer(message, false)); awaitIdle(pipeline);
            LogItem item = pipeline.snapshot().get(0);
            assertArrayEquals(payload, item.getRawBytes());
            assertTrue(item.getContent().length() <= 4096);
            assertTrue(item.getHexContent().length() <= 512 * 3 + 11);
            assertTrue(item.getAsciiContent().length() <= 512 + 11);
            assertTrue(item.getContent().endsWith("[truncated]"));
        }
    }

    @Test public void statusEventsUpdateOneRowWithoutReencodingOrRegression() throws Exception {
        Msg message = new Msg(new byte[]{1, 2, 3}, Msg.TYPE_SENT, "02:00:00:00:00:01");
        message.setSessionId("session"); message.updateSendStatus(BluetoothSendStatus.QUEUED, null);
        try (DebugLogPipeline pipeline = new DebugLogPipeline("...")) {
            assertTrue(pipeline.offer(message, true)); awaitIdle(pipeline);
            message.updateSendStatus(BluetoothSendStatus.SENT, null);
            assertTrue(pipeline.offer(message, true)); assertTrue(pipeline.offer(message, true)); awaitIdle(pipeline);
            assertEquals(1, pipeline.snapshot().size());
            assertEquals("SENT", pipeline.snapshot().get(0).getSendStatus());
        }
    }

    @Test public void displayCacheEvictsByByteBudgetAndRejectsOversizedRows() {
        DebugLogStore store = new DebugLogStore(10, 1100);
        for (int i = 0; i < 3; i++) {
            LogItem item = new LogItem("x", new byte[200], LogItem.TYPE_RECEIVED, "device");
            item.setMessageId("row-" + i); assertTrue(store.add(item));
        }
        assertEquals(2, store.size()); assertEquals("row-2", store.get(0).getMessageId());
        assertTrue(store.getBytes() <= 1100);
        assertFalse(store.add(new LogItem("large", new byte[1101], LogItem.TYPE_RECEIVED, "device")));
        assertEquals(2, store.size());
    }

    @Test public void oldSessionStatusCannotChangeTheCurrentRow() {
        DebugLogStore store = new DebugLogStore(10, 4096);
        LogItem item = new LogItem("x", new byte[]{1}, LogItem.TYPE_SENT, "device");
        item.setMessageId("row"); item.setSessionId("new"); item.setSendStatus("SENT"); item.setSendStatusVersion(3);
        store.add(item);
        assertFalse(store.updateStatus("row", "old", "FAILED", 100));
        assertTrue(store.updateStatus("row", "new", "QUEUED", 1));
        assertEquals("SENT", store.get(0).getSendStatus());
    }

    @Test public void exportBytesAreImmutableAcrossCallerMutation() {
        byte[] original = {1, 2, 3};
        LogItem item = new LogItem("x", original, LogItem.TYPE_RECEIVED, "device");
        original[0] = 9; item.getRawBytes()[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, item.getRawBytes());
    }

    private static void awaitIdle(DebugLogPipeline pipeline) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (pipeline.pendingCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, pipeline.pendingCount());
    }
}
