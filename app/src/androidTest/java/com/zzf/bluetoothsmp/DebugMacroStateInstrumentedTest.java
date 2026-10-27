package com.zzf.bluetoothsmp;

import android.os.Looper;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.zzf.bluetoothsmp.entity.CommandMacroEntity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DebugMacroStateInstrumentedTest {
    private static final String A = "02:00:00:00:DD:01", B = "02:00:00:00:DD:02";

    @Test public void deviceRefreshesCoalesceAndBusyMutationsCannotAccumulate() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), ready = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<DebugMacroState.Result> last = new AtomicReference<>();
        DebugMacroState model = new DebugMacroState(new FakeStorage() {
            @Override public List<CommandMacroEntity> load(String address) {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
                if (reads.incrementAndGet() == 1) { started.countDown(); await(release); }
                return Collections.singletonList(macro(address, address));
            }
        });
        ViewModelStore store = new ViewModelStore(); store.put("macros", model);
        Observer<DebugMacroState.Result> observer = result -> {
            if (result != null && !result.macros.isEmpty()) { last.set(result); ready.countDown(); }
        };
        try {
            onMain(() -> { model.results().observeForever(observer); model.load(A); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            onMain(() -> {
                assertTrue(model.isBusy());
                assertFalse(model.save(A, "rejected", "TEXT rejected", 1));
                assertFalse(model.delete(A, macro(A, "rejected")));
                model.load(B); model.load(A); model.load(B);
            });
            release.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(B, last.get().address);
            assertEquals(B, last.get().macros.get(0).getBluetoothAddress());
            assertEquals(2, reads.get());
            onMain(() -> assertFalse(model.isBusy()));
        } finally {
            release.countDown();
            onMain(() -> { model.results().removeObserver(observer); store.clear(); });
        }
    }

    @Test public void acceptedWriteKeepsItsTargetAndRefreshesAfterObserverRecreation() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), ready = new CountDownLatch(1);
        AtomicReference<String> savedTarget = new AtomicReference<>();
        AtomicInteger writes = new AtomicInteger(), reads = new AtomicInteger();
        DebugMacroState model = new DebugMacroState(new FakeStorage() {
            @Override public void save(String address, String name, String script, int repeat) {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
                writes.incrementAndGet(); savedTarget.set(address); started.countDown(); await(release);
            }
            @Override public List<CommandMacroEntity> load(String address) {
                reads.incrementAndGet();
                return Collections.singletonList(macro(address, "persisted"));
            }
        });
        ViewModelStore store = new ViewModelStore(); store.put("macros", model);
        Observer<DebugMacroState.Result> oldView = ignored -> { };
        Observer<DebugMacroState.Result> newView = result -> {
            if (result != null && !result.macros.isEmpty()) ready.countDown();
        };
        try {
            onMain(() -> {
                model.results().observeForever(oldView);
                assertTrue(model.save(A, "persisted", "TEXT persisted", 1));
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            onMain(() -> {
                model.results().removeObserver(oldView);
                model.load(null); // old view is destroyed while the write still owns its original target.
                model.results().observeForever(newView);
                model.load(B); model.load(A);
                assertFalse(model.save(B, "rejected", "TEXT rejected", 1));
            });
            release.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(A, savedTarget.get());
            assertEquals(1, writes.get());
            assertEquals(2, reads.get());
            onMain(() -> {
                assertFalse(model.isBusy());
                assertEquals(A, model.results().getValue().address);
                assertEquals("persisted", model.results().getValue().macros.get(0).getName());
            });
        } finally {
            release.countDown();
            onMain(() -> { model.results().removeObserver(oldView); model.results().removeObserver(newView); store.clear(); });
        }
    }

    @Test public void storageFailureIsReportedOnlyAfterCompletionAndConsumedOnce() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger feedback = new AtomicInteger();
        DebugMacroState model = new DebugMacroState(new FakeStorage() {
            @Override public List<CommandMacroEntity> load(String address) { throw new IllegalStateException("fixture"); }
        });
        ViewModelStore store = new ViewModelStore(); store.put("macros", model);
        Observer<DebugMacroState.Result> observer = result -> {
            int resource = model.consumeFeedback(result.generation);
            if (resource != 0) {
                feedback.set(resource); assertFalse(model.isBusy());
                assertEquals(0, model.consumeFeedback(result.generation)); ready.countDown();
            }
        };
        try {
            onMain(() -> { model.results().observeForever(observer); model.load(A); });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(R.string.macro_storage_failed, feedback.get());
        } finally { onMain(() -> { model.results().removeObserver(observer); store.clear(); }); }
    }

    @Test public void sameDeviceRefreshAfterRotationKeepsFailedMutationFeedbackExactlyOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), ready = new CountDownLatch(1);
        AtomicInteger feedbackCount = new AtomicInteger();
        DebugMacroState model = new DebugMacroState(new FakeStorage() {
            @Override public void save(String address, String name, String script, int repeat) {
                started.countDown(); await(release);
                throw new IllegalStateException("fixture mutation failed");
            }
        });
        ViewModelStore store = new ViewModelStore(); store.put("macros", model);
        Observer<DebugMacroState.Result> observer = result -> {
            int feedback = model.consumeFeedback(result.generation);
            if (feedback != 0) {
                assertEquals(A, result.address);
                assertEquals(R.string.macro_storage_failed, feedback);
                assertEquals(0, model.consumeFeedback(result.generation));
                feedbackCount.incrementAndGet(); ready.countDown();
            }
        };
        try {
            onMain(() -> {
                model.results().observeForever(observer);
                assertTrue(model.save(A, "failed", "TEXT failed", 1));
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            onMain(() -> {
                model.results().removeObserver(observer);
                model.load(null);
                model.results().observeForever(observer);
                model.load(A); model.load(A);
                assertEquals(0, feedbackCount.get());
            });
            release.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            onMain(() -> {
                assertEquals(1, feedbackCount.get());
                model.load(A);
                assertEquals(0, model.consumeFeedback(model.results().getValue().generation));
            });
        } finally {
            release.countDown();
            onMain(() -> { model.results().removeObserver(observer); store.clear(); });
        }
    }

    private static class FakeStorage implements DebugMacroState.Storage {
        @Override public List<CommandMacroEntity> load(String address) { return Collections.emptyList(); }
        @Override public void save(String address, String name, String script, int repeat) { fail("Unexpected save"); }
        @Override public void delete(CommandMacroEntity macro) { fail("Unexpected delete"); }
    }
    private static CommandMacroEntity macro(String address, String name) {
        CommandMacroEntity item = new CommandMacroEntity(); item.setBluetoothAddress(address); item.setName(name); return item;
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
