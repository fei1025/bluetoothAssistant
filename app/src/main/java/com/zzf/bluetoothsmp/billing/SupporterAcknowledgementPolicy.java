package com.zzf.bluetoothsmp.billing;

import com.android.billingclient.api.BillingClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/** Bounded acknowledgement retries, independent from entitlement/UI state. */
final class SupporterAcknowledgementPolicy {
    static final int MAX_ATTEMPTS = 24;
    static final long MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(3);

    private SupporterAcknowledgementPolicy() { }

    static boolean canRetry(int attempts, long firstSeenMillis, long nowMillis) {
        return attempts < MAX_ATTEMPTS && firstSeenMillis > 0
                // A wall-clock rollback must not discard an unconfirmed order.
                && Math.max(0L, nowMillis - firstSeenMillis) < MAX_AGE_MILLIS;
    }

    static boolean isTransient(int code) {
        return code == BillingClient.BillingResponseCode.NETWORK_ERROR
                || code == BillingClient.BillingResponseCode.SERVICE_TIMEOUT
                || code == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED
                || code == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
                || code == BillingClient.BillingResponseCode.ERROR;
    }

    static String tokenKey(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Missing purchase token");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
