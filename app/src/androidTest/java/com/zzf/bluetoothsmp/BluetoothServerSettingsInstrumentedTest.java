package com.zzf.bluetoothsmp;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exercises the actual persistent gate without requiring a Bluetooth peripheral. */
@RunWith(AndroidJUnit4.class)
public class BluetoothServerSettingsInstrumentedTest {
    private static final String TEST_PREFERENCES = "bluetooth_server_regression_test";
    private static final String TEST_ADDRESS = "02:00:00:00:00:FD";
    private Context targetContext;
    private Context settingsContext;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = targetContext.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        settingsContext = newSettingsContext();
        StaticObject.foregroundGate.activityStarted(true);
        StaticObject.stopBluetoothService();
    }

    @After
    public void tearDown() {
        StaticObject.stopBluetoothService();
        StaticObject.foregroundGate.activityStopped(false);
        StaticObject.bluetoothSocketMap.remove(TEST_ADDRESS);
        assertTrue(preferences.edit().clear().commit());
    }

    @Test
    public void existingInstallationsKeepDefaultEnabledIntent() {
        assertTrue(BluetoothServerSettings.isEnabled(settingsContext));
    }

    @Test
    public void disabledIntentBlocksForegroundAndRecreatedProcessOwnerStartup() throws Exception {
        StaticObject.setBluetoothServerEnabled(settingsContext, false);
        // A null adapter is intentional: the disabled gate must return before
        // creating a listener or reaching any Bluetooth APIs.
        assertNull(StaticObject.ensureBluetoothService(settingsContext, null));
        StaticObject.stopBluetoothService();
        Context recreatedContext = newSettingsContext();
        assertFalse(BluetoothServerSettings.isEnabled(recreatedContext));
        assertNull(StaticObject.ensureBluetoothService(recreatedContext, null));
    }

    @Test
    public void uuidRestartDoesNotReenableDisabledServer() throws Exception {
        StaticObject.setBluetoothServerEnabled(settingsContext, false);
        assertNull(StaticObject.restartBluetoothService(settingsContext, null));
        assertFalse(BluetoothServerSettings.isEnabled(newSettingsContext()));
    }

    @Test
    public void stoppingListenerDoesNotCloseExistingClientSession() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        BluetoothServiceConnect session = new BluetoothServiceConnect() {
            @Override
            public void closeForLifecycle() {
                closed.set(true);
            }
        };
        StaticObject.bluetoothSocketMap.put(TEST_ADDRESS, session);
        StaticObject.setBluetoothServerEnabled(settingsContext, false);
        StaticObject.restartBluetoothService(settingsContext, null);
        assertSame(session, StaticObject.bluetoothSocketMap.get(TEST_ADDRESS));
        assertFalse(closed.get());
    }

    @Test
    public void explicitEnableAndTemporaryStartFailureRetainEnabledIntent() {
        StaticObject.setBluetoothServerEnabled(settingsContext, false);
        StaticObject.setBluetoothServerEnabled(settingsContext, true);
        try {
            StaticObject.ensureBluetoothService(settingsContext, null);
            fail("An enabled listener with no adapter must report an initialization failure");
        } catch (IOException expected) {
            // A transient runtime failure must not overwrite the user's switch.
        }
        StaticObject.stopBluetoothService();
        assertTrue(BluetoothServerSettings.isEnabled(newSettingsContext()));
    }

    private Context newSettingsContext() {
        return new ContextWrapper(targetContext) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                // Use real Android preferences while isolating the user's server switch.
                return targetContext.getSharedPreferences(TEST_PREFERENCES, mode);
            }
        };
    }
}
