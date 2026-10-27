package com.zzf.bluetoothsmp;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.zzf.bluetoothsmp.entity.CommandMacroEntity;
import com.zzf.bluetoothsmp.entity.LogItem;
import com.zzf.bluetoothsmp.utils.HexUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Saved state contains small paths/target identities only; all SAF/serialization work is background. */
public final class DebugTransferState extends ViewModel {
    private final SavedStateHandle state;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean cleared = new AtomicBoolean();
    public DebugTransferState(SavedStateHandle state) { this.state = state; }
    public LiveData<String> readyExports() { return state.getLiveData("ready_export"); }
    public LiveData<Bundle> feedback() { return state.getLiveData("feedback"); }
    public void consumeReady() { state.set("ready_export", null); }
    public void consumeFeedback() { state.set("feedback", null); }
    public String filename(String slot) { return state.get(slot + "_filename"); }
    public boolean markPickerLaunched(String slot) {
        if (state.get("picker_slot") != null) return false;
        state.set("picker_slot", slot); consumeReady(); return true;
    }
    public void clearPicker() { state.set("picker_slot", null); }
    public boolean beginImport(String address) {
        if (busy.get() || state.get("picker_slot") != null || state.get("ready_export") != null) return false;
        state.set("import_address", address); state.set("picker_slot", "import"); return true;
    }
    public String getImportAddress() { return state.get("import_address"); }
    public void cancel(String slot) {
        clearPicker();
        if ("import".equals(slot)) { state.set("import_address", null); return; }
        String path = state.get(slot + "_path");
        if (path != null) io.execute(() -> new File(path).delete());
        state.set(slot + "_path", null); state.set(slot + "_filename", null);
        consumeReady();
    }
    public boolean prepareMacros(Context context, String address) {
        return prepare(context, "macro", "spp_macros.txt", output -> {
            String content = MacroTransferCodec.exportMacros(CommandMacroStore.findForAddress(address));
            output.write(content.getBytes(StandardCharsets.UTF_8));
        });
    }
    public boolean prepareLogs(Context context, String filename, List<LogItem> logs, String name, String address) {
        List<LogItem> snapshot = new ArrayList<>(logs);
        return prepare(context, "log", filename, output -> {
            write(output, "Bluetooth Debug Log\nDevice: " + name + "\nAddress: " + address + "\n\n");
            for (LogItem item : snapshot) {
                byte[] raw = item.getRawBytes();
                if (raw == null) raw = new byte[0];
                write(output, "[" + item.getTimestamp() + "] " + (item.isSent() ? "SEND" : "RECV")
                        + " " + item.getSendStatus() + "\nHEX: ");
                // Stream complete bytes; do not allocate a multi-megabyte HEX String.
                for (int offset = 0; offset < raw.length; offset += 4096) {
                    write(output, HexUtils.bytesToHex(Arrays.copyOfRange(raw, offset, Math.min(raw.length, offset + 4096))));
                    if (offset + 4096 < raw.length) write(output, " ");
                }
                write(output, "\nTEXT_ENCODING: " + item.getTextEncoding() + "\nTEXT: ");
                if (item.getTextEncoding() == BluetoothTextEncoding.HEX) {
                    for (int offset = 0; offset < raw.length; offset += 4096) {
                        write(output, HexUtils.bytesToHex(Arrays.copyOfRange(raw, offset, Math.min(raw.length, offset + 4096))));
                    }
                } else {
                    Charset charset = item.getTextEncoding() == BluetoothTextEncoding.GBK ? Charset.forName("GBK")
                            : item.getTextEncoding() == BluetoothTextEncoding.ASCII ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8;
                    InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(raw), charset);
                    OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
                    char[] chars = new char[4096]; int count;
                    while ((count = reader.read(chars)) != -1) writer.write(chars, 0, count);
                    writer.flush(); reader.close();
                }
                write(output, "\n\n");
            }
        });
    }
    private static void write(OutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }
    private boolean prepare(Context context, String slot, String filename, WriteSnapshot action) {
        if (state.get("picker_slot") != null || state.get("ready_export") != null || !busy.compareAndSet(false, true)) return false;
        state.set("preparing_slot", slot);
        Context app = context.getApplicationContext();
        io.execute(() -> {
            File file = new File(app.getCacheDir(), "spp-export-" + UUID.randomUUID() + ".txt");
            try {
                try (OutputStream output = new FileOutputStream(file)) { action.write(output); }
                // Publish only AFTER close: a fast picker result must see a complete immutable snapshot.
                if (cleared.get()) { file.delete(); return; }
                main.post(() -> {
                    if (cleared.get()) { file.delete(); return; }
                    state.set(slot + "_path", file.getAbsolutePath());
                    state.set(slot + "_filename", filename);
                    state.set("preparing_slot", null);
                    busy.set(false); state.set("ready_export", slot);
                });
            } catch (Exception error) {
                file.delete(); main.post(() -> { state.set("preparing_slot", null); finish(R.string.export_failed, 0); });
            }
        });
        return true;
    }
    public void writeExport(Context context, Uri uri, String slot) {
        if (!busy.compareAndSet(false, true)) { notifyFeedback(R.string.debug_file_busy, 0); return; }
        clearPicker();
        String path = state.get(slot + "_path");
        if (path == null || !new File(path).isFile()) {
            state.set("pending_uri", null); state.set("pending_slot", null);
            state.set(slot + "_path", null); state.set(slot + "_filename", null);
            finish(R.string.debug_pending_export_missing, 0); return;
        }
        state.set("pending_uri", uri.toString()); state.set("pending_slot", slot);
        Context app = context.getApplicationContext();
        io.execute(() -> {
            int result = "macro".equals(slot) ? R.string.macro_exported : R.string.log_exported;
            try (InputStream input = new FileInputStream(path);
                 OutputStream output = app.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IllegalStateException("No destination stream");
                byte[] bytes = new byte[8192]; int count;
                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            } catch (Exception error) { result = R.string.export_failed; }
            final int feedback = result;
            main.post(() -> {
                if (cleared.get()) return;
                if (feedback != R.string.export_failed) cancel(slot);
                state.set("pending_uri", null); state.set("pending_slot", null); finish(feedback, 0);
            });
        });
    }
    public void importMacros(Context context, Uri uri) {
        if (!busy.compareAndSet(false, true)) { notifyFeedback(R.string.debug_file_busy, 0); return; }
        clearPicker();
        String address = getImportAddress();
        state.set("pending_uri", uri.toString()); state.set("pending_slot", "import");
        Context app = context.getApplicationContext();
        io.execute(() -> {
            int count = 0, result = R.string.macro_transfer_failed;
            try (InputStream input = app.getContentResolver().openInputStream(uri)) {
                if (input == null || address == null) throw new IllegalStateException("No import target");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int read;
                while ((read = input.read(bytes)) != -1) {
                    if (buffer.size() + read > 256 * 1024) throw new IllegalArgumentException("Import too large");
                    buffer.write(bytes, 0, read);
                }
                List<CommandMacroEntity> macros = MacroTransferCodec.importMacros(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                CommandMacroStore.importForAddress(address, macros);
                count = macros.size(); result = R.string.macro_imported;
            } catch (Exception ignored) { }
            final int resultResource = result, importedCount = count;
            main.post(() -> {
                if (cleared.get()) return;
                state.set("pending_uri", null); state.set("pending_slot", null);
                state.set("import_address", null); finish(resultResource, importedCount);
            });
        });
    }
    public void resumePending(Context context) {
        if (state.get("preparing_slot") != null && !busy.get()) {
            // A process died before the immutable snapshot committed. Never export a partial file.
            state.set("preparing_slot", null); notifyFeedback(R.string.debug_pending_export_missing, 0); return;
        }
        String uri = state.get("pending_uri"), slot = state.get("pending_slot");
        if (uri == null || slot == null || busy.get()) return;
        if ("import".equals(slot)) importMacros(context, Uri.parse(uri));
        else writeExport(context, Uri.parse(uri), slot);
    }
    private void finish(int resource, int count) { busy.set(false); notifyFeedback(resource, count); }
    private void notifyFeedback(int resource, int count) {
        if (cleared.get()) return;
        Bundle result = new Bundle(); result.putInt("resource", resource); result.putInt("count", count); state.set("feedback", result);
    }
    @Override protected void onCleared() { cleared.set(true); io.shutdown(); }
    private interface WriteSnapshot { void write(OutputStream output) throws Exception; }
}
