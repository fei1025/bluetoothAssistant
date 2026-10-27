package com.zzf.bluetoothsmp;

import android.content.Context;
import android.net.Uri;
import androidx.lifecycle.Observer;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.zzf.bluetoothsmp.entity.LogItem;
import com.zzf.bluetoothsmp.entity.CommandMacroEntity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.litepal.LitePal;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DebugTransferStateInstrumentedTest {
    @Test public void macroImportRollsBackAllRowsWhenOneDatabaseWriteFails() {
        String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
        String firstName = "debug-import-" + nonce + "-first", failedName = "debug-import-" + nonce + "-failed";
        String trigger = "debug_import_failure_" + nonce;
        String address = "02:00:00:00:00:FC";
        android.database.sqlite.SQLiteDatabase database = LitePal.getDatabase();
        CommandMacroEntity first = new CommandMacroEntity(); first.setName(firstName); first.setScript("TEXT first");
        CommandMacroEntity second = new CommandMacroEntity(); second.setName(failedName); second.setScript("TEXT second");
        database.execSQL("CREATE TEMP TRIGGER " + trigger + " BEFORE INSERT ON commandmacroentity WHEN NEW.name = '"
                + failedName + "' BEGIN SELECT RAISE(ABORT, 'offline macro import failure'); END");
        try {
            try {
                CommandMacroStore.importForAddress(address, Arrays.asList(first, second));
                fail("The failing write must abort the import");
            } catch (RuntimeException expected) { }
            assertNull(CommandMacroStore.findByName(address, firstName));
            assertNull(CommandMacroStore.findByName(address, failedName));
        } finally {
            database.execSQL("DROP TRIGGER IF EXISTS " + trigger);
            LitePal.deleteAll(CommandMacroEntity.class, "name = ? or name = ?", firstName, failedName);
        }
    }

    @Test public void readySnapshotIsClosedCompleteAndRestorableWithoutBundlePayload() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        byte[] bytes = new byte[64 * 1024]; Arrays.fill(bytes, (byte) 'A');
        LogItem item = new LogItem("preview only", bytes, LogItem.TYPE_RECEIVED, "02:00:00:00:00:01");
        item.setTextEncoding(BluetoothTextEncoding.UTF_8);
        SavedStateHandle saved = new SavedStateHandle();
        DebugTransferState model = new DebugTransferState(saved);
        ViewModelStore store = new ViewModelStore(); store.put("transfer", model);
        CountDownLatch ready = new CountDownLatch(1);
        Observer<String> observer = slot -> { if ("log".equals(slot)) ready.countDown(); };
        onMain(() -> {
            model.readyExports().observeForever(observer);
            assertTrue(model.prepareLogs(context, "test.txt", Collections.singletonList(item), "test", "device"));
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        AtomicReference<String> path = new AtomicReference<>();
        Map<String, Object> restored = new HashMap<>();
        onMain(() -> {
            path.set(saved.get("log_path"));
            assertTrue(model.markPickerLaunched("log"));
            for (String key : saved.keys()) {
                Object value = saved.get(key);
                assertTrue(value == null || value instanceof String); restored.put(key, value);
            }
            model.readyExports().removeObserver(observer); store.clear();
        });
        String snapshot = read(new File(path.get()));
        assertTrue(snapshot.contains("TEXT: " + new String(bytes, StandardCharsets.UTF_8)));
        DebugTransferState resumed = new DebugTransferState(new SavedStateHandle(restored));
        ViewModelStore resumedStore = new ViewModelStore(); resumedStore.put("transfer", resumed);
        File destination = File.createTempFile("debug-transfer-test-", ".txt", context.getCacheDir());
        CountDownLatch written = new CountDownLatch(1);
        Observer<android.os.Bundle> feedback = result -> {
            if (result != null && result.getInt("resource") == R.string.log_exported) written.countDown();
        };
        try {
            onMain(() -> {
                resumed.feedback().observeForever(feedback);
                resumed.writeExport(context, Uri.fromFile(destination), "log");
                resumed.writeExport(context, Uri.fromFile(destination), "log");
                // Busy rejection must not unlock the first write before its completion post.
                assertFalse(resumed.beginImport("02:00:00:00:00:02"));
            });
            assertTrue(written.await(5, TimeUnit.SECONDS));
            assertEquals(snapshot, read(destination));
        } finally {
            onMain(() -> { resumed.feedback().removeObserver(feedback); resumedStore.clear(); });
            destination.delete(); new File(path.get()).delete();
        }
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private static String read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
