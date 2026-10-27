package com.zzf.bluetoothsmp;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.MessageMapper;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;
import com.zzf.bluetoothsmp.utils.HistoryRepository;
import com.zzf.bluetoothsmp.utils.MonitorMessage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.litepal.LitePal;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Exercises real dispatch/admission/history contracts; the transport is a controlled test double. */
@RunWith(AndroidJUnit4.class)
public class CanceledSendDispatchInstrumentedTest {
    private static final String ADDRESS = "02:00:00:00:00:FC";
    private String testUuid;
    private String listenerUuid;
    private String transportListenerUuid;
    private BluetoothServiceConnect session;
    private boolean completeWrites = true;

    @Before
    public void setUp() {
        testUuid = UUID.randomUUID().toString();
        listenerUuid = UUID.randomUUID().toString();
        transportListenerUuid = UUID.randomUUID().toString();
        StaticObject.foregroundGate.activityStarted(true);
        StaticObject.ensureMessageMonitor();
        session = syntheticSession();
        StaticObject.bluetoothSocketMap.put(ADDRESS, session);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, event -> {
            Msg message = (Msg) event.getEventData()[0];
            if (!isTestMessage(message) || !session.getSessionId().equals(message.getSessionId())) return;
            if (message.isSendCanceled()) {
                StaticObject.publishSendStatus(message, BluetoothSendStatus.CANCELED, null);
                return;
            }
            if (!message.claimTransportAdmission()) return;
            StaticObject.publishSendStatus(message, BluetoothSendStatus.SENDING, null);
            if (completeWrites) StaticObject.publishSendStatus(message, BluetoothSendStatus.SENT, null);
        }, transportListenerUuid);
    }

    @After
    public void tearDown() throws Exception {
        StaticObject.bluetoothEvent.deleteAllEventByUuid(listenerUuid);
        StaticObject.bluetoothEvent.deleteAllEventByUuid(transportListenerUuid);
        StaticObject.bluetoothSocketMap.remove(ADDRESS);
        StaticObject.foregroundGate.activityStopped(false);
        assertTrue(HistoryRepository.awaitWrites(10000L));
        HistoryRepository.deleteConversation(ADDRESS, testUuid);
    }

    @Test
    public void stoppedCentralBacklogIsNotPublishedOrSavedButBoundNormalChatStillIs() throws Exception {
        AtomicInteger sends = listen(BluetoothType.SEND);
        AtomicInteger all = listen(BluetoothType.All_MSG);
        Msg canceled = canceledMessage();
        dispatch(canceled);
        assertEquals(BluetoothSendStatus.CANCELED, canceled.getSendStatus());
        assertEquals(0, sends.get());
        assertEquals(0, all.get());
        assertNoHistory();

        Msg normal = message("normal chat");
        dispatch(normal);
        assertEquals(1, sends.get());
        assertEquals(1, all.get());
        assertEquals(BluetoothSendStatus.SENT, normal.getSendStatus());
        assertNormalChatHistory();
    }

    @Test
    public void databaseConsumersRejectAlreadyCanceledMessagesAndKeepActualCompletedWrites() throws Exception {
        Msg canceled = canceledMessage();
        StaticObject.bluetoothEvent.senMsg(canceled);
        StaticObject.bluetoothEvent.AllMsg(canceled);
        new MonitorMessage().saveMsg(canceled);
        assertNoHistory();

        Msg normal = message("normal chat");
        StaticObject.bluetoothEvent.senMsg(normal);
        StaticObject.bluetoothEvent.AllMsg(normal);
        assertNormalChatHistory();
    }

    @Test
    public void cancellationDuringDispatchNeverCreatesFalseSentHistory() throws Exception {
        completeWrites = false; // Admission can occur; no synthetic socket write has completed.
        BluetoothSendBatch batch = new BluetoothSendBatch();
        AtomicInteger all = listen(BluetoothType.All_MSG);
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, event -> {
            Msg message = (Msg) event.getEventData()[0];
            if (isTestMessage(message)) {
                batch.cancel();
                StaticObject.publishSendStatus(message, BluetoothSendStatus.CANCELED, null);
            }
        }, listenerUuid);
        Msg racing = message("canceled during dispatch");
        racing.setSendBatch(batch);
        dispatch(racing);
        assertEquals(0, all.get());
        assertEquals(BluetoothSendStatus.CANCELED, racing.getSendStatus());
        awaitWrites();
        // Observer order is unspecified. An admitted request may retain a truthful
        // CANCELED row; a request canceled before admission needs no history row.
        for (MessageMapper row : history()) assertEquals("CANCELED", row.getSendStatus());
        assertEquals(0, LitePal.where("senduuid = ? AND sendstatus = ?", testUuid, "SENT")
                .count(MessageMapper.class));
    }

    @Test
    public void oldCentralMessageCannotReplayAfterReturningToForeground() throws Exception {
        AtomicInteger sends = listen(BluetoothType.SEND);
        AtomicInteger all = listen(BluetoothType.All_MSG);
        Msg stale = message("old foreground send");
        StaticObject.foregroundGate.screenUnavailable();
        StaticObject.foregroundGate.screenAvailable();
        dispatch(stale);
        assertEquals(BluetoothSendStatus.CANCELED, stale.getSendStatus());
        assertEquals(0, sends.get());
        assertEquals(0, all.get());
        assertNoHistory();
        // Even a producer bypassing the dispatcher cannot persist an uncompleted old send.
        new MonitorMessage().saveMsg(stale);
        assertNoHistory();

        Msg normal = message("normal chat");
        dispatch(normal);
        assertEquals(1, sends.get());
        assertEquals(1, all.get());
        assertNormalChatHistory();
    }

    @Test
    public void absentSessionFailsBeforePublishingSendOrFabricatingSuccessfulHistory() throws Exception {
        AtomicInteger sends = listen(BluetoothType.SEND);
        Msg rejected = message("no transport");
        StaticObject.bluetoothSocketMap.remove(ADDRESS);
        dispatch(rejected);
        assertEquals(0, sends.get());
        assertEquals(BluetoothSendStatus.FAILED, rejected.getSendStatus());
        assertFalse(rejected.hasTransportAdmission());
        assertNoHistory();
    }

    @Test
    public void replacementSessionCannotReplayAnOldSessionsQueuedMessage() throws Exception {
        AtomicInteger sends = listen(BluetoothType.SEND);
        Msg oldSessionRequest = message("old session request");
        BluetoothServiceConnect replacement = syntheticSession();
        assertNotEquals(session.getSessionId(), replacement.getSessionId());
        StaticObject.bluetoothSocketMap.put(ADDRESS, replacement);
        dispatch(oldSessionRequest);
        assertEquals(0, sends.get());
        assertEquals(BluetoothSendStatus.FAILED, oldSessionRequest.getSendStatus());
        assertFalse(oldSessionRequest.hasTransportAdmission());
        assertNoHistory();
    }

    private BluetoothServiceConnect syntheticSession() {
        return new BluetoothServiceConnect() {
            @Override public boolean bindOutboundMessage(Msg message) {
                if (message.getSessionId() != null && !getSessionId().equals(message.getSessionId())) return false;
                message.setBluetoothAdd(ADDRESS);
                message.setSendUuid(testUuid);
                message.setBluetoothName("cancel regression");
                message.setSessionId(getSessionId());
                if (!message.hasPayload()) message.setPayload(message.getContent().getBytes(StandardCharsets.UTF_8));
                return true;
            }
        };
    }

    private AtomicInteger listen(BluetoothType type) {
        AtomicInteger count = new AtomicInteger();
        StaticObject.bluetoothEvent.addEventListener(type, event -> {
            if (isTestMessage((Msg) event.getEventData()[0])) count.incrementAndGet();
        }, listenerUuid);
        return count;
    }

    private Msg message(String content) {
        Msg message = new Msg(content, Msg.TYPE_SENT, ADDRESS);
        message.setSendUuid(testUuid);
        message.setBluetoothName("cancel regression");
        message.setSessionId(session.getSessionId());
        return message;
    }

    private Msg canceledMessage() {
        BluetoothSendBatch batch = new BluetoothSendBatch();
        Msg message = message("must not become sent history");
        message.setSendBatch(batch);
        batch.cancel();
        return message;
    }

    private boolean isTestMessage(Msg message) { return testUuid.equals(message.getSendUuid()); }

    private void awaitWrites() throws Exception { assertTrue(HistoryRepository.awaitWrites(10000L)); }

    private List<MessageMapper> history() { return LitePal.where("senduuid = ?", testUuid).find(MessageMapper.class); }

    private void assertNoHistory() throws Exception {
        awaitWrites();
        assertEquals(0, history().size());
        assertEquals(0, LitePal.where("uuid = ?", testUuid).count(BluetoothDrive.class));
    }

    private void assertNormalChatHistory() throws Exception {
        awaitWrites();
        List<MessageMapper> rows = history();
        assertEquals(1, rows.size());
        assertEquals("normal chat", rows.get(0).getMessage());
        assertEquals(Msg.TYPE_SENT, rows.get(0).getType());
        assertEquals("SENT", rows.get(0).getSendStatus());
        assertEquals(session.getSessionId(), rows.get(0).getSessionId());
        assertEquals(1, LitePal.where("uuid = ?", testUuid).count(BluetoothDrive.class));
    }

    private static void dispatch(Msg message) throws Exception {
        Method method = StaticObject.class.getDeclaredMethod("dispatchMessage", Msg.class);
        method.setAccessible(true);
        method.invoke(null, message);
    }
}
