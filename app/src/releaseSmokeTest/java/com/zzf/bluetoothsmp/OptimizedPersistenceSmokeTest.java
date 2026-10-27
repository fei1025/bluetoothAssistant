package com.zzf.bluetoothsmp;

import android.content.Context;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.litepal.LitePal;
import org.litepal.crud.LitePalSupport;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.InputStream;

import static org.junit.Assert.*;

/** Runs against the minified APK: XML-loaded model names, schema columns and reflection roundtrip. */
@RunWith(AndroidJUnit4.class)
public class OptimizedPersistenceSmokeTest {
    @Test public void debugViewModelsCreateAndRestoreThroughTheOptimizedActivity() throws Exception {
        // Operate through framework APIs: optimized AndroidX implementation types
        // may be merged/removed, so referencing their unoptimized test types is invalid.
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Application application = (Application) instrumentation.getTargetContext().getApplicationContext();
        AtomicReference<Activity> current = new AtomicReference<>();
        ArrayList<Activity> created = new ArrayList<>();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { created.add(activity); }
            @Override public void onActivityResumed(Activity activity) { current.set(activity); }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        instrumentation.runOnMainSync(() -> application.registerActivityLifecycleCallbacks(callbacks));
        Instrumentation.ActivityMonitor discoverable = instrumentation.addMonitor(
                new IntentFilter(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE),
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                grant(instrumentation, application, "android.permission.BLUETOOTH_CONNECT");
                grant(instrumentation, application, "android.permission.BLUETOOTH_SCAN");
                grant(instrumentation, application, "android.permission.BLUETOOTH_ADVERTISE");
            } else grant(instrumentation, application, "android.permission.ACCESS_FINE_LOCATION");
            Class<?> driveType = Class.forName("com.zzf.bluetoothsmp.entity.BluetoothDrive");
            Object drive = driveType.getDeclaredConstructor().newInstance();
            driveType.getMethod("setDriveAdd", String.class).invoke(drive, "02:00:00:00:00:CE");
            driveType.getMethod("setDriveName", String.class).invoke(drive, "Release UI fixture");
            driveType.getMethod("setUuid", String.class).invoke(drive, "release-ui-fixture");
            instrumentation.startActivitySync(new Intent(Intent.ACTION_VIEW)
                    .setClassName(application, "com.zzf.bluetoothsmp.liaoTian.Liantian_new")
                    .putExtra("BluetoothDrive", (java.io.Serializable) drive).putExtra("infoType", "0")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            instrumentation.waitForIdleSync();
            awaitView(instrumentation, current, "tabs", null);
            instrumentation.runOnMainSync(() -> {
                Activity activity = current.get();
                int id = activity.getResources().getIdentifier("tabs", "id", activity.getPackageName());
                android.view.ViewGroup tabs = (android.view.ViewGroup) activity.findViewById(id);
                android.view.ViewGroup items = (android.view.ViewGroup) tabs.getChildAt(0);
                assertEquals(3, items.getChildCount());
                assertTrue(items.getChildAt(2).performClick());
            });
            awaitView(instrumentation, current, "debug_device_selector", null);
            Activity original = current.get();
            instrumentation.runOnMainSync(original::recreate);
            awaitView(instrumentation, current, "debug_device_selector", original);
            assertNotSame(original, current.get());
        } finally {
            instrumentation.runOnMainSync(() -> {
                for (Activity activity : new ArrayList<>(created)) {
                    if (!activity.isDestroyed()) activity.finishAndRemoveTask();
                }
                application.unregisterActivityLifecycleCallbacks(callbacks);
            });
            instrumentation.removeMonitor(discoverable);
        }
    }

    private static void grant(Instrumentation instrumentation, Application application, String permission) throws Exception {
        ParcelFileDescriptor result = instrumentation.getUiAutomation().executeShellCommand(
                "pm grant " + application.getPackageName() + " " + permission);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(result)) {
            while (input.read() != -1) { }
        }
    }

    private static void awaitView(Instrumentation instrumentation, AtomicReference<Activity> current,
                                  String resource, Activity previous) {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            instrumentation.runOnMainSync(() -> {
                Activity activity = current.get();
                if (activity == null || activity == previous || activity.isDestroyed()) return;
                int id = activity.getResources().getIdentifier(resource, "id", activity.getPackageName());
                ready.set(id != 0 && activity.findViewById(id) != null);
            });
            if (ready.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("Optimized activity did not restore " + resource, ready.get());
    }

    @Test public void persistedSchemaSurvivesR8() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LitePal.initialize(context);
        SQLiteDatabase database = LitePal.getDatabase();
        assertColumns(database, "keyboardentity", "buttonid", "driveadd", "actiondown", "actionup");
        assertColumns(database, "messagemapper", "message", "receiveadd", "sendadd", "payloadbase64", "messageid");
        assertColumns(database, "systeminfomapper", "servicespp", "clientspp");

        Class<?> type = Class.forName("com.zzf.bluetoothsmp.entity.SystemInfoMapper");
        Field field = type.getDeclaredField("serviceSpp");
        field.setAccessible(true);
        String marker = "offline-schema-" + UUID.randomUUID();
        LitePalSupport model = (LitePalSupport) type.getDeclaredConstructor().newInstance();
        field.set(model, marker);
        assertTrue(model.save());
        try {
            List<?> rows = LitePal.where("serviceSpp = ?", marker).find(type);
            assertEquals(1, rows.size());
            assertEquals(marker, field.get(rows.get(0)));
        } finally {
            LitePal.deleteAll(type, "serviceSpp = ?", marker);
        }

        Class<?> messageType = Class.forName("com.zzf.bluetoothsmp.entity.MessageMapper");
        LitePalSupport message = (LitePalSupport) messageType.getDeclaredConstructor().newInstance();
        String messageMarker = "offline-raw-" + UUID.randomUUID();
        messageType.getDeclaredMethod("setMessageId", String.class).invoke(message, messageMarker);
        byte[] raw = new byte[]{0, (byte) 0xFF};
        messageType.getDeclaredMethod("setPayload", byte[].class).invoke(message, (Object) raw);
        assertTrue(message.save());
        try {
            List<?> rows = LitePal.where("messageId = ?", messageMarker).find(messageType);
            assertEquals(1, rows.size());
            assertArrayEquals(raw, (byte[]) messageType.getDeclaredMethod("getPayload").invoke(rows.get(0)));
        } finally {
            LitePal.deleteAll(messageType, "messageId = ?", messageMarker);
        }
    }

    private static void assertColumns(SQLiteDatabase database, String table, String... expected) {
        Set<String> columns = new HashSet<>();
        try (Cursor cursor = database.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
        }
        for (String column : expected) assertTrue(table + " lost column " + column, columns.contains(column));
    }
}
