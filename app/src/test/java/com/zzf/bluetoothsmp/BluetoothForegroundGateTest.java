package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class BluetoothForegroundGateTest {
    @Test
    public void internalActivityHandoffPreservesCurrentConnectionGeneration() {
        BluetoothForegroundGate gate = new BluetoothForegroundGate();
        gate.activityStarted(true); // MainActivity.
        long generation = gate.currentGeneration();
        gate.activityStarted(true); // Chat starts before MainActivity stops.
        gate.activityStopped(false);
        assertTrue(gate.isActive());
        assertEquals(generation, gate.currentGeneration());
        assertTrue(gate.isCurrent(generation));
        gate.activityStopped(false); // App leaves the foreground.
        assertFalse(gate.isActive());
        assertEquals(-1L, gate.currentGeneration());
    }

    @Test
    public void configurationRecreationPreservesGenerationUntilReplacementStops() {
        BluetoothForegroundGate gate = new BluetoothForegroundGate();
        gate.activityStarted(true);
        long generation = gate.currentGeneration();
        gate.activityStopped(true);
        assertTrue(gate.isCurrent(generation));
        gate.activityStarted(true);
        assertEquals(generation, gate.currentGeneration());
        gate.activityStopped(false);
        assertFalse(gate.isCurrent(generation));
    }

    @Test
    public void foregroundReturnRejectsAnOldAsyncConnectionAndAllowsNewOne() {
        BluetoothForegroundGate gate = new BluetoothForegroundGate();
        AtomicInteger published = new AtomicInteger();
        assertFalse(gate.isActive());
        assertEquals(-1L, gate.currentGeneration());
        assertFalse(gate.runIfCurrent(-1L, published::incrementAndGet));
        gate.activityStarted(true);
        long stale = gate.currentGeneration();
        gate.activityStopped(false);
        gate.activityStarted(true);
        long current = gate.currentGeneration();
        assertTrue(current != stale);
        assertFalse(gate.runIfCurrent(stale, published::incrementAndGet));
        assertTrue(gate.runIfCurrent(current, published::incrementAndGet));
        assertEquals(1, published.get());
    }

    @Test
    public void lockScreenInvalidatesWorkAndUnlockRequiresAStartedActivity() {
        BluetoothForegroundGate gate = new BluetoothForegroundGate();
        gate.activityStarted(true);
        long beforeLock = gate.currentGeneration();
        gate.screenUnavailable();
        assertFalse(gate.isActive());
        assertFalse(gate.isCurrent(beforeLock));
        gate.screenAvailable();
        assertTrue(gate.isActive());
        assertFalse(gate.isCurrent(beforeLock));
        gate.activityStopped(false);
        gate.screenAvailable(); // SCREEN_ON while the app remains backgrounded.
        assertFalse(gate.isActive());
        gate.activityStarted(false); // Activity is started but screen is still locked.
        assertFalse(gate.isActive());
        gate.screenAvailable();
        assertTrue(gate.isActive());
    }

    @Test
    public void backgroundInvalidationCannotRacePastAnInProgressPublication() throws Exception {
        BluetoothForegroundGate gate = new BluetoothForegroundGate();
        gate.activityStarted(true);
        long generation = gate.currentGeneration();
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        CountDownLatch stopAttempted = new CountDownLatch(1);
        CountDownLatch stopFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger published = new AtomicInteger();
        Thread publisher = new Thread(() -> {
            try {
                assertTrue(gate.runIfCurrent(generation, () -> {
                    publishing.countDown();
                    await(releasePublication);
                    published.incrementAndGet();
                }));
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "foreground-publication-test");
        Thread stopper = new Thread(() -> {
            stopAttempted.countDown();
            gate.activityStopped(false);
            stopFinished.countDown();
        }, "foreground-stop-test");
        try {
            publisher.start();
            assertTrue(publishing.await(2, TimeUnit.SECONDS));
            stopper.start();
            assertTrue(stopAttempted.await(2, TimeUnit.SECONDS));
            assertFalse(stopFinished.await(150, TimeUnit.MILLISECONDS));
            releasePublication.countDown();
            assertTrue(stopFinished.await(2, TimeUnit.SECONDS));
            publisher.join(2000);
            stopper.join(2000);
            assertFalse(publisher.isAlive());
            assertFalse(stopper.isAlive());
            assertEquals(null, failure.get());
            assertEquals(1, published.get());
            assertFalse(gate.isCurrent(generation));
            assertFalse(gate.runIfCurrent(generation, published::incrementAndGet));
            assertEquals(1, published.get());
        } finally {
            releasePublication.countDown();
            publisher.join(2000);
            stopper.join(2000);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Publication was not released");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
