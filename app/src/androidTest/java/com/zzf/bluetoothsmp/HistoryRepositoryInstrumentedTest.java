package com.zzf.bluetoothsmp;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.MessageMapper;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.utils.HistoryRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.litepal.LitePal;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.zzf.bluetoothsmp.utils.HistoryRetention;
import androidx.test.platform.app.InstrumentationRegistry;
import android.os.SystemClock;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class HistoryRepositoryInstrumentedTest {
    private String uuid, address;
    @Before public void setup() throws Exception {
        assertTrue(HistoryRepository.awaitWrites(10000));
        uuid = UUID.randomUUID().toString();
        String suffix = uuid.replace("-", "").substring(0, 10).toUpperCase(java.util.Locale.ROOT);
        address = "02:" + suffix.substring(0,2) + ":" + suffix.substring(2,4) + ":"
                + suffix.substring(4,6) + ":" + suffix.substring(6,8) + ":" + suffix.substring(8,10);
    }
    @After public void cleanup() throws Exception {
        setField("transactionFaultForTests", null);
        assertTrue(HistoryRepository.awaitWrites(10000));
        HistoryRepository.deleteConversation(address, uuid);
        HistoryRepository.deleteConversation(address, null);
    }
    private Msg received(String text, long millis) {
        Msg msg = new Msg(text, Msg.TYPE_RECEIVED, address);
        msg.setSendUuid(uuid); msg.setSenTime(new Date(millis)); return msg;
    }
    @Test public void rawPayloadTimestampAndAnonymousNameRoundTrip() throws Exception {
        byte[] raw = {(byte)0xff, 0, 13, 10};
        Msg message = new Msg(raw, Msg.TYPE_RECEIVED, address);
        message.setContent("FF 00 0D 0A"); message.setSendUuid(uuid);
        message.setTextEncoding(BluetoothTextEncoding.HEX); message.setSenTime(new Date(123456789L));
        HistoryRepository.record(message); assertTrue(HistoryRepository.awaitWrites(10000));
        List<MessageMapper> rows = HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10);
        assertEquals(1, rows.size()); assertTrue(rows.get(0).getId() > 0);
        assertArrayEquals(raw, rows.get(0).getPayload()); assertEquals("HEX", rows.get(0).getTextEncoding());
        assertEquals(123456789L, rows.get(0).getSendTime().getTime());
        assertEquals(message.getMessageId(), HistoryRepository.toMessage(rows.get(0)).getMessageId());
        BluetoothDrive summary = LitePal.where("driveadd = ? and uuid = ?", address, uuid).findFirst(BluetoothDrive.class);
        assertNotNull(summary); assertEquals(address, summary.getDriveName());
    }
    @Test public void terminalBeforeInitialRowIsKeptAndDuplicateIdsUpsert() throws Exception {
        Msg message = new Msg("completed", Msg.TYPE_SENT, address); message.setSendUuid(uuid);
        message.claimTransportAdmission();
        message.updateSendStatus(BluetoothSendStatus.SENT, null);
        HistoryRepository.updateStatus(message); assertTrue(HistoryRepository.awaitWrites(10000));
        HistoryRepository.record(message); HistoryRepository.record(message);
        assertTrue(HistoryRepository.awaitWrites(10000));
        List<MessageMapper> rows = HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10);
        assertEquals(1, rows.size()); assertEquals("SENT", rows.get(0).getSendStatus());
        Msg stale = Msg.restored(message.getPayload(), message.getContent(), Msg.TYPE_SENT, address,
                message.getMessageId(), message.getRequestId());
        stale.setSendUuid(uuid); stale.updateSendStatus(BluetoothSendStatus.QUEUED, null);
        // Direct database data is still guarded against late intermediate state.
        StaticObject.foregroundGate.activityStarted(true);
        try { HistoryRepository.record(stale); assertTrue(HistoryRepository.awaitWrites(10000)); }
        finally { StaticObject.foregroundGate.activityStopped(false); }
        assertEquals("SENT", HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10).get(0).getSendStatus());
    }
    @Test public void keysetPagesAndBoundFiltersDoNotSkipOrDuplicateRecords() throws Exception {
        for (int i=0; i<35; i++) HistoryRepository.record(received("sample " + i, 1000L + i));
        assertTrue(HistoryRepository.awaitWrites(10000));
        List<MessageMapper> page1 = HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 15);
        List<MessageMapper> page2 = HistoryRepository.loadMessages(address, uuid, page1.get(14).getId(), 15);
        assertEquals(15, page1.size()); assertEquals(15, page2.size());
        HashSet<Long> ids = new HashSet<>();
        for (MessageMapper row : page1) assertTrue(ids.add(row.getId()));
        for (MessageMapper row : page2) assertTrue(ids.add(row.getId()));
        assertEquals(5, HistoryRepository.loadMessages(address, uuid, page2.get(14).getId(), 15).size());
        List<MessageMapper> filtered = HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 200,
                "sample", 1010L, 1015L, Msg.TYPE_RECEIVED);
        assertEquals(5, filtered.size());
        assertTrue(HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 200,
                "sample' OR 1=1 --", null, null, null).isEmpty());
    }
    @Test public void legacyTextAndMissingUuidRemainReadableWithoutInventingIdentity() throws Exception {
        MessageMapper legacy = new MessageMapper(); legacy.setSendAdd(address); legacy.setSendUuid(null);
        legacy.setSendTime(new Date(1000)); legacy.setMessage("legacy"); legacy.setType(Msg.TYPE_RECEIVED);
        assertTrue(legacy.save());
        List<MessageMapper> rows = HistoryRepository.loadMessages(address, null, Long.MAX_VALUE, 10);
        assertEquals(1, rows.size()); assertNull(rows.get(0).getPayload());
        Msg first = HistoryRepository.toMessage(rows.get(0)); Msg second = HistoryRepository.toMessage(rows.get(0));
        assertEquals(first.getMessageId(), second.getMessageId()); assertNull(first.getSendUuid());
        assertTrue(HistoryRepository.deleteConversation(address, null));
        assertTrue(HistoryRepository.loadMessages(address, null, Long.MAX_VALUE, 10).isEmpty());
    }

    @Test public void everyByteAndEmptyPayloadRoundTripThroughTheSupportedOrmColumn() throws Exception {
        byte[] allBytes = new byte[256];
        for (int i = 0; i < allBytes.length; i++) allBytes[i] = (byte) i;
        Msg all = new Msg(allBytes, Msg.TYPE_RECEIVED, address); all.setSendUuid(uuid);
        Msg empty = new Msg(new byte[0], Msg.TYPE_RECEIVED, address); empty.setSendUuid(uuid);
        HistoryRepository.record(all); HistoryRepository.record(empty);
        assertTrue(HistoryRepository.awaitWrites(10000));
        List<MessageMapper> rows = HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10);
        assertEquals(2, rows.size());
        for (MessageMapper row : rows) {
            assertNotNull(row.getPayloadBase64());
            assertArrayEquals(row.getMessageId().equals(all.getMessageId()) ? allBytes : new byte[0], row.getPayload());
        }
    }

    @Test public void rolledBackBatchRetainsReceivedBytesAndEarlyTerminalOutcome() throws Exception {
        Msg send = new Msg("sent", Msg.TYPE_SENT, address); send.setSendUuid(uuid); send.claimTransportAdmission();
        send.updateSendStatus(BluetoothSendStatus.SENT, null);
        HistoryRepository.updateStatus(send);
        assertTrue(HistoryRepository.awaitWrites(10000));
        AtomicInteger remainingFaults = new AtomicInteger(2);
        setField("transactionFaultForTests", (Runnable) () -> {
            if (remainingFaults.getAndDecrement() > 0) throw new IllegalStateException("Injected transient commit failure");
        });
        long errors = HistoryRepository.getWriteFailureCount();
        Msg receive = new Msg(new byte[]{0, (byte) 0xff, 1}, Msg.TYPE_RECEIVED, address); receive.setSendUuid(uuid);
        HistoryRepository.record(send); HistoryRepository.record(receive);
        assertTrue(HistoryRepository.awaitWrites(10000));
        assertTrue(HistoryRepository.getWriteFailureCount() >= errors + 2);
        List<MessageMapper> rows = HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10);
        assertEquals(2, rows.size());
        for (MessageMapper row : rows) {
            if (row.getMessageId().equals(send.getMessageId())) assertEquals("SENT", row.getSendStatus());
            else assertArrayEquals(new byte[]{0, (byte) 0xff, 1}, row.getPayload());
        }
    }

    @Test public void deletedConversationCannotBeResurrectedByInFlightOrLateRecords() throws Exception {
        Msg oldReceive = received("old receive", System.currentTimeMillis());
        Msg oldSend = new Msg("old send", Msg.TYPE_SENT, address); oldSend.setSendUuid(uuid); oldSend.claimTransportAdmission();
        synchronized (field("DB_LOCK")) {
            HistoryRepository.record(oldReceive); HistoryRepository.record(oldSend);
            awaitInFlight();
            assertTrue(HistoryRepository.deleteConversation(address, uuid));
        }
        oldSend.updateSendStatus(BluetoothSendStatus.SENT, null);
        HistoryRepository.updateStatus(oldSend);
        HistoryRepository.record(oldReceive); HistoryRepository.record(oldSend);
        assertTrue(HistoryRepository.awaitWrites(10000));
        assertTrue(HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10).isEmpty());
        HistoryRepository.record(received("new receive", System.currentTimeMillis()));
        assertTrue(HistoryRepository.awaitWrites(10000));
        assertEquals(1, HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10).size());
    }

    @Test(timeout = 15000) public void mainThreadCancellationNeverWaitsForBlockedDiskCommit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Msg message = new Msg("queued", Msg.TYPE_SENT, address); message.setSendUuid(uuid); message.claimTransportAdmission();
        message.updateSendStatus(BluetoothSendStatus.QUEUED, null);
        setField("transactionFaultForTests", (Runnable) () -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            HistoryRepository.record(message); assertTrue(entered.await(3, TimeUnit.SECONDS));
            long start = SystemClock.elapsedRealtime();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                message.updateSendStatus(BluetoothSendStatus.CANCELED, null);
                HistoryRepository.updateStatus(message);
            });
            assertTrue("Cancellation must not wait for disk", SystemClock.elapsedRealtime() - start < 1000);
        } finally { release.countDown(); setField("transactionFaultForTests", null); }
        assertTrue(HistoryRepository.awaitWrites(10000));
        assertEquals("CANCELED", HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10).get(0).getSendStatus());
    }

    @Test public void retentionFiltersOldInFlightRowsAndRunsAgainOnLaterAccess() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int previous = HistoryRetention.getDays(context);
        try {
            assertTrue(HistoryRetention.setDaysAndPrune(context, 0));
            synchronized (field("DB_LOCK")) {
                HistoryRepository.record(received("expired", System.currentTimeMillis() - 2L * 86400000L));
                awaitInFlight();
                assertTrue(HistoryRetention.setDaysAndPrune(context, 1));
            }
            assertTrue(HistoryRepository.awaitWrites(10000));
            assertTrue(HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10).isEmpty());
            MessageMapper laterExpired = new MessageMapper(); laterExpired.setSendAdd(address); laterExpired.setSendUuid(uuid);
            laterExpired.setSendTime(new Date(System.currentTimeMillis() - 2L * 86400000L)); laterExpired.setMessage("later expired");
            assertTrue(laterExpired.save());
            setField("lastPruneElapsed", SystemClock.elapsedRealtime() - 60001L);
            assertTrue(HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10).isEmpty());
            HistoryRepository.record(received("current", System.currentTimeMillis()));
            assertTrue(HistoryRepository.awaitWrites(10000));
            assertEquals(1, HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10).size());
        } finally { assertTrue(HistoryRetention.setDaysAndPrune(context, previous)); }
    }

    @Test public void emptyLegacyIdentityIsStableAcrossRepeatedLoads() throws Exception {
        MessageMapper legacy = new MessageMapper(); legacy.setSendAdd(address); legacy.setSendUuid(uuid);
        legacy.setMessageId(""); legacy.setMessage("empty legacy ID"); legacy.setSendTime(new Date());
        assertTrue(legacy.save());
        MessageMapper row = HistoryRepository.loadMessages(address, uuid, Long.MAX_VALUE, 10).get(0);
        assertEquals("legacy:" + row.getId(), HistoryRepository.toMessage(row).getMessageId());
        assertEquals(HistoryRepository.toMessage(row).getMessageId(), HistoryRepository.toMessage(row).getMessageId());
    }
    private static Object field(String name) throws Exception {
        Field value = HistoryRepository.class.getDeclaredField(name); value.setAccessible(true); return value.get(null);
    }
    private static void setField(String name, Object object) throws Exception {
        Field value = HistoryRepository.class.getDeclaredField(name); value.setAccessible(true); value.set(null, object);
    }
    private static void awaitInFlight() throws Exception {
        long end = SystemClock.elapsedRealtime() + 3000L;
        while (SystemClock.elapsedRealtime() < end) {
            synchronized (field("PENDING_LOCK")) { if (field("inFlight") != null) return; }
            Thread.sleep(2);
        }
        fail("Writer did not extract its bounded in-flight batch");
    }
}
