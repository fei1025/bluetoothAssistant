package com.zzf.bluetoothsmp;

import android.bluetooth.BluetoothSocket;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Delayed DB/listen/close operations exercise cancellation without Bluetooth hardware. */
@RunWith(AndroidJUnit4.class)
public class BluetoothServerStartupInstrumentedTest {
    private final List<BluetoothService> services = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();
    private Context context;
    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        StaticObject.foregroundGate.activityStarted(true);
    }
    @After public void tearDown() throws Exception {
        for (CountDownLatch release : releases) release.countDown();
        for (BluetoothService service : services) service.stop();
        drainStartup();
        StaticObject.foregroundGate.activityStopped(false);
    }
    @Test(timeout = 15000) public void mainStartAndStopDoNotWaitForTheDatabaseAndCanceledStartNeverOpens() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = release();
        AtomicInteger opens = new AtomicInteger();
        BluetoothService service = service(() -> {
            entered.countDown(); awaitIgnoringInterrupt(release); return BluetoothObject.SPP_UUID;
        }, (adapter, uuid) -> { opens.incrementAndGet(); return new FakeListener(); });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> start(service));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(service.isRunning());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(service::stop);
        assertFalse(service.isRunning());
        release.countDown(); drainStartup();
        assertEquals(0, opens.get());
    }
    @Test(timeout = 15000) public void listenerReturnedAfterStopIsClosedWithoutStartingAccept() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = release();
        FakeListener listener = new FakeListener();
        BluetoothService service = service(() -> BluetoothObject.SPP_UUID, (adapter, uuid) -> {
            entered.countDown(); awaitIgnoringInterrupt(release); return listener;
        });
        start(service);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(service::stop);
        release.countDown(); drainStartup();
        assertTrue(listener.closed.await(5, TimeUnit.SECONDS));
        assertEquals(0, listener.accepts.get());
        assertFalse(service.isRunning());
    }
    @Test(timeout = 15000) public void restartWaitsForOldListenerCloseWithoutBlockingMain() throws Exception {
        FakeListener original = new FakeListener();
        original.closeRelease = release();
        BluetoothService first = service(() -> BluetoothObject.SPP_UUID, (adapter, uuid) -> original);
        start(first); assertTrue(original.acceptStarted.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(first::stop);
        assertTrue(original.closeEntered.await(5, TimeUnit.SECONDS));
        FakeListener replacement = new FakeListener();
        AtomicInteger replacementOpens = new AtomicInteger();
        BluetoothService second = service(() -> BluetoothObject.SPP_UUID, (adapter, uuid) -> {
            replacementOpens.incrementAndGet(); return replacement;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> start(second));
        assertEquals(0, replacementOpens.get());
        original.closeRelease.countDown();
        assertTrue(replacement.acceptStarted.await(5, TimeUnit.SECONDS));
        assertEquals(1, replacementOpens.get());
        assertTrue(second.isRunning());
    }
    @Test(timeout = 15000) public void listenerReturnedAfterForegroundExitCannotPublish() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = release();
        FakeListener listener = new FakeListener();
        BluetoothService service = service(() -> BluetoothObject.SPP_UUID, (adapter, uuid) -> {
            entered.countDown(); awaitIgnoringInterrupt(release); return listener;
        });
        start(service); assertTrue(entered.await(5, TimeUnit.SECONDS));
        StaticObject.foregroundGate.screenUnavailable();
        release.countDown(); drainStartup();
        assertTrue(listener.closed.await(5, TimeUnit.SECONDS));
        assertEquals(0, listener.accepts.get());
        assertFalse(service.isRunning());
    }
    private BluetoothService service(BluetoothService.UuidLoader loader, BluetoothService.ListenerFactory factory) {
        BluetoothService service = new BluetoothService(loader, factory); services.add(service); return service;
    }
    private void start(BluetoothService service) {
        try { service.createService(context, null); } catch (IOException failure) { throw new AssertionError(failure); }
    }
    private CountDownLatch release() { CountDownLatch latch = new CountDownLatch(1); releases.add(latch); return latch; }
    private static void drainStartup() throws Exception {
        Field field = BluetoothService.class.getDeclaredField("SERVER_IO"); field.setAccessible(true);
        ((ScheduledThreadPoolExecutor) field.get(null)).submit(() -> { }).get(5, TimeUnit.SECONDS);
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static final class FakeListener implements BluetoothService.Listener {
        final AtomicInteger accepts = new AtomicInteger();
        final AtomicBoolean closing = new AtomicBoolean();
        final CountDownLatch acceptStarted = new CountDownLatch(1), closeEntered = new CountDownLatch(1);
        final CountDownLatch closingSignal = new CountDownLatch(1), closed = new CountDownLatch(1);
        CountDownLatch closeRelease;
        public BluetoothSocket accept() throws IOException {
            accepts.incrementAndGet(); acceptStarted.countDown(); awaitIgnoringInterrupt(closingSignal);
            throw new IOException("Fixture listener closed");
        }
        public void close() {
            if (!closing.compareAndSet(false, true)) return;
            closeEntered.countDown(); closingSignal.countDown();
            if (closeRelease != null) awaitIgnoringInterrupt(closeRelease);
            closed.countDown();
        }
    }
}
