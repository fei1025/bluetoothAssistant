package com.zzf.bluetoothsmp.ble;

import android.app.Application;
import android.bluetooth.*;
import android.os.SystemClock;
import androidx.lifecycle.ViewModelStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.zzf.bluetoothsmp.R;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Exercises the production client with controlled GATT callbacks; no BLE radio required. */
@RunWith(AndroidJUnit4.class)
public class BleClientInstrumentedTest {
    private static final String ADDRESS = "02:00:00:00:BE:01";
    private final AtomicBoolean foreground = new AtomicBoolean(true);
    private final AtomicBoolean permission = new AtomicBoolean(true);
    private ViewModelStore store;
    private BleClient client;
    private Factory factory;

    @Before public void before() { create(5000, 5000); }
    @After public void after() { main(store::clear); }

    private void create(long opTimeout, long connectTimeout) {
        main(() -> {
            if (store != null) store.clear();
            store = new ViewModelStore();
            factory = new Factory();
            Application app = (Application) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
            client = new BleClient(app, factory, foreground::get, permission::get, opTimeout, connectTimeout);
            store.put("client", client);
        });
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void drain() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    private FakeGatt connect() {
        main(() -> client.connect(ADDRESS));
        FakeGatt gatt = factory.last();
        main(() -> gatt.events.connectionState(gatt, 0, BluetoothProfile.STATE_CONNECTED));
        drain();
        main(() -> {
            assertEquals(R.string.ble_discovering, client.state);
            assertEquals(1, gatt.discoveries);
            gatt.events.servicesDiscovered(gatt, 0);
        });
        drain();
        main(() -> { assertTrue(client.isReady()); client.selectWrite(gatt.tx); });
        return gatt;
    }
    private void written(FakeGatt gatt, int status) {
        main(() -> gatt.events.characteristicWritten(gatt, gatt.tx, status)); drain();
    }
    private void descriptor(FakeGatt gatt, int status) {
        main(() -> gatt.events.descriptorWritten(gatt, gatt.cccd, status)); drain();
    }
    private void subscribe(FakeGatt gatt) {
        main(() -> client.subscribe(gatt.tx, 1)); descriptor(gatt, 0);
        main(() -> assertTrue(client.subscribed.contains(gatt.tx)));
    }
    private void awaitClosed(FakeGatt gatt) throws Exception {
        long until = SystemClock.elapsedRealtime() + 2000;
        AtomicBoolean closed = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < until) {
            main(() -> closed.set(gatt.closes == 1));
            if (closed.get()) return;
            Thread.sleep(10);
        }
        fail("GATT must close after timeout");
    }
    private void hasLog(String text) {
        main(() -> {
            for (BleLogStore.Entry entry : client.logs.snapshot()) if (entry.label.contains(text)) return;
            fail("Missing log: " + text);
        });
    }

    @Test public void connectedCallbackDuringOpenIsNotLostAndDiscoveryFailureCloses() {
        factory.connectedDuringOpen = true;
        main(() -> client.connect(ADDRESS)); drain();
        FakeGatt gatt = factory.last();
        main(() -> {
            assertEquals(1, gatt.discoveries);
            gatt.events.servicesDiscovered(gatt, 133);
        }); drain();
        main(() -> {
            assertFalse(client.hasConnection()); assertFalse(client.isReady());
            assertEquals(1, gatt.closes); assertEquals(0, client.pendingCount());
        });
    }

    @Test public void nullConnectionAndRejectedDiscoveryNeverBecomeReady() {
        factory.returnNull = true;
        main(() -> { client.connect(ADDRESS); assertFalse(client.hasConnection()); });
        factory.returnNull = false;
        main(() -> client.connect(ADDRESS));
        FakeGatt gatt = factory.last(); gatt.discoveryAccepted = false;
        main(() -> gatt.events.connectionState(gatt, 0, BluetoothProfile.STATE_CONNECTED)); drain();
        main(() -> { assertFalse(client.isReady()); assertEquals(1, gatt.closes); });
    }

