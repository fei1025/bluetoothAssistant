package com.zzf.bluetoothsmp.billing;

import com.android.billingclient.api.BillingClient;
import org.junit.Test;

import static org.junit.Assert.*;

public class SupporterAcknowledgementPolicyTest {
    @Test public void retryBudgetAndDeadlineAreBounded() {
        assertTrue(SupporterAcknowledgementPolicy.canRetry(0, 1000, 1000));
        assertFalse(SupporterAcknowledgementPolicy.canRetry(24, 1000, 1001));
        assertFalse(SupporterAcknowledgementPolicy.canRetry(0, 1000,
                1000 + SupporterAcknowledgementPolicy.MAX_AGE_MILLIS));
        assertTrue(SupporterAcknowledgementPolicy.canRetry(0, 1000, 999));
    }

    @Test public void configurationFailuresAreNotBlindlyRetried() {
        assertTrue(SupporterAcknowledgementPolicy.isTransient(BillingClient.BillingResponseCode.NETWORK_ERROR));
        assertTrue(SupporterAcknowledgementPolicy.isTransient(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED));
        assertFalse(SupporterAcknowledgementPolicy.isTransient(BillingClient.BillingResponseCode.DEVELOPER_ERROR));
        assertFalse(SupporterAcknowledgementPolicy.isTransient(BillingClient.BillingResponseCode.ITEM_NOT_OWNED));
    }

    @Test public void workIdentityDeduplicatesWithoutExposingTokens() {
        String key = SupporterAcknowledgementPolicy.tokenKey("private-purchase-token");
        assertEquals(64, key.length());
        assertEquals(key, SupporterAcknowledgementPolicy.tokenKey("private-purchase-token"));
        assertNotEquals(key, SupporterAcknowledgementPolicy.tokenKey("another-purchase-token"));
        assertFalse(key.contains("private"));
    }
}
