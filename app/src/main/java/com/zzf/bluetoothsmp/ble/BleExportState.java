package com.zzf.bluetoothsmp.ble;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.SavedStateHandle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The picker refers to a closed cache snapshot, never to the live BLE connection. */
public final class BleExportState extends AndroidViewModel {
    private final SavedStateHandle saved;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable listener;
    private boolean busy;
    private volatile boolean cleared;
    private int result;

    public BleExportState(Application application, SavedStateHandle saved) {
        super(application);
        this.saved = saved;
        String destination = saved.get("destination");
        if (destination != null) write(Uri.parse(destination));
    }

    private File snapshotFile() throws IOException {
        String path = saved.get("path");
        if (path == null) throw new IOException("No snapshot");
        File file = new File(path).getCanonicalFile();
        if (!getApplication().getCacheDir().getCanonicalFile().equals(file.getParentFile())
                || !file.getName().startsWith("ble-export-")) throw new IOException("Invalid snapshot");
        return file;
    }
    boolean busy() { return busy || Boolean.TRUE.equals(saved.get("pending")); }
    void listen(Runnable value) { listener = value; }
    boolean ready() { return Boolean.TRUE.equals(saved.get("ready")); }
    void claim() { saved.set("ready", false); }
    int takeResult() { int value = result; result = 0; return value; }
    private void changed() { if (!cleared && listener != null) listener.run(); }

    void prepare(List<BleLogStore.Entry> entries, long dropped, String address) {
        if (busy()) return;
        final String oldPath = saved.get("path");
        File snapshot = new File(getApplication().getCacheDir(), "ble-export-" + java.util.UUID.randomUUID() + ".tsv");
        saved.set("path", snapshot.getAbsolutePath());
        busy = true;
        changed();
        io.execute(() -> {
            if (oldPath != null) deleteSnapshot(oldPath);
            boolean success = false;
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(snapshot), StandardCharsets.UTF_8))) {
                writer.write("# BLE " + address + "; retained entries <=512 / <=1MiB; dropped=" + dropped + "\n");
                writer.write("time_epoch_ms\tevent\thex\n");
                for (BleLogStore.Entry entry : entries) writer.write(entry.time + "\t" + entry.label + "\t" + entry.hex() + "\n");
                success = true;
            } catch (IOException failure) { success = false; }
            final boolean completed = success;
            main.post(() -> {
                if (cleared) return;
                busy = false;
                if (completed) { saved.set("pending", true); saved.set("ready", true); }
                else result = -1;
                changed();
            });
        });
    }

    void write(Uri uri) {
        if (uri == null) {
            saved.set("pending", false); saved.set("ready", false);
            String oldPath = saved.get("path");
            saved.set("path", null);
            if (oldPath != null) io.execute(() -> deleteSnapshot(oldPath));
            changed(); return;
        }
        saved.set("destination", uri.toString());
        final String path = saved.get("path");
        busy = true;
        io.execute(() -> {
            boolean success = false;
            try (InputStream input = new FileInputStream(snapshotFile());
                 OutputStream output = getApplication().getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("No destination");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
                success = true;
            } catch (IOException | RuntimeException failure) { success = false; }
            final boolean completed = success;
            if (completed && path != null) deleteSnapshot(path);
            main.post(() -> {
                if (cleared) return;
                busy = false;
                saved.set("destination", null);
                saved.set("pending", false);
                saved.set("ready", false);
                result = completed ? 1 : -1;
                changed();
            });
        });
    }

    private void deleteSnapshot(String path) {
        try {
            File file = new File(path).getCanonicalFile();
            if (getApplication().getCacheDir().getCanonicalFile().equals(file.getParentFile())
                    && file.getName().startsWith("ble-export-")) file.delete();
        } catch (IOException ignored) { }
    }

    @Override protected void onCleared() {
        cleared = true;
        listener = null;
        String path = saved.get("path");
        if (path != null) io.execute(() -> deleteSnapshot(path));
        io.shutdown();
    }
}
