package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.navigation.Navigation;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses real Activity callbacks; the transport itself is deliberately a test double. */
@RunWith(AndroidJUnit4.class)
public class ForegroundActivityHandoffInstrumentedTest {
    private static final String SESSION_ADDRESS = "02:00:00:00:00:FB";
    private Instrumentation instrumentation;
    private Instrumentation.ActivityMonitor discoveryPrompt;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grant(Manifest.permission.BLUETOOTH_CONNECT);
            grant(Manifest.permission.BLUETOOTH_SCAN);
            grant(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            grant(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        // The test exercises app transitions, so resolve the external discovery
        // request immediately; manual device testing covers that system dialog.
        discoveryPrompt = instrumentation.addMonitor(
                new IntentFilter(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE),
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
    }

    @After
    public void tearDown() {
        instrumentation.removeMonitor(discoveryPrompt);
        StaticObject.bluetoothSocketMap.remove(SESSION_ADDRESS);
    }

    @Test
    public void mainChatMainHandoffKeepsExistingSessionAndGeneration() {
        AtomicInteger closed = new AtomicInteger();
        BluetoothServiceConnect session = session(closed);
        MainActivity main = launchMain();
        try {
            assertTrue(StaticObject.foregroundGate.isActive());
            long generation = StaticObject.foregroundGate.currentGeneration();
            StaticObject.bluetoothSocketMap.put(SESSION_ADDRESS, session);
            BluetoothDrive drive = new BluetoothDrive();
            drive.setDriveAdd("02:00:00:00:00:FA");
            drive.setDriveName("Lifecycle regression fixture");
            drive.setUuid("foreground-activity-regression");
            Instrumentation.ActivityMonitor chatMonitor = instrumentation.addMonitor(
                    Liantian_new.class.getName(), null, false);
            Activity chat = null;
            try {
                instrumentation.runOnMainSync(() -> main.startActivity(new Intent(main, Liantian_new.class)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra("BluetoothDrive", drive).putExtra("infoType", "0")));
                chat = instrumentation.waitForMonitorWithTimeout(chatMonitor, 5000);
                assertNotNull(chat);
                instrumentation.waitForIdleSync();
                assertEquals(generation, StaticObject.foregroundGate.currentGeneration());
                assertSame(session, StaticObject.bluetoothSocketMap.get(SESSION_ADDRESS));
                assertEquals(0, closed.get());
                Activity finishingChat = chat;
                instrumentation.runOnMainSync(finishingChat::finish);
                awaitOnMain(() -> main.getLifecycle().getCurrentState()
                        == androidx.lifecycle.Lifecycle.State.RESUMED);
                instrumentation.runOnMainSync(() -> assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED,
                        main.getLifecycle().getCurrentState()));
                assertEquals(generation, StaticObject.foregroundGate.currentGeneration());
                assertSame(session, StaticObject.bluetoothSocketMap.get(SESSION_ADDRESS));
                assertEquals(0, closed.get());
            } finally {
                if (chat != null) {
                    Activity finishingChat = chat;
                    instrumentation.runOnMainSync(finishingChat::finish);
                }
                instrumentation.removeMonitor(chatMonitor);
            }
        } finally {
            finishTask(main);
        }
    }

    @Test
    public void serviceConfigurationRecreationKeepsSessionAndSelectedPage() {
        AtomicInteger closed = new AtomicInteger();
        BluetoothServiceConnect session = session(closed);
        MainActivity main = launchMain();
        Activity replacement = null;
        try {
            instrumentation.runOnMainSync(() -> Navigation.findNavController(main,
                    R.id.nav_host_fragment_activity_home).navigate(R.id.navigation_service));
            instrumentation.waitForIdleSync();
            long generation = StaticObject.foregroundGate.currentGeneration();
            StaticObject.bluetoothSocketMap.put(SESSION_ADDRESS, session);
            instrumentation.runOnMainSync(main::recreate);
            replacement = awaitReplacement(main);
            assertNotNull(replacement);
            assertTrue(replacement != main);
            instrumentation.waitForIdleSync();
            Activity recreated = replacement;
            instrumentation.runOnMainSync(() -> assertEquals(R.id.navigation_service,
                    Navigation.findNavController(recreated, R.id.nav_host_fragment_activity_home)
                            .getCurrentDestination().getId()));
            assertEquals(generation, StaticObject.foregroundGate.currentGeneration());
            assertSame(session, StaticObject.bluetoothSocketMap.get(SESSION_ADDRESS));
            assertEquals(0, closed.get());
        } finally {
            Activity finishing = replacement == null ? main : replacement;
            finishTask(finishing);
        }
    }

    private MainActivity launchMain() {
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        final MainActivity[] current = new MainActivity[1];
        awaitOnMain(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (activity instanceof MainActivity) {
                    MainActivity candidate = (MainActivity) activity;
                    java.util.Locale displayed = android.os.Build.VERSION.SDK_INT >= 24
                            ? candidate.getResources().getConfiguration().getLocales().get(0)
                            : candidate.getResources().getConfiguration().locale;
                    String wanted = com.zzf.bluetoothsmp.utils.LanguageUtils.getCurrentAppLocale().getLanguage();
                    if (!candidate.isDestroyed() && displayed.getLanguage().equals(wanted)) {
                        current[0] = candidate;
                        return true;
                    }
                }
            }
            return false;
        });
        return current[0];
    }

    private void finishTask(Activity activity) {
        instrumentation.runOnMainSync(activity::finishAndRemoveTask);
        awaitOnMain(activity::isDestroyed);
        instrumentation.waitForIdleSync();
    }

    private MainActivity awaitReplacement(MainActivity original) {
        MainActivity[] replacement = new MainActivity[1];
        awaitOnMain(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (activity instanceof MainActivity && activity != original
                        && !activity.isFinishing() && !activity.isDestroyed()) {
                    replacement[0] = (MainActivity) activity;
                    return true;
                }
            }
            return false;
        }, original);
        return replacement[0];
    }

    private void awaitOnMain(ReadyCheck condition) {
        awaitOnMain(condition, null);
    }

    private void awaitOnMain(ReadyCheck condition, Activity original) {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            instrumentation.runOnMainSync(() -> ready.set(condition.isReady()));
            if (ready.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        String[] details = new String[1];
        instrumentation.runOnMainSync(() -> {
            StringBuilder description = new StringBuilder("Activity lifecycle transition did not complete; generation=")
                    .append(StaticObject.foregroundGate.currentGeneration());
            if (original != null) description.append("; original=")
                    .append(System.identityHashCode(original)).append(" destroyed=")
                    .append(original.isDestroyed()).append(" finishing=").append(original.isFinishing());
            for (Stage stage : Stage.values()) {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)) {
                    description.append("; ").append(stage).append(' ')
                            .append(activity.getClass().getSimpleName()).append('@')
                            .append(System.identityHashCode(activity)).append(" destroyed=")
                            .append(activity.isDestroyed()).append(" finishing=").append(activity.isFinishing());
                }
            }
            details[0] = description.toString();
        });
        assertTrue(details[0], ready.get());
    }

    private interface ReadyCheck {
        boolean isReady();
    }

    private BluetoothServiceConnect session(AtomicInteger closed) {
        return new BluetoothServiceConnect() {
            @Override
            public void closeForLifecycle() {
                closed.incrementAndGet();
            }
        };
    }

    private void grant(String permission) throws Exception {
        String command = "pm grant " + instrumentation.getTargetContext().getPackageName()
                + " " + permission;
        ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            while (input.read() != -1) { /* Wait for permission grant completion. */ }
        }
    }
}
