package com.zzf.bluetoothsmp.plugin;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.zzf.bluetoothsmp.BuildConfig;

/** Immutable content-addressed versions; an AtomicFile is the only installed-version pointer. */
public final class PluginStore {
    private static final Object LOCK = new Object();
    private final Context context;
    private final File root;
    public static final class Installed {
        public final PluginManifest manifest;
        public final String digest, author;
        public final File directory;
        Installed(PluginManifest manifest, String digest, String author, File directory) {
            this.manifest = manifest; this.digest = digest; this.author = author; this.directory = directory;
        }
        public String identity() { return manifest.id + "." + author; }
    }
    public PluginStore(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getNoBackupFilesDir(), "plugins");
        root.mkdirs();
    }
    public Set<String> reviewKeys() throws Exception {
        try (InputStream source = context.getAssets().open("plugins/review-keys.json")) {
            JSONArray values = new JSONArray(new String(read(source, 65536), StandardCharsets.UTF_8));
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < values.length(); i++) keys.add(values.getString(i));
            return keys;
        }
    }
    public List<Installed> list() {
        synchronized (LOCK) {
            List<Installed> result = new ArrayList<>();
            File[] files = root.listFiles((dir, name) -> name.endsWith(".active") || name.endsWith(".active.bak"));
            Set<String> loaded = new HashSet<>();
            if (files != null) for (File file : files) {
                String name = file.getName().endsWith(".bak") ? file.getName().substring(0, file.getName().length() - 4) : file.getName();
                String id = name.substring(0, name.length() - 7);
                try { if (loaded.add(id)) result.add(load(id, false)); }
                catch (Exception ignored) { }
            }
            Collections.sort(result, (left, right) -> left.manifest.name.compareTo(right.manifest.name));
            return result;
        }
    }
    public Installed load(String id) throws Exception { return load(id, true); }
    private Installed load(String id, boolean checkRevocation) throws Exception {
        synchronized (LOCK) {
            checkId(id);
            JSONObject pointer = json(new AtomicFile(new File(root, id + ".active")));
            String digest = pointer.getString("digest"), author = pointer.getString("author");
            if (!digest.matches("[a-f0-9]{64}") || !author.matches("[a-f0-9]{64}")) throw new IOException();
            File version = new File(root, "versions/" + digest);
            PluginManifest manifest;
            try (InputStream source = new FileInputStream(new File(version, "plugin.json"))) {
                manifest = new PluginManifest(read(source, 128 * 1024));
            }
            if (!id.equals(manifest.id) || !author.equals(PluginSignatures.sha256(Base64.decode(manifest.authorKey, Base64.DEFAULT)))) throw new IOException();
            if (!digest.equals(PluginSignatures.sha256(manifest.bytes))) throw new IOException("Stored content identity is invalid");
            JSONObject signatures;
            try (InputStream source = new FileInputStream(new File(version, "signature.json"))) {
                signatures = new JSONObject(new String(read(source, 128 * 1024), StandardCharsets.UTF_8));
            }
            if (!PluginSignatures.verify(manifest.bytes, Base64.decode(manifest.authorKey, Base64.DEFAULT),
                    Base64.decode(signatures.getString("author"), Base64.DEFAULT))) throw new PluginException("AUTHOR_SIGNATURE_INVALID", "Stored plugin signature is invalid");
            String reviewKey = signatures.optString("reviewKey", "");
            boolean reviewed = reviewKeys().contains(reviewKey) && PluginSignatures.verify(manifest.bytes, Base64.decode(reviewKey, Base64.DEFAULT),
                    Base64.decode(signatures.optString("review", ""), Base64.DEFAULT));
            if (!reviewed && !BuildConfig.PLUGIN_DEVELOPER_MODE) throw new PluginException("REVIEW_REQUIRED", "This plugin has not been reviewed");
            long total = 0;
            for (Map.Entry<String, String> resource : manifest.files.entrySet()) {
                File file = new File(version, resource.getKey());
                total += file.length();
                if (total > PluginArchive.MAX_EXPANDED) throw new IOException("Plugin size exceeded");
                try (InputStream source = new FileInputStream(file)) {
                    if (!resource.getValue().equals(PluginSignatures.sha256(read(source, PluginArchive.MAX_EXPANDED))))
                        throw new PluginException("RESOURCE_HASH_MISMATCH", "Stored plugin resource is invalid");
                }
            }
            if (checkRevocation && isRevoked(digest)) throw new PluginException("VERSION_REVOKED", "This plugin version has been withdrawn");
            return new Installed(manifest, digest, author, version);
        }
    }
    public Installed install(File archive, String expectedDigest) throws Exception {
        return install(archive, expectedDigest, null, 0, null);
    }
    public Installed install(File archive, String expectedDigest, String expectedId, int expectedVersion, String expectedContent) throws Exception {
        synchronized (LOCK) {
            File staging = new File(root, "staging/" + UUID.randomUUID());
            try {
                PluginArchive verified = new PluginArchive(archive, staging, reviewKeys(), BuildConfig.PLUGIN_DEVELOPER_MODE,
                        value -> Base64.decode(value, Base64.DEFAULT));
                if (expectedDigest != null && !expectedDigest.equals(verified.archiveDigest)) throw new PluginException("RESOURCE_HASH_MISMATCH", "Download hash does not match the catalogue");
                if (expectedId != null && (!expectedId.equals(verified.manifest.id) || expectedVersion != verified.manifest.version
                        || !verified.digest.equals(expectedContent))) throw new PluginException("CATALOG_MISMATCH", "Package does not match its catalogue entry");
                if (isRevoked(verified.digest)) throw new PluginException("VERSION_REVOKED", "This plugin version has been withdrawn");
                String id = verified.manifest.id;
                if (PluginRuntime.isRunning(id)) throw new PluginException("PLUGIN_RUNNING", "Stop this plugin before updating it");
                File stateFile = new File(root, id + ".state");
                JSONObject state;
                try { state = json(new AtomicFile(stateFile)); }
                catch (FileNotFoundException missing) { state = new JSONObject(); }
                if (state.has("author") && !state.getString("author").equals(verified.authorFingerprint))
                    throw new PluginException("AUTHOR_CHANGED", "The plugin publisher cannot change");
                if (verified.manifest.version < state.optInt("version", 0)) throw new PluginException("VERSION_DOWNGRADE", "An older plugin version cannot replace this version");
                if (verified.manifest.version == state.optInt("version", 0) && state.has("digest")
                        && !verified.digest.equals(state.getString("digest"))) throw new PluginException("VERSION_CONFLICT", "Changed packages must increment their version");
                File version = new File(root, "versions/" + verified.digest);
                version.getParentFile().mkdirs();
                if (!version.exists() && !staging.renameTo(version)) throw new IOException("Cannot install plugin version");
                JSONObject pointer = new JSONObject().put("digest", verified.digest).put("author", verified.authorFingerprint);
                // Write continuity metadata first: it must survive uninstall and a failed activation.
                state.put("author", verified.authorFingerprint).put("version", verified.manifest.version).put("digest", verified.digest);
                write(new AtomicFile(stateFile), state.toString().getBytes(StandardCharsets.UTF_8));
                write(new AtomicFile(new File(root, id + ".active")), pointer.toString().getBytes(StandardCharsets.UTF_8));
                return load(id);
            } finally { removeTree(staging); }
        }
    }
    public void uninstall(Installed plugin) throws Exception {
        synchronized (LOCK) {
            if (PluginRuntime.isRunning(plugin.manifest.id)) throw new PluginException("PLUGIN_RUNNING", "Stop this plugin first");
            new AtomicFile(new File(root, plugin.manifest.id + ".active")).delete();
            revokePermissions(plugin);
            removeTree(plugin.directory);
        }
    }
    public JSONObject storage(Installed plugin) throws Exception {
        synchronized (LOCK) {
            AtomicFile file = dataFile(plugin);
            try { return json(file); }
            catch (FileNotFoundException missing) { return new JSONObject(); }
        }
    }
    public void store(Installed plugin, String key, Object value, boolean remove) throws Exception {
        synchronized (LOCK) {
            if (key == null || key.length() > 200) throw new PluginException("INVALID_ARGUMENT", "Invalid storage key");
            JSONObject data = storage(plugin);
            if (remove) data.remove(key); else data.put(key, value == null ? JSONObject.NULL : value);
            byte[] encoded = data.toString().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > 1024 * 1024) throw new PluginException("STORAGE_QUOTA", "Plugin storage is limited to 1 MiB");
            write(dataFile(plugin), encoded);
        }
    }
    public void clearData(Installed plugin) { synchronized (LOCK) { dataFile(plugin).delete(); revokePermissions(plugin); } }
    public Set<String> grants(Installed plugin) {
        return new HashSet<>(context.getSharedPreferences("plugin_grants", Context.MODE_PRIVATE)
                .getStringSet(plugin.identity() + ".capabilities", Collections.emptySet()));
    }
    public boolean grant(Installed plugin, Set<String> capabilities) {
        return context.getSharedPreferences("plugin_grants", Context.MODE_PRIVATE).edit()
                .putStringSet(plugin.identity() + ".capabilities", new HashSet<>(capabilities)).commit();
    }
    public boolean deviceGranted(Installed plugin, String transport, String address) {
        return context.getSharedPreferences("plugin_grants", Context.MODE_PRIVATE)
                .getBoolean(plugin.identity() + ".device." + transport + "." + address, false);
    }
    public boolean grantDevice(Installed plugin, String transport, String address) {
        return context.getSharedPreferences("plugin_grants", Context.MODE_PRIVATE).edit()
                .putBoolean(plugin.identity() + ".device." + transport + "." + address, true).commit();
    }
    public void revokePermissions(Installed plugin) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("plugin_grants", Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) if (key.startsWith(plugin.identity() + ".")) editor.remove(key);
        editor.commit();
    }
    public boolean isRevoked(String digest) {
        return context.getSharedPreferences("plugin_catalog", Context.MODE_PRIVATE)
                .getStringSet("revoked", Collections.emptySet()).contains(digest);
    }
    private AtomicFile dataFile(Installed plugin) { return new AtomicFile(new File(root, "data/" + plugin.identity() + ".json")); }
    private void checkId(String id) throws IOException { if (id == null || !id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+") || id.length() > 100) throw new IOException(); }
    static JSONObject json(AtomicFile file) throws Exception {
        try (InputStream source = file.openRead()) { return new JSONObject(new String(read(source, 1024 * 1024), StandardCharsets.UTF_8)); }
    }
    static void write(AtomicFile file, byte[] data) throws IOException {
        file.getBaseFile().getParentFile().mkdirs();
        FileOutputStream output = null;
        try { output = file.startWrite(); output.write(data); file.finishWrite(output); }
        catch (IOException error) { if (output != null) file.failWrite(output); throw error; }
    }
    public static byte[] read(InputStream source, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read;
        while ((read = source.read(buffer)) != -1) {
            if (bytes.size() + read > limit) throw new IOException("Size limit exceeded");
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }
    private void removeTree(File file) throws IOException {
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Invalid cleanup path");
        File[] children = file.listFiles(); if (children != null) for (File child : children) removeTree(child);
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove plugin files");
    }
}