    @Test public void readSplitWriteAndDescriptorReadRemainSerialized() {
        FakeGatt gatt = connect(); byte[] payload = new byte[45]; Arrays.fill(payload, (byte) 7);
        main(() -> {
            client.read(gatt.tx);
            assertTrue(client.send(payload, false, true, 0));
            client.read(gatt.cccd);
            assertEquals(1, gatt.reads); assertEquals(0, gatt.writes.size());
            byte[] read = {1, 2}; gatt.events.characteristicRead(gatt, gatt.tx, read, 0); read[0] = 9;
        }); drain();
        main(() -> { assertEquals(2L, client.rxBytes); assertEquals(1, gatt.writes.size()); assertEquals(0L, client.txBytes); });
        written(gatt, 0);
        main(() -> { assertEquals(20L, client.txBytes); assertEquals(2, gatt.writes.size()); assertEquals(0, gatt.descriptorReads); });
        written(gatt, 0);
        main(() -> { assertEquals(3, gatt.writes.size()); assertEquals(5, gatt.writes.get(2).length); });
        written(gatt, 0);
        main(() -> {
            assertEquals(45L, client.txBytes); assertEquals(1, gatt.descriptorReads);
            gatt.events.descriptorRead(gatt, gatt.cccd, new byte[]{0, 0}, 0);
        }); drain();
        main(() -> assertEquals(0, client.pendingCount()));
        AtomicReference<String> readBytes = new AtomicReference<>();
        main(() -> { for (BleLogStore.Entry entry : client.logs.snapshot()) if (entry.label.startsWith("READ ")) readBytes.set(entry.hex()); });
        assertEquals("01 02", readBytes.get());
    }

    @Test public void duplicateQueuedWriteCallbacksCannotAcknowledgeTheNextChunk() {
        FakeGatt gatt = connect();
        main(() -> {
            client.send(new byte[40], false, true, 0);
            gatt.events.characteristicWritten(gatt, gatt.tx, 0);
            gatt.events.characteristicWritten(gatt, gatt.tx, 0);
        }); drain();
        main(() -> { assertEquals(20L, client.txBytes); assertEquals(2, gatt.writes.size()); assertEquals(1, client.pendingCount()); });
        written(gatt, 0);
        main(() -> { assertEquals(40L, client.txBytes); assertEquals(0, client.pendingCount()); });
    }

    @Test public void failedSecondChunkStopsRemainingPayloadAndPreservesHealthyConnection() {
        FakeGatt gatt = connect();
        main(() -> { client.send(new byte[60], false, true, 0); client.read(gatt.tx); });
        written(gatt, 0); written(gatt, 5);
        main(() -> { assertEquals(20L, client.txBytes); assertEquals(2, gatt.writes.size()); assertEquals(1, gatt.reads); assertTrue(client.isReady()); });
        hasLog("FAILED status=5 completedBytes=20");
    }

    @Test public void immediateWriteRejectionPreservesPlatformErrorAndDoesNotCountSuccess() {
        FakeGatt gatt = connect(); gatt.writeStatus = 201;
        main(() -> { client.send(new byte[]{1}, true, false, 0); client.read(gatt.tx); }); drain();
        main(() -> { assertEquals(0L, client.txBytes); assertEquals(1, gatt.reads); assertEquals(0, gatt.closes); });
        hasLog("FAILED status=201");
    }

    @Test public void wrongCharacteristicAndWrongDescriptorCallbacksDoNotAdvanceRequests() {
        FakeGatt gatt = connect();
        main(() -> {
            client.read(gatt.tx);
            gatt.events.characteristicRead(gatt, gatt.other, new byte[]{1}, 0);
            gatt.events.characteristicWritten(gatt, gatt.tx, 0);
        }); drain();
        main(() -> { assertEquals(1, client.pendingCount()); assertEquals(0L, client.rxBytes); gatt.events.characteristicRead(gatt, gatt.tx, new byte[]{2}, 0); }); drain();
        main(() -> {
            client.read(gatt.cccd);
            gatt.events.descriptorRead(gatt, new BluetoothGattDescriptor(BlePayload.uuid("2902"), 0), new byte[]{1}, 0);
        }); drain();
        main(() -> assertEquals(1, client.pendingCount()));
        descriptorRead(gatt);
        main(() -> assertEquals(0, client.pendingCount()));
    }
    private void descriptorRead(FakeGatt gatt) { main(() -> gatt.events.descriptorRead(gatt, gatt.cccd, new byte[]{0}, 0)); drain(); }

