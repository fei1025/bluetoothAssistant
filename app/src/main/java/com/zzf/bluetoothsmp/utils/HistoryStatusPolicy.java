package com.zzf.bluetoothsmp.utils;

/** A late intermediate snapshot must never replace a completed send. */
public final class HistoryStatusPolicy {
    private HistoryStatusPolicy() { }
    public static boolean isTerminal(String status) {
        return "SENT".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status);
    }
    public static boolean shouldReplace(String current, String incoming) {
        if (incoming == null) return false;
        if (isTerminal(current)) return current.equals(incoming);
        return !"SENDING".equals(current) || !"QUEUED".equals(incoming);
    }
}
