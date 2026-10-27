package com.zzf.bluetoothsmp.billing;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Private, backup-excluded pending purchases. Tokens never enter logs or WorkManager input. */
final class SupporterAcknowledgementStore {
    private static final Object LOCK = new Object();
    private static final String TOKEN_PREFIX = "token_";
    private static final String TIME_PREFIX = "first_seen_";
    private final SharedPreferences preferences;

    SupporterAcknowledgementStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                "supporter_pending_acknowledgements", Context.MODE_PRIVATE);
    }

    String remember(String token, long nowMillis) {
        String key = SupporterAcknowledgementPolicy.tokenKey(token);
        synchronized (LOCK) {
            if (!preferences.contains(TOKEN_PREFIX + key)) {
                // Persist before scheduling: process death must not discard a new purchase.
                if (!preferences.edit().putString(TOKEN_PREFIX + key, token)
                        .putLong(TIME_PREFIX + key, nowMillis).commit()) {
                    throw new IllegalStateException("Cannot persist purchase acknowledgement");
                }
            }
        }
        return key;
    }

    String token(String key) {
        synchronized (LOCK) {
            return preferences.getString(TOKEN_PREFIX + key, null);
        }
    }

    long firstSeen(String key) {
        synchronized (LOCK) {
            return preferences.getLong(TIME_PREFIX + key, 0L);
        }
    }

    List<String> keys() {
        List<String> result = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (entry.getKey().startsWith(TOKEN_PREFIX)) {
                    result.add(entry.getKey().substring(TOKEN_PREFIX.length()));
                }
            }
        }
        return result;
    }

    void forget(String key) {
        synchronized (LOCK) {
            preferences.edit().remove(TOKEN_PREFIX + key).remove(TIME_PREFIX + key).commit();
        }
    }
}