    @Test public void oldConnectionCallbacksAndCharacteristicsCannotAffectReplacement() {
        FakeGatt old = connect();
        main(() -> client.send(new byte[]{1}, false, false, 0));
        FakeGatt current = connect();
        main(() -> {
            client.read(old.tx); client.read(old.cccd); client.selectWrite(old.tx);
            old.events.characteristicWritten(old, old.tx, 0);
            old.events.notification(old, old.tx, new byte[]{1});
            old.events.connectionState(old, 133, BluetoothProfile.STATE_DISCONNECTED);
        }); drain();
        main(() -> { assertTrue(client.isReady()); assertEquals(0, client.pendingCount()); assertEquals(0L, client.txBytes); assertEquals(0L, client.rxBytes); assertEquals(1, old.closes); assertEquals(0, current.closes); assertSame(current.tx, client.writeTarget); });
    }

    @Test public void stoppingRepeatCancelsRemainingChunksButAllowsFollowingOneShot() throws Exception {
        FakeGatt gatt = connect();
        main(() -> {
            client.send(new byte[45], false, true, 100);
            client.stopRepeat();
            client.send(new byte[2], false, false, 0);
        });
        written(gatt, 0);
        main(() -> { assertEquals(2, gatt.writes.size()); assertEquals(2, gatt.writes.get(1).length); assertFalse(client.isRepeating()); });
        written(gatt, 0); Thread.sleep(140); drain();
        main(() -> { assertEquals(2, gatt.writes.size()); assertEquals(22L, client.txBytes); assertEquals(0, client.pendingCount()); });
    }

    @Test public void replacedRepeatDoesNotScheduleTheOldLoop() throws Exception {
        FakeGatt gatt = connect();
        main(() -> { client.send(new byte[]{1}, false, false, 100); client.stopRepeat(); client.send(new byte[]{2}, false, false, 100); });
        written(gatt, 0); Thread.sleep(140); drain();
        main(() -> { assertEquals(2, gatt.writes.size()); assertArrayEquals(new byte[]{2}, gatt.writes.get(1)); });
        written(gatt, 0); Thread.sleep(140); drain();
        main(() -> { assertEquals(3, gatt.writes.size()); client.stopRepeat(); });
    }

    @Test public void notificationEnableCommitsOnlyOnCccdSuccessAndFailureRollsBack() {
        FakeGatt gatt = connect();
        main(() -> {
            client.subscribe(gatt.tx, 1);
            assertEquals(Arrays.asList("local:true", "cccd:1"), gatt.notificationCalls);
            assertFalse(client.subscribed.contains(gatt.tx));
        }); descriptor(gatt, 5);
        main(() -> { assertEquals(Arrays.asList("local:true", "cccd:1", "local:false"), gatt.notificationCalls); assertFalse(client.subscribed.contains(gatt.tx)); assertTrue(client.isReady()); });
        subscribe(gatt);
    }

    @Test public void disableFailureKeepsLocalDeliveryAndSuccessfulDisableWaitsForCccd() {
        FakeGatt gatt = connect(); subscribe(gatt); gatt.notificationCalls.clear();
        main(() -> {
            client.subscribe(gatt.tx, 0);
            assertEquals(Collections.singletonList("cccd:0"), gatt.notificationCalls);
            gatt.events.notification(gatt, gatt.tx, new byte[]{1, 2, 3});
        }); descriptor(gatt, 5);
        main(() -> { assertTrue(client.subscribed.contains(gatt.tx)); assertEquals(3L, client.rxBytes); assertEquals(Collections.singletonList("cccd:0"), gatt.notificationCalls); client.subscribe(gatt.tx, 0); });
        descriptor(gatt, 0);
        main(() -> { assertFalse(client.subscribed.contains(gatt.tx)); assertEquals("local:false", gatt.notificationCalls.get(2)); });
    }

