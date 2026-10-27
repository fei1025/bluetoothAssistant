package com.zzf.bluetoothsmp.plugin;

import org.json.JSONObject;

public interface PluginTransport {
    interface Reply {
        default boolean active() { return true; }
        void success(Object value);
        void error(String code, String message);
    }
    interface Listener { void event(String name, JSONObject value); }
    void execute(String action, JSONObject params, Reply reply);
    JSONObject snapshot();
    boolean busy();
    void stopScan();
    void disconnect();
    void close();
}
