package com.zzf.bluetoothsmp.plugin;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only installer/catalogue code has networking; no network method exists in the plugin bridge. */
public final class PluginCatalog {
    private static final Object STATE_LOCK = new Object();
    private final Context context;
    private final PluginStore store;
    private final AtomicFile cache;
    public PluginCatalog(Context context) {
        this.context = context.getApplicationContext(); store = new PluginStore(context);
        cache = new AtomicFile(new File(context.getNoBackupFilesDir(), "plugin-catalog.json"));
    }
    public static boolean validUrl(String value) {
        try {
            URI uri = new URI(value);
            return value.length() <= 4096 && "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (Exception error) { return false; }
    }
    public static File download(Context context, String url, int limit) throws Exception {
        if (!validUrl(url)) throw new PluginException("HTTPS_REQUIRED", "Use an HTTPS URL without credentials or fragment");
        File directory = new File(context.getCacheDir(), "plugin-downloads"); directory.mkdirs();
        File target = File.createTempFile("package-", ".download", directory);
        HttpURLConnection connection = null; boolean success = false;
        long deadline = android.os.SystemClock.elapsedRealtime() + 60000;
        try {
            for (int redirects = 0; redirects <= 5; redirects++) {
                if (!validUrl(url)) throw new PluginException("HTTPS_REQUIRED", "Insecure download redirect rejected");
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
                connection.setUseCaches(false); connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "BluetoothAssistant-PluginInstaller/1");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == 5) throw new IOException("Invalid redirect");
                    url = new URL(new URL(url), location).toString(); connection.disconnect(); connection = null; continue;
                }
                if (status != 200 || connection.getContentLength() > limit) throw new IOException("Download unavailable or exceeds size limit");
                int size = 0;
                try (InputStream source = connection.getInputStream(); OutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = source.read(buffer)) != -1) {
                        size += read;
                        if (size > limit || android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("Download limit exceeded");
                        output.write(buffer, 0, read);
                    }
                }
                success = true; return target;
            }
            throw new IOException("Too many redirects");
        } finally {
            if (connection != null) connection.disconnect();
            if (!success) target.delete();
        }
    }
    public JSONObject refresh(String url) throws Exception {
        File downloaded = download(context, url, 1024 * 1024);
        try {
            byte[] bytes;
            try (InputStream source = new FileInputStream(downloaded)) { bytes = PluginStore.read(source, 1024 * 1024); }
            JSONObject payload = verify(bytes);
            apply(payload);
            PluginStore.write(cache, bytes);
            PluginRuntime.enforceRevocations(context);
            return payload;
        } finally { downloaded.delete(); }
    }
    public JSONObject cached() {
        try (InputStream source = cache.openRead()) { JSONObject payload = verify(PluginStore.read(source, 1024 * 1024)); apply(payload); return payload; }
        catch (Exception error) { return PluginJson.object("plugins", new JSONArray()); }
    }
    private JSONObject verify(byte[] bytes) throws Exception {
        JSONObject envelope = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        String key = envelope.getString("key"); byte[] payload = Base64.decode(envelope.getString("payload"), Base64.DEFAULT);
        if (!store.reviewKeys().contains(key) || !PluginSignatures.verify(payload, Base64.decode(key, Base64.DEFAULT),
                Base64.decode(envelope.getString("signature"), Base64.DEFAULT))) throw new PluginException("CATALOG_SIGNATURE_INVALID", "Catalogue signature is invalid");
        JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        if (json.getInt("revision") < 1) throw new IOException("Invalid catalogue revision");
        JSONArray plugins = json.getJSONArray("plugins"), revoked = json.getJSONArray("revoked");
        if (plugins.length() > 512 || revoked.length() > 4096) throw new IOException("Catalogue size limit exceeded");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject plugin = plugins.getJSONObject(i); String id = plugin.getString("id");
            if (!id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+") || id.length() > 100 || !ids.add(id)
                    || !validUrl(plugin.getString("url")) || !plugin.getString("sha256").matches("[a-f0-9]{64}")
                    || !plugin.getString("contentSha256").matches("[a-f0-9]{64}")
                    || plugin.getInt("version") < 1 || plugin.getString("name").length() > 100) throw new IOException("Invalid catalogue entry");
        }
        for (int i = 0; i < revoked.length(); i++) if (!revoked.getString(i).matches("[a-f0-9]{64}")) throw new IOException("Invalid revocation list");
        return json;
    }
    private void apply(JSONObject payload) throws Exception {
        // Multiple retained installer screens can synchronize concurrently.
        synchronized (STATE_LOCK) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("plugin_catalog", Context.MODE_PRIVATE);
        int revision = payload.getInt("revision"), previous = preferences.getInt("revision", 0);
        String hash = PluginSignatures.sha256(payload.toString().getBytes(StandardCharsets.UTF_8));
        if (revision < previous || revision == previous && !hash.equals(preferences.getString("hash", "")))
            throw new PluginException("CATALOG_ROLLBACK", "Catalogue revision is older than the last verified revision");
        Set<String> revoked = new HashSet<>(preferences.getStringSet("revoked", Collections.emptySet())); JSONArray list = payload.getJSONArray("revoked");
        for (int i = 0; i < list.length(); i++) revoked.add(list.getString(i));
        if (!preferences.edit().putInt("revision", revision).putString("hash", hash).putStringSet("revoked", revoked).commit()) throw new IOException("Cannot save catalogue state");
        }
    }
}