    @Test public void indicationUsesCorrectCccdAndInvalidModeIsRejected() {
        FakeGatt gatt = connect();
        main(() -> {
            client.subscribe(gatt.tx, 3); assertEquals(0, gatt.descriptorWrites.size());
            client.subscribe(gatt.tx, 2); assertArrayEquals(new byte[]{2, 0}, gatt.descriptorWrites.get(0));
        }); descriptor(gatt, 0);
        main(() -> assertTrue(client.subscribed.contains(gatt.tx)));
    }

    @Test public void failedLocalEnableDoesNotWriteCccdAndQueueContinues() {
        FakeGatt gatt = connect(); gatt.localResults.add(false);
        main(() -> { client.subscribe(gatt.tx, 1); client.read(gatt.tx); }); drain();
        main(() -> { assertEquals(0, gatt.descriptorWrites.size()); assertEquals(1, gatt.reads); assertTrue(client.isReady()); assertFalse(client.subscribed.contains(gatt.tx)); });
    }

    @Test public void failedNotificationRollbackClosesInsteadOfReportingFalseSubscriptionState() {
        FakeGatt gatt = connect(); gatt.localResults.add(true); gatt.localResults.add(false);
        main(() -> { client.subscribe(gatt.tx, 1); client.read(gatt.tx); }); descriptor(gatt, 5);
        main(() -> { assertFalse(client.hasConnection()); assertTrue(client.subscribed.isEmpty()); assertEquals(1, gatt.closes); assertEquals(0, gatt.reads); });
        hasLog("NOTIFICATION_ROLLBACK_FAILED");
    }

    @Test public void failedLocalDisableAfterRemoteSuccessClosesAndCancelsPendingSend() {
        FakeGatt gatt = connect(); subscribe(gatt); gatt.localResults.add(false);
        main(() -> { client.subscribe(gatt.tx, 0); client.send(new byte[]{1}, false, false, 0); }); descriptor(gatt, 0);
        main(() -> { assertFalse(client.hasConnection()); assertEquals(0, gatt.writes.size()); assertEquals(0, client.pendingCount()); assertEquals(1, gatt.closes); });
    }

    @Test public void queueFullRejectsOnlyNewWorkAndKeepsActiveSessionHealthy() {
        FakeGatt gatt = connect();
        main(() -> {
            for (int i = 0; i < 129; i++) client.read(gatt.tx);
            assertEquals(128, client.pendingCount()); assertEquals(1, gatt.reads); assertEquals(0, gatt.closes);
            assertEquals("QUEUE_FULL", client.lastError);
            gatt.events.characteristicRead(gatt, gatt.tx, new byte[]{1}, 0);
        }); drain();
        main(() -> { assertEquals(127, client.pendingCount()); assertEquals(2, gatt.reads); assertTrue(client.isReady()); });
    }

    @Test public void writeByteBudgetIncludesActiveRequest() {
        FakeGatt gatt = connect();
        main(() -> {
            int accepted = 0;
            for (int i = 0; i < 33; i++) if (client.send(new byte[65536], false, true, 0)) accepted++;
            assertEquals(31, accepted); assertEquals(31, client.pendingCount()); assertEquals(1, gatt.writes.size()); assertTrue(client.isReady());
        });
    }

    @Test public void disconnectClosesEvenIfDisconnectThrowsAndLateCallbacksAreIgnored() {
        FakeGatt gatt = connect(); subscribe(gatt); gatt.throwDisconnect = true;
        main(() -> {
            client.send(new byte[40], false, true, 100); client.read(gatt.tx);
            client.disconnect("USER_DISCONNECTED");
            assertEquals(1, gatt.closes); assertEquals(0, client.pendingCount()); assertFalse(client.isRepeating()); assertTrue(client.subscribed.isEmpty());
            gatt.events.characteristicWritten(gatt, gatt.tx, 0);
            gatt.events.notification(gatt, gatt.tx, new byte[]{1});
        }); drain();
        main(() -> { assertEquals(0L, client.txBytes); assertEquals(0L, client.rxBytes); assertNull(client.writeTarget); });
    }

