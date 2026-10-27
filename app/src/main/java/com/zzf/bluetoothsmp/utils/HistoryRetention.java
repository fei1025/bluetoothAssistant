package com.zzf.bluetoothsmp.utils;
import android.content.Context;

/** Opt-in retention: the default never removes existing user history. */
public final class HistoryRetention {
    private static final String PREFS = "history_retention";
    private HistoryRetention() { }
    public static int getDays(Context context) {
        int value = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("days", 0);
        return value >= 0 && value <= 36500 ? value : 0;
    }
    public static boolean setDaysAndPrune(Context context, int days) {
        return HistoryRepository.configureRetention(context, days);
    }
    static void persistDays(Context context, int days) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("days", days).apply();
    }
}
