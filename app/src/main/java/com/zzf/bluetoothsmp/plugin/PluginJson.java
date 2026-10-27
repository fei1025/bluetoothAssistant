package com.zzf.bluetoothsmp.plugin;

import org.json.JSONObject;

public final class PluginJson {
    private PluginJson() { }
    public static JSONObject object(Object... values) {
        try {
            JSONObject json = new JSONObject();
            for (int i = 0; i < values.length; i += 2) json.put((String) values[i], values[i + 1] == null ? JSONObject.NULL : values[i + 1]);
            return json;
        } catch (Exception error) { throw new IllegalArgumentException(error); }
    }
}