    @Test public void permissionRevokedBeforeQueuedWriteClosesWithoutWriting() {
        FakeGatt gatt = connect();
        main(() -> { client.read(gatt.tx); client.send(new byte[]{1}, false, false, 0); permission.set(false); gatt.events.characteristicRead(gatt, gatt.tx, new byte[]{1}, 0); }); drain();
        main(() -> { assertEquals(1, gatt.closes); assertEquals(0, gatt.writes.size()); assertEquals(0, client.pendingCount()); });
    }

    @Test public void backgroundCallbackAndClearedViewModelCannotReviveConnection() {
        FakeGatt gatt = connect();
        main(() -> { client.read(gatt.tx); foreground.set(false); gatt.events.characteristicRead(gatt, gatt.tx, new byte[]{1}, 0); }); drain();
        main(() -> { assertEquals(1, gatt.closes); assertEquals(0L, client.rxBytes); foreground.set(true); store.clear(); client.connect(ADDRESS); gatt.events.servicesDiscovered(gatt, 0); }); drain();
        main(() -> { assertFalse(client.hasConnection()); assertEquals(1, factory.connections.size()); });
    }

    @Test public void connectionAndAttTimeoutsCloseWithoutAutomaticRetry() throws Exception {
        create(100, 100);
        main(() -> client.connect(ADDRESS)); FakeGatt connecting = factory.last(); awaitClosed(connecting);
        create(150, 1000); FakeGatt gatt = connect();
        main(() -> client.send(new byte[]{1}, false, false, 0)); awaitClosed(gatt);
        main(() -> { assertEquals(0L, client.txBytes); assertEquals(1, factory.connections.size()); assertEquals(0, client.pendingCount()); });
        hasLog("TIMEOUT");
    }

    @Test public void unsolicitedMtuSurvivesWriteCallbackAdvancingTheProcedure() {
        FakeGatt gatt = connect();
        main(() -> {
            client.send(new byte[50], false, true, 0);
            gatt.events.characteristicWritten(gatt, gatt.tx, 0);
            gatt.events.mtuChanged(gatt, 247, 0);
        }); drain();
        main(() -> { assertEquals(247, client.mtu); assertEquals(20L, client.txBytes); assertEquals(2, gatt.writes.size()); assertEquals(30, gatt.writes.get(1).length); });
        written(gatt, 0);
        main(() -> { assertEquals(2, gatt.writes.size()); assertEquals(50L, client.txBytes); assertEquals(0, client.pendingCount()); });
    }

    @Test public void shrinkingMtuIsAppliedBeforeNextChunkAndDoesNotAcknowledgeWrite() {
        FakeGatt gatt = connect();
        main(() -> gatt.events.mtuChanged(gatt, 247, 0)); drain();
        main(() -> {
            client.send(new byte[500], true, true, 0);
            assertEquals(244, gatt.writes.get(0).length);
            gatt.events.characteristicWritten(gatt, gatt.tx, 0);
            gatt.events.mtuChanged(gatt, 23, 0);
        }); drain();
        main(() -> { assertEquals(23, client.mtu); assertEquals(244L, client.txBytes); assertEquals(2, gatt.writes.size()); assertEquals(20, gatt.writes.get(1).length); assertEquals(1, client.pendingCount()); });
    }

    @Test public void serviceChangedDisposesQueueAndNeverReusesStaleAttributeInstances() {
        FakeGatt gatt = connect(); subscribe(gatt);
        main(() -> { client.read(gatt.tx); client.send(new byte[]{1}, false, false, 0); gatt.events.servicesChanged(gatt); }); drain();
        main(() -> { assertFalse(client.isReady()); assertEquals(0, client.pendingCount()); assertTrue(client.subscribed.isEmpty()); assertNull(client.writeTarget); assertEquals(1, gatt.closes); assertEquals(0, gatt.writes.size()); });
    }

