package com.zzf.bluetoothsmp.plugin;

/** Stable SDK error codes; messages must never contain keys or arbitrary file paths. */
public final class PluginException extends Exception {
    public final String code;
    public PluginException(String code, String message) { super(message); this.code = code; }
}
