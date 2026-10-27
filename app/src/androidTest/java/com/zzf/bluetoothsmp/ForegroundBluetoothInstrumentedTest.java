package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises runtime teardown with a fake transport, without a Bluetooth peripheral. */
@RunWith(AndroidJUnit4.class)
public class ForegroundBluetoothInstrumentedTest {
    private static final String TEST_ADDRESS = "02:00:00:00:00:FC";
    private Context isolatedContext;
    private SharedPreferences preferences;
    private boolean activityStarted;

    @Before
    public void setUp() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = target.getSharedPreferences("foreground_runtime_regression_test", Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        isolatedContext = new ContextWrapper(target) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return preferences;
            }

            @Override
            public Context getApplicationContext() {
                return this;
            }
        };
        StaticObject.foregroundGate.activityStarted(true);
        activityStarted = true;
    }

    @After
    public void tearDown() {
        if (activityStarted) StaticObject.foregroundGate.activityStopped(false);
        StaticObject.bluetoothSocketMap.remove(TEST_ADDRESS);
        StaticObject.connectionRegistry.clear();
        assertTrue(preferences.edit().clear().commit());
    }

    @Test
    public void backgroundSuspensionClosesSessionAndRejectsLatePublication() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        BluetoothServiceConnect session = new BluetoothServiceConnect() {
            @Override
            public void closeForLifecycle() {
                closed.incrementAndGet();
            }
        };
        BluetoothServerSettings.setEnabled(isolatedContext, true);
        long oldGeneration = StaticObject.foregroundGate.currentGeneration();
        StaticObject.bluetoothSocketMap.put(TEST_ADDRESS, session);
        StaticObject.foregroundGate.activityStopped(false);
        activityStarted = false;
        StaticObject.suspendForegroundBluetooth(isolatedContext);
        assertEquals(1, closed.get());
        assertTrue(StaticObject.bluetoothSocketMap.isEmpty());
        assertTrue(BluetoothServerSettings.isEnabled(isolatedContext));
        assertNull(StaticObject.ensureBluetoothService(isolatedContext, null));
        assertFalse(StaticObject.foregroundGate.runIfCurrent(oldGeneration,
                () -> StaticObject.bluetoothSocketMap.put(TEST_ADDRESS, session)));

        StaticObject.foregroundGate.activityStarted(true);
        activityStarted = true;
        assertFalse(StaticObject.foregroundGate.runIfCurrent(oldGeneration,
                () -> StaticObject.bluetoothSocketMap.put(TEST_ADDRESS, session)));
        long newGeneration = StaticObject.foregroundGate.currentGeneration();
        assertTrue(StaticObject.foregroundGate.runIfCurrent(newGeneration,
                () -> StaticObject.bluetoothSocketMap.put(TEST_ADDRESS, session)));
        assertSame(session, StaticObject.bluetoothSocketMap.get(TEST_ADDRESS));
    }

    @Test
    public void cancelingReconnectRuntimeKeepsPersistedReconnectIntent() {
        assertTrue(preferences.edit()
                .putBoolean("global_enabled", true)
                .putBoolean("enabled." + TEST_ADDRESS, true)
                .putString("uuid." + TEST_ADDRESS, "00001101-0000-1000-8000-00805F9B34FB")
                .commit());
        Map<String, ?> before = preferences.getAll();
        BluetoothReconnectManager isolatedReconnect = new BluetoothReconnectManager();
        isolatedReconnect.initialize(isolatedContext);
        try {
            isolatedReconnect.onUnexpectedDisconnect(TEST_ADDRESS);
            assertEquals(BluetoothConnectionState.RECONNECTING,
                    StaticObject.connectionRegistry.get(TEST_ADDRESS));
            isolatedReconnect.cancelAll();
            assertEquals(before, preferences.getAll());
            assertTrue(isolatedReconnect.isGlobalEnabled());
            assertTrue(isolatedReconnect.isDeviceReconnectEnabled(TEST_ADDRESS));
        } finally {
            isolatedReconnect.cancelAll();
        }
    }

    @Test
    public void oldReceiveFailureCannotScheduleReconnectInANewForegroundGeneration() {
        assertTrue(preferences.edit()
                .putBoolean("global_enabled", true)
                .putBoolean("enabled." + TEST_ADDRESS, true)
                .putString("uuid." + TEST_ADDRESS, "00001101-0000-1000-8000-00805F9B34FB")
                .commit());
        BluetoothReconnectManager isolatedReconnect = new BluetoothReconnectManager();
        isolatedReconnect.initialize(isolatedContext);
        long oldGeneration = StaticObject.foregroundGate.currentGeneration();
        StaticObject.foregroundGate.activityStopped(false);
        StaticObject.foregroundGate.activityStarted(true);
        StaticObject.connectionRegistry.set(TEST_ADDRESS, BluetoothConnectionState.CONNECTED);
        try {
            isolatedReconnect.onUnexpectedDisconnect(TEST_ADDRESS, oldGeneration);
            assertEquals(BluetoothConnectionState.CONNECTED,
                    StaticObject.connectionRegistry.get(TEST_ADDRESS));
            isolatedReconnect.onUnexpectedDisconnect(TEST_ADDRESS,
                    StaticObject.foregroundGate.currentGeneration());
            assertEquals(BluetoothConnectionState.RECONNECTING,
                    StaticObject.connectionRegistry.get(TEST_ADDRESS));
        } finally {
            isolatedReconnect.cancelAll();
        }
    }
}
