package com.zzf.bluetoothsmp.ble;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.widget.EditText;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.tabs.TabLayout;
import com.zzf.bluetoothsmp.R;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BleInstrumentedTest {
    @Test public void bleScreenStartsWithoutHardwareAndPreservesInputOnRecreation() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        BleActivity activity = (BleActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(BleActivity.class.getName(), null, false);
        BleActivity recreated = null;
        try {
            instrumentation.runOnMainSync(() -> {
                assertNotNull(activity.findViewById(R.id.ble_scan));
                TabLayout tabs = activity.findViewById(R.id.ble_tabs);
                assertEquals(3, tabs.getTabCount());
                tabs.getTabAt(2).select();
                ((EditText) activity.findViewById(R.id.ble_payload)).setText("00 7F FF");
                assertFalse(activity.findViewById(R.id.ble_send).isEnabled());
                activity.recreate();
            });
            recreated = (BleActivity) monitor.waitForActivityWithTimeout(10000);
            assertNotNull("BLE screen recreated", recreated);
            instrumentation.waitForIdleSync();
            BleActivity current = recreated;
            instrumentation.runOnMainSync(() -> {
                assertEquals("00 7F FF", ((EditText) current.findViewById(R.id.ble_payload)).getText().toString());
                assertEquals(2, ((TabLayout) current.findViewById(R.id.ble_tabs)).getSelectedTabPosition());
            });
        } finally {
            BleActivity current = recreated == null ? activity : recreated;
            instrumentation.runOnMainSync(current::finish);
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test public void exportReadinessMeansClosedCompleteSnapshotAndSurvivesSavedState() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Application application = (Application) instrumentation.getTargetContext().getApplicationContext();
        SavedStateHandle saved = new SavedStateHandle();
        AtomicReference<BleExportState> model = new AtomicReference<>();
        ViewModelStore store = new ViewModelStore();
        BleLogStore logs = new BleLogStore();
        byte[] payload = new byte[2048];
        java.util.Arrays.fill(payload, (byte) 0xA5);
        logs.add("NOTIFY", payload);
        CountDownLatch ready = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            BleExportState state = new BleExportState(application, saved);
            model.set(state);
            store.put("export", state);
            state.listen(() -> { if (state.ready()) ready.countDown(); });
            state.prepare(logs.snapshot(), 0, "02:00:00:00:00:01");
        });
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            File snapshot = new File((String) saved.get("path"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(snapshot)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(text.startsWith("# BLE 02:00:00:00:00:01"));
            assertEquals(2048, text.split("A5", -1).length - 1);
            assertTrue(text.endsWith("A5\n"));
            instrumentation.runOnMainSync(() -> {
                java.util.Map<String, Object> restoredValues = new java.util.HashMap<>();
                for (String key : saved.keys()) {
                    Object value = saved.get(key);
                    assertTrue(value instanceof Boolean || value instanceof String);
                    restoredValues.put(key, value);
                }
                BleExportState restored = new BleExportState(application, new SavedStateHandle(restoredValues));
                store.put("restored", restored);
                assertTrue(restored.ready());
                restored.claim();
                assertFalse(restored.ready());
                assertTrue(restored.busy());
                restored.write(null);
                assertFalse(restored.busy());
            });
        } finally { instrumentation.runOnMainSync(store::clear); }
    }
}
