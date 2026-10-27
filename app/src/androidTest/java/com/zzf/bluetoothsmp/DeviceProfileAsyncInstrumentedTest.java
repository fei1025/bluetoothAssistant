package com.zzf.bluetoothsmp;

import static org.junit.Assert.*;

import android.os.Looper;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.zzf.bluetoothsmp.entity.BluetoothDeviceProfileEntity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.litepal.LitePal;
import org.litepal.crud.LitePalSupport;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class DeviceProfileAsyncInstrumentedTest {
    private String address;
    private String invalidAddress;

    @Before public void setup() {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(java.util.Locale.ROOT);
        address = "02:" + hex.substring(0, 2) + ":" + hex.substring(2, 4) + ":"
                + hex.substring(4, 6) + ":" + hex.substring(6, 8) + ":" + hex.substring(8, 10);
        invalidAddress = "invalid address " + UUID.randomUUID();
    }

    @After public void cleanup() throws Exception {
        // A queued load is also a barrier after this fixture's earlier writes.
        load();
        LitePal.deleteAll(BluetoothDeviceProfileEntity.class, "address = ?", address);
        LitePal.deleteAll(BluetoothDeviceProfileEntity.class, "address = ?",
                invalidAddress.toUpperCase(java.util.Locale.ROOT));
        // Clean only the exact fixture leaked by the earlier failed validation test.
        LitePal.deleteAll(BluetoothDeviceProfileEntity.class, "address = ? AND alias = ?",
                "INVALID ADDRESS", "Unsaved");
        load();
    }

    @Test public void aliasAndFavoriteClearDefaultsAndPersistOnlyOneDeviceRow() throws Exception {
        alias("  Example alias  ");
        favorite(true);
        assertEquals("Example alias", BluetoothDeviceProfileStore.findCached(address).getAlias());
        assertTrue(BluetoothDeviceProfileStore.findCached(address).isFavorite());
        alias("");
        BluetoothDeviceProfileEntity cleared = BluetoothDeviceProfileStore.find(address);
        assertNotNull(cleared);
        assertTrue("Clearing the alias did not persist: " + cleared.getAlias(),
                cleared.getAlias() == null || cleared.getAlias().isEmpty());
        assertTrue("Clearing an alias also changed the favorite", cleared.isFavorite());
        favorite(false);
        load();
        BluetoothDeviceProfileEntity saved = BluetoothDeviceProfileStore.find(address);
        assertNotNull(saved);
        assertTrue(saved.getAlias() == null || saved.getAlias().isEmpty());
        assertFalse(saved.isFavorite());
        assertEquals(1, LitePal.where("address = ?", address).count(BluetoothDeviceProfileEntity.class));
        assertFalse(BluetoothDeviceProfileStore.findCached(address).isFavorite());
    }

    @Test public void cachedDiscoveryReadDoesNotWaitForGlobalLitePalMonitor() throws Exception {
        alias("Cached alias");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread blocker = new Thread(() -> {
            synchronized (LitePalSupport.class) {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        });
        blocker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                long before = SystemClock.elapsedRealtime();
                BluetoothDeviceProfileEntity cached = BluetoothDeviceProfileStore.findCached(address);
                assertNotNull(cached);
                assertEquals("Cached alias", cached.getAlias());
                assertTrue("A cached UI lookup waited for database IO", SystemClock.elapsedRealtime() - before < 1000);
                cached.setAlias("UI-only mutation");
                assertEquals("Cached alias", BluetoothDeviceProfileStore.findCached(address).getAlias());
            });
        } finally {
            release.countDown();
            blocker.join(5000);
        }
    }

    @Test public void invalidAddressReportsMainThreadFailureWithoutChangingCommittedCache() throws Exception {
        alias("Committed");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean main = new AtomicBoolean();
        BluetoothDeviceProfileStore.updateAliasAsync(invalidAddress, "Unsaved", System.currentTimeMillis(),
                new BluetoothDeviceProfileStore.Callback<BluetoothDeviceProfileEntity>() {
            @Override public void onSuccess(BluetoothDeviceProfileEntity value) {
                error.set(new AssertionError("Invalid-address save reported success"));
                done.countDown();
            }
            @Override public void onError(Throwable failure) {
                error.set(failure);
                main.set(Looper.myLooper() == Looper.getMainLooper());
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue("Unexpected invalid-address result: " + error.get(), error.get() instanceof IllegalArgumentException);
        assertTrue(main.get());
        assertEquals("Committed", BluetoothDeviceProfileStore.findCached(address).getAlias());
        assertEquals(0, LitePal.where("address = ?", invalidAddress.toUpperCase(java.util.Locale.ROOT))
                .count(BluetoothDeviceProfileEntity.class));
    }

    private void alias(String value) throws Exception {
        Completion completion = new Completion();
        BluetoothDeviceProfileStore.updateAliasAsync(address, value, System.currentTimeMillis(), completion);
        completion.await();
    }

    private void favorite(boolean value) throws Exception {
        Completion completion = new Completion();
        BluetoothDeviceProfileStore.setFavoriteAsync(address, value, System.currentTimeMillis(), completion);
        completion.await();
    }

    private void load() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        BluetoothDeviceProfileStore.loadAsync(new BluetoothDeviceProfileStore.Callback<List<BluetoothDeviceProfileEntity>>() {
            @Override public void onSuccess(List<BluetoothDeviceProfileEntity> result) { done.countDown(); }
            @Override public void onError(Throwable failure) { error.set(failure); done.countDown(); }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private static final class Completion implements BluetoothDeviceProfileStore.Callback<BluetoothDeviceProfileEntity> {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicBoolean main = new AtomicBoolean();
        @Override public void onSuccess(BluetoothDeviceProfileEntity saved) {
            main.set(Looper.myLooper() == Looper.getMainLooper());
            done.countDown();
        }
        @Override public void onError(Throwable failure) { error.set(failure); done.countDown(); }
        void await() throws Exception {
            assertTrue(done.await(10, TimeUnit.SECONDS));
            if (error.get() != null) throw new AssertionError(error.get());
            assertTrue("Metadata callback was not delivered on main", main.get());
        }
    }
}
