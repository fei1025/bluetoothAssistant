package com.zzf.bluetoothsmp.plugin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PluginManifest {
    public static final int SDK_VERSION = 1;
    public static final Set<String> CAPABILITIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "classic.scan", "classic.connect", "classic.io", "ble.scan", "ble.connect", "ble.gatt")));
    public final String id, name, author, entry, authorKey;
    public final int version, sdk;
    public final Set<String> capabilities;
    public final Map<String, String> files;
    public final byte[] bytes;

    public PluginManifest(byte[] bytes) throws PluginException {
        try {
            if (bytes.length > 128 * 1024) throw new IllegalArgumentException();
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            id = json.getString("id"); name = json.getString("name"); author = json.getString("author");
            entry = json.getString("entry"); authorKey = json.getString("authorKey");
            if (!(json.get("version") instanceof Integer) || !(json.get("sdk") instanceof Integer)) throw new IllegalArgumentException();
            version = json.getInt("version"); sdk = json.getInt("sdk");
            if (!id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+") || id.length() > 100
                    || name.isEmpty() || name.length() > 100 || author.isEmpty() || author.length() > 100
                    || version < 1 || !safePath(entry) || !entry.endsWith(".html")) throw new IllegalArgumentException();
            if (sdk != SDK_VERSION) throw new PluginException("SDK_UNSUPPORTED", "Unsupported plugin SDK");
            Set<String> permissions = new TreeSet<>();
            JSONArray caps = json.getJSONArray("capabilities");
            for (int i = 0; i < caps.length(); i++) {
                String capability = caps.getString(i);
                if (!CAPABILITIES.contains(capability) || !permissions.add(capability)) throw new IllegalArgumentException();
            }
            capabilities = Collections.unmodifiableSet(permissions);
            Map<String, String> hashes = new TreeMap<>();
            JSONObject resources = json.getJSONObject("files");
            Iterator<String> names = resources.keys();
            while (names.hasNext()) {
                String path = names.next(); String hash = resources.getString(path);
                if (!safePath(path) || path.equals("plugin.json") || path.equals("signature.json")
                        || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
                hashes.put(path, hash);
            }
            if (!hashes.containsKey(entry) || hashes.size() > 510) throw new IllegalArgumentException();
            files = Collections.unmodifiableMap(hashes);
            this.bytes = bytes.clone();
        } catch (PluginException error) { throw error; }
        catch (Exception error) { throw new PluginException("INVALID_MANIFEST", "Invalid plugin manifest"); }
    }

    public static boolean safePath(String path) {
        if (path == null || path.length() > 240 || !path.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) return false;
        for (String segment : path.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        return true;
    }
}