    @Test public void delayedSuspendOfOldSessionCannotCloseReplacement() {
        FakeGatt old = connect();
        main(() -> {
            Thread worker = new Thread(client::suspend);
            worker.start();
            try { worker.join(1000); } catch (InterruptedException failure) { throw new AssertionError(failure); }
            assertFalse(worker.isAlive());
            client.connect(ADDRESS);
        }); drain();
        FakeGatt current = factory.last();
        main(() -> { assertEquals(1, old.closes); assertEquals(0, current.closes); assertTrue(client.hasConnection()); });
    }

    @Test public void oldConnectTimeoutIsCanceledWhenReplacementBecomesReady() throws Exception {
        create(5000, 150);
        main(() -> client.connect(ADDRESS)); FakeGatt old = factory.last();
        FakeGatt current = connect();
        Thread.sleep(200); drain();
        main(() -> { assertEquals(1, old.closes); assertEquals(0, current.closes); assertTrue(client.isReady()); });
    }

    @Test public void immediatePermissionAndUnavailableStatusesDisposeSession() {
        FakeGatt gatt = connect(); gatt.writeStatus = BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
        main(() -> { client.send(new byte[]{1}, false, false, 0); assertEquals(1, gatt.closes); assertFalse(client.hasConnection()); });
        FakeGatt next = connect(); next.writeStatus = BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        main(() -> { client.subscribe(next.tx, 1); }); descriptor(next, 0);
        main(() -> { client.send(new byte[]{1}, false, false, 0); assertEquals(1, next.closes); assertTrue(client.subscribed.isEmpty()); });
    }

    @Test public void unexpectedStartExceptionClosesUnknownOutcomeInsteadOfStartingNextRequest() {
        FakeGatt gatt = connect(); gatt.throwWrite = true;
        main(() -> { client.send(new byte[]{1}, false, false, 0); client.read(gatt.tx); }); drain();
        main(() -> { assertEquals(1, gatt.closes); assertEquals(0, gatt.reads); assertEquals(0, client.pendingCount()); });
        hasLog("GATT_START_FAILED");
    }

    @Test public void rejectedDescriptorStartRollsBackAndKeepsQueueUsable() {
        FakeGatt gatt = connect(); gatt.descriptorStatus = 201;
        main(() -> { client.subscribe(gatt.tx, 1); client.read(gatt.tx); }); drain();
        main(() -> { assertEquals(Arrays.asList("local:true", "cccd:1", "local:false"), gatt.notificationCalls); assertEquals(1, gatt.reads); assertTrue(client.isReady()); });
        hasLog("FAILED status=201");
    }

