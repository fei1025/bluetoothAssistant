package com.zzf.bluetoothsmp.plugin;

import android.app.Application;
import android.net.Uri;
import androidx.lifecycle.*;
import com.zzf.bluetoothsmp.BuildConfig;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Retains installer work across rotations; only app-private paths/URIs are transferred to IO. */
public final class PluginCenterModel extends AndroidViewModel {
    public static final class State {
        public final List<PluginStore.Installed> installed;
        public final JSONArray catalogue;
        public final String result;
        public final boolean busy;
        State(List<PluginStore.Installed> installed, JSONArray catalogue, String result, boolean busy) {
            this.installed = installed; this.catalogue = catalogue; this.result = result; this.busy = busy;
        }
    }
    public final MutableLiveData<State> state = new MutableLiveData<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final PluginStore store;
    private final PluginCatalog catalog;
    private volatile boolean busy;
    private volatile JSONArray entries = new JSONArray();
    public PluginCenterModel(Application application) {
        super(application); store = new PluginStore(application); catalog = new PluginCatalog(application);
        io.execute(() -> { entries = catalog.cached().optJSONArray("plugins"); publish("", false); });
    }
    public void reload() { if (!busy) io.execute(() -> publish("", false)); }
    private synchronized void work(Work action) {
        if (busy) return;
        busy = true; State previous = state.getValue();
        state.setValue(new State(previous == null ? Collections.emptyList() : previous.installed, entries, "", true));
        io.execute(() -> {
            String result = "OK";
            try { action.run(); } catch (Exception error) { result = error instanceof PluginException ? ((PluginException) error).code : "OPERATION_FAILED"; }
            busy = false; publish(result, false);
        });
    }
    private void publish(String result, boolean working) { state.postValue(new State(store.list(), entries, result, working)); }
    public void refresh() { work(() -> entries = catalog.refresh(BuildConfig.PLUGIN_CATALOG_URL).getJSONArray("plugins")); }
    public void installUrl(String url, String expectedDigest) {
        work(() -> { File file = PluginCatalog.download(getApplication(), url, PluginArchive.MAX_ARCHIVE);
            try { store.install(file, expectedDigest); } finally { file.delete(); } });
    }
    public void installEntry(JSONObject entry) {
        work(() -> {
            File file = PluginCatalog.download(getApplication(), entry.getString("url"), PluginArchive.MAX_ARCHIVE);
            try { store.install(file, entry.getString("sha256"), entry.getString("id"), entry.getInt("version"), entry.getString("contentSha256")); }
            finally { file.delete(); }
        });
    }
    public void installUri(Uri uri) {
        work(() -> {
            File directory = new File(getApplication().getCacheDir(), "plugin-downloads"); directory.mkdirs();
            File target = File.createTempFile("import-", ".download", directory);
            try {
                try (InputStream source = getApplication().getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(target)) {
                    if (source == null) throw new IOException();
                    byte[] bytes = PluginStore.read(source, PluginArchive.MAX_ARCHIVE); output.write(bytes);
                }
                store.install(target, null);
            } finally { target.delete(); }
        });
    }
    public void installBundled(String name) {
        work(() -> {
            File target = File.createTempFile("example-", ".btplugin", getApplication().getCacheDir());
            try {
                try (InputStream source = getApplication().getAssets().open("plugins/examples/" + name + ".btplugin"); OutputStream out = new FileOutputStream(target)) {
                    out.write(PluginStore.read(source, PluginArchive.MAX_ARCHIVE));
                }
                store.install(target, null);
            } finally { target.delete(); }
        });
    }
    public void uninstall(PluginStore.Installed plugin) { work(() -> store.uninstall(plugin)); }
    public void clear(PluginStore.Installed plugin) { work(() -> {
        if (PluginRuntime.isRunning(plugin.manifest.id)) throw new PluginException("PLUGIN_RUNNING", "Stop the plugin first");
        store.clearData(plugin);
    }); }
    private interface Work { void run() throws Exception; }
    @Override protected void onCleared() { io.shutdown(); }
}
