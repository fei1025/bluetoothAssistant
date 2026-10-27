package com.zzf.bluetoothsmp;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Controlled session handoff tests; no adapter/socket/permission or Bluetooth peer is required. */
@RunWith(AndroidJUnit4.class)
public class BluetoothSessionDispatchInstrumentedTest {
    private static final String ADDRESS = "02:00:00:00:00:EF";
    private String listenerId;
    private BluetoothServiceConnect original, replacement;

    @Before public void setUp() throws Exception {
        StaticObject.foregroundGate.activityStarted(true);
        listenerId = UUID.randomUUID().toString();
        original = session();
        replacement = session();
    }
    @After public void tearDown() {
        StaticObject.bluetoothEvent.deleteAllEventByUuid(listenerId);
        StaticObject.bluetoothSocketMap.remove(ADDRESS, original);
        StaticObject.bluetoothSocketMap.remove(ADDRESS, replacement);
        StaticObject.foregroundGate.activityStopped(false);
    }

    @Test public void oldSendCannotBeRoutedOntoTheReplacementSession() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.SEND, event -> sends.incrementAndGet(), listenerId);
        Msg oldSend = new Msg("old command", Msg.TYPE_SENT, ADDRESS);
        oldSend.setPersistHistory(false);
        assertTrue(original.bindOutboundMessage(oldSend));
        StaticObject.bluetoothSocketMap.put(ADDRESS, replacement);
        dispatch(oldSend);
        assertEquals(0, sends.get());
        assertEquals(BluetoothSendStatus.FAILED, oldSend.getSendStatus());
        assertTrue(replacement.isSessionOpen());
    }

    @Test public void lateDisconnectCannotReachOrCloseTheReplacementSession() throws Exception {
        AtomicInteger disconnects = new AtomicInteger();
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.NOT_CONNECT, event -> disconnects.incrementAndGet(), listenerId);
        StaticObject.bluetoothSocketMap.put(ADDRESS, replacement);
        Msg oldDisconnect = new Msg(ADDRESS);
        oldDisconnect.setStateType(1);
        oldDisconnect.setSessionId(original.getSessionId());
        dispatch(oldDisconnect);
        assertEquals(0, disconnects.get());
        assertTrue(replacement.isSessionOpen());
        Msg currentDisconnect = new Msg(ADDRESS);
        currentDisconnect.setStateType(1);
        currentDisconnect.setSessionId(replacement.getSessionId());
        dispatch(currentDisconnect);
        assertEquals(1, disconnects.get());
    }

    @Test public void physicallyReceivedOldForegroundFrameRemainsAvailableForHistory() throws Exception {
        AtomicInteger receives = new AtomicInteger();
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.RECEIVE, event -> receives.incrementAndGet(), listenerId);
        Msg received = new Msg(new byte[]{1, 2, 3}, Msg.TYPE_RECEIVED, ADDRESS);
        received.setPersistHistory(false);
        received.setSessionId(original.getSessionId());
        StaticObject.foregroundGate.screenUnavailable();
        StaticObject.foregroundGate.screenAvailable();
        assertFalse(received.isForegroundGenerationCurrent());
        dispatch(received);
        assertEquals(1, receives.get());
    }

    @Test public void supersededClientCancelsItsTimeoutAndRejectsLateFailure() throws Exception {
        BluetoothObject attempt = new BluetoothObject();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ScheduledFuture<?> timeout = scheduler.schedule(() -> {}, 30, TimeUnit.SECONDS);
            set(attempt, "connectionTimeoutTask", timeout);
            set(attempt, "foregroundGeneration", StaticObject.foregroundGate.currentGeneration());
            attempt.finishSupersededAttempt();
            assertTrue(timeout.isCancelled());
            Field field = BluetoothObject.class.getDeclaredField("failureGate");
            field.setAccessible(true);
            assertFalse(((BluetoothConnectionFailureGate) field.get(attempt)).tryReport());
            Method lateTimeout = BluetoothObject.class.getDeclaredMethod("connectionTimeout");
            lateTimeout.setAccessible(true);
            lateTimeout.invoke(attempt);
        } finally { scheduler.shutdownNow(); }
    }
    @Test public void pluginReceivePreservesRawBytesAndNeverPublishesToHistoryBus() throws Exception {
        AtomicInteger receives=new AtomicInteger();java.util.concurrent.atomic.AtomicReference<byte[]> data=new java.util.concurrent.atomic.AtomicReference<>();
        StaticObject.bluetoothEvent.addEventListener(BluetoothType.RECEIVE,event->receives.incrementAndGet(),listenerId);
        original.configurePlugin(new BluetoothServiceConnect.PluginListener(){
            @Override public void onData(byte[] bytes){data.set(bytes);}
            @Override public void onSend(long id,BluetoothSendStatus status,Throwable error){}
            @Override public void onClosed(){}
        });
        byte[] bytes={0,(byte)255,13,10,(byte)0xE4,(byte)0xB8};
        Method publish=BluetoothServiceConnect.class.getDeclaredMethod("publishReceivedFrame",byte[].class);publish.setAccessible(true);publish.invoke(original,(Object)bytes);
        assertArrayEquals(bytes,data.get());assertNotSame(bytes,data.get());assertEquals(0,receives.get());
    }
    @Test public void pluginWriteUsesExactBytesAndCompletionWaitsForWriter() throws Exception {
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),sent=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<byte[]> actual=new java.util.concurrent.atomic.AtomicReference<>();
        original.configurePlugin(new BluetoothServiceConnect.PluginListener(){
            @Override public void onData(byte[] bytes){}
            @Override public void onSend(long id,BluetoothSendStatus status,Throwable error){if(status==BluetoothSendStatus.SENT)sent.countDown();}
            @Override public void onClosed(){}
        });
        Method status=BluetoothServiceConnect.class.getDeclaredMethod("onSendStatus",long.class,BluetoothSendStatus.class,int.class,Throwable.class);status.setAccessible(true);
        BluetoothSendQueue queue=new BluetoothSendQueue(bytes->{actual.set(bytes);entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}},
                (id,state,count,error)->{try{status.invoke(original,id,state,count,error);}catch(Exception e){throw new RuntimeException(e);}});
        try{
            set(original,"sendQueue",queue);byte[] payload={0,(byte)255,13,10};original.sendPlugin(BluetoothRequestIds.next(),payload);
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals(1,sent.getCount());assertArrayEquals(payload,actual.get());
            release.countDown();assertTrue(sent.await(3,TimeUnit.SECONDS));assertTrue(original.isSessionOpen());
        }finally{release.countDown();queue.close();}
    }

    private BluetoothServiceConnect session() throws Exception {
        BluetoothServiceConnect value = new BluetoothServiceConnect();
        set(value, "bluetoothAdd", ADDRESS);
        set(value, "bluetoothName", "dispatch regression");
        set(value, "sendUuid", UUID.randomUUID().toString());
        set(value, "frameConfig", BluetoothFrameConfig.defaultConfig());
        set(value, "foregroundGeneration", StaticObject.foregroundGate.currentGeneration());
        return value;
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static void dispatch(Msg message) throws Exception {
        Method method = StaticObject.class.getDeclaredMethod("dispatchMessage", Msg.class);
        method.setAccessible(true);
        method.invoke(null, message);
    }
}