    @Test public void pluginWritePromiseWaitsForGattCallbackAndMarksNoResponseUnconfirmed() throws Exception {
        PluginBleTransport plugin = new PluginBleTransport(client, (name,value)->{});
        FakeGatt gatt = pluginConnect(plugin);
        String characteristic = pluginHandle(plugin,0);
        PluginReply reply = new PluginReply();
        byte[] bytes = {0,(byte)255,13,10};
        main(()->plugin.execute("write", com.zzf.bluetoothsmp.plugin.PluginJson.object("sessionId",plugin.snapshot().optString("sessionId"),
                "characteristic",characteristic,"bytes",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP),"withoutResponse",true),reply));
        assertFalse(reply.done);assertArrayEquals(bytes,gatt.writes.get(0));
        written(gatt,0);assertTrue(reply.done);assertNull(reply.error);
        assertEquals("SENT_UNCONFIRMED",((org.json.JSONObject)reply.value).getString("status"));
    }
    @Test public void pluginReadRejectsOtherCharacteristicInstanceAndReturnsExactBytes() throws Exception {
        PluginBleTransport plugin = new PluginBleTransport(client,(name,value)->{});
        FakeGatt gatt = pluginConnect(plugin);String handle=pluginHandle(plugin,0);
        assertNotEquals(handle,pluginHandle(plugin,1));
        PluginReply reply=new PluginReply();
        main(()->plugin.execute("read",com.zzf.bluetoothsmp.plugin.PluginJson.object("sessionId",plugin.snapshot().optString("sessionId"),"characteristic",handle),reply));
        main(()->gatt.events.characteristicRead(gatt,gatt.other,new byte[]{9},0));drain();assertFalse(reply.done);
        byte[] bytes={0,(byte)255,2};main(()->gatt.events.characteristicRead(gatt,gatt.tx,bytes,0));drain();
        assertTrue(reply.done);assertNull(reply.error);
        assertArrayEquals(bytes,android.util.Base64.decode(((org.json.JSONObject)reply.value).getString("bytes"),android.util.Base64.NO_WRAP));
    }
    @Test public void pluginCannotReuseCharacteristicHandleAfterReconnect() throws Exception {
        PluginBleTransport plugin=new PluginBleTransport(client,(name,value)->{});pluginConnect(plugin);String old=pluginHandle(plugin,0);
        main(plugin::disconnect);pluginConnect(plugin);pluginHandle(plugin,0);
        PluginReply reply=new PluginReply();main(()->plugin.execute("read",com.zzf.bluetoothsmp.plugin.PluginJson.object(
                "sessionId",plugin.snapshot().optString("sessionId"),"characteristic",old),reply));
        assertTrue(reply.done);assertEquals("STALE_CHARACTERISTIC",reply.error);
    }
    @Test public void pluginCanceledActiveWriteHasUnknownOutcomeWhileQueuedReadNeverStarts() throws Exception {
        PluginBleTransport plugin=new PluginBleTransport(client,(name,value)->{});FakeGatt gatt=pluginConnect(plugin);String handle=pluginHandle(plugin,0);
        PluginReply write=new PluginReply(),read=new PluginReply();
        main(()->{
            org.json.JSONObject args=com.zzf.bluetoothsmp.plugin.PluginJson.object("sessionId",plugin.snapshot().optString("sessionId"),"characteristic",handle,"bytes","AQI=");
            plugin.execute("write",args,write);plugin.execute("read",args,read);plugin.disconnect();
        });
        assertEquals("OUTCOME_UNKNOWN",write.error);assertEquals("SESSION_ENDED",read.error);assertEquals(0,gatt.reads);
    }
    @Test public void pluginCannotTakeOverAReservedNativeBleDevice() {
        Object owner=new Object();assertTrue(com.zzf.bluetoothsmp.BluetoothSessionOwners.acquire("ble",ADDRESS,owner));
        try {
            PluginBleTransport plugin=new PluginBleTransport(client,(name,value)->{});PluginReply reply=new PluginReply();
            main(()->plugin.execute("connect",com.zzf.bluetoothsmp.plugin.PluginJson.object("address",ADDRESS),reply));
            assertTrue(reply.done);assertEquals("DEVICE_BUSY",reply.error);assertTrue(factory.connections.isEmpty());
        } finally {com.zzf.bluetoothsmp.BluetoothSessionOwners.release("ble",ADDRESS,owner);}
    }
    @Test public void pluginNotificationFloodReportsLossAndClosesInsteadOfSilentlyDropping() throws Exception {
        java.util.concurrent.atomic.AtomicInteger loss=new java.util.concurrent.atomic.AtomicInteger();
        PluginBleTransport plugin=new PluginBleTransport(client,(name,value)->{if(name.equals("dataLoss"))loss.incrementAndGet();});
        FakeGatt gatt=pluginConnect(plugin);pluginHandle(plugin,0);
        main(()->{for(int i=0;i<300;i++)gatt.events.notification(gatt,gatt.tx,new byte[]{(byte)i});});drain();
        assertEquals(1,loss.get());assertTrue(gatt.closes>0);assertFalse(plugin.busy());
    }
    private FakeGatt pluginConnect(PluginBleTransport plugin) {
        PluginReply reply=new PluginReply();main(()->plugin.execute("connect",com.zzf.bluetoothsmp.plugin.PluginJson.object("address",ADDRESS),reply));
        FakeGatt gatt=factory.last();main(()->gatt.events.connectionState(gatt,0,BluetoothProfile.STATE_CONNECTED));drain();
        main(()->gatt.events.servicesDiscovered(gatt,0));drain();assertTrue(reply.done);assertNull(reply.error);return gatt;
    }
    private String pluginHandle(PluginBleTransport plugin,int index) throws Exception {
        PluginReply reply=new PluginReply();main(()->plugin.execute("services",com.zzf.bluetoothsmp.plugin.PluginJson.object("sessionId",plugin.snapshot().optString("sessionId")),reply));
        return ((org.json.JSONArray)reply.value).getJSONObject(0).getJSONArray("characteristics").getJSONObject(index).getString("handle");
    }
    private static final class PluginReply implements com.zzf.bluetoothsmp.plugin.PluginTransport.Reply {
        volatile boolean done;volatile Object value;volatile String error;
        @Override public void success(Object value){this.value=value;done=true;}
        @Override public void error(String code,String message){error=code;done=true;}
    }

    private static final class Factory implements BleGattConnection.Factory {
        final List<FakeGatt> connections = new ArrayList<>();
        boolean connectedDuringOpen, returnNull;
        @Override public BleGattConnection open(String address, BleGattConnection.Events events) {
            if (returnNull) return null;
            FakeGatt gatt = new FakeGatt(events); connections.add(gatt);
            if (connectedDuringOpen) events.connectionState(gatt, 0, BluetoothProfile.STATE_CONNECTED);
            return gatt;
        }
        FakeGatt last() { return connections.get(connections.size() - 1); }
    }
    private static final class FakeGatt implements BleGattConnection {
        final Events events;
        final BluetoothGattService service = new BluetoothGattService(BlePayload.uuid("ffe0"), BluetoothGattService.SERVICE_TYPE_PRIMARY);
        final BluetoothGattCharacteristic tx = new BluetoothGattCharacteristic(BlePayload.uuid("ffe1"), 2 | 4 | 8 | 16 | 32, 0);
        final BluetoothGattCharacteristic other = new BluetoothGattCharacteristic(BlePayload.uuid("ffe1"), 2, 0);
        final BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(BlePayload.uuid("2902"), 0);
        final List<byte[]> writes = new ArrayList<>(), descriptorWrites = new ArrayList<>();
        final List<String> notificationCalls = new ArrayList<>();
        final ArrayDeque<Boolean> localResults = new ArrayDeque<>();
        int discoveries, reads, descriptorReads, closes, writeStatus, descriptorStatus;
        boolean discoveryAccepted = true, throwDisconnect, throwWrite;
        FakeGatt(Events events) { this.events = events; service.addCharacteristic(tx); service.addCharacteristic(other); tx.addDescriptor(cccd); }
        @Override public boolean discoverServices() { discoveries++; return discoveryAccepted; }
        @Override public List<BluetoothGattService> getServices() { return Collections.singletonList(service); }
        @Override public boolean readCharacteristic(BluetoothGattCharacteristic characteristic) { reads++; return true; }
        @Override public boolean readDescriptor(BluetoothGattDescriptor descriptor) { descriptorReads++; return true; }
        @Override public int writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value, int type) { writes.add(value.clone()); if (throwWrite) throw new IllegalStateException("outcome unknown"); return writeStatus; }
        @Override public int writeDescriptor(BluetoothGattDescriptor descriptor, byte[] value) { descriptorWrites.add(value.clone()); notificationCalls.add("cccd:" + value[0]); return descriptorStatus; }
        @Override public boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) { notificationCalls.add("local:" + enabled); return localResults.isEmpty() || localResults.removeFirst(); }
        @Override public boolean requestMtu(int mtu) { return true; }
        @Override public boolean readRemoteRssi() { return true; }
        @Override public boolean requestConnectionPriority(int priority) { return true; }
        @Override public void disconnect() { if (throwDisconnect) throw new IllegalStateException("test disconnect failure"); }
        @Override public void close() { closes++; }
    }
}
