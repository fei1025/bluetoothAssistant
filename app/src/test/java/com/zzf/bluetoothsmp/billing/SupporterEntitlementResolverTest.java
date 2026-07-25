package com.zzf.bluetoothsmp.billing;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class SupporterEntitlementResolverTest {

    @Test
    public void purchasedTargetWinsOverPending() {
        assertEquals(SupporterEntitlementResolver.Result.OWNED,
                SupporterEntitlementResolver.resolve(Arrays.asList(
                                entry("supporter_badge",
                                        SupporterEntitlementResolver.PurchaseStatus.PENDING),
                                entry("supporter_badge",
                                        SupporterEntitlementResolver.PurchaseStatus.PURCHASED)),
                        "supporter_badge"));
    }

    @Test
    public void pendingTargetDoesNotGrantOwnership() {
        assertEquals(SupporterEntitlementResolver.Result.PENDING,
                SupporterEntitlementResolver.resolve(Collections.singletonList(
                                entry("supporter_badge",
                                        SupporterEntitlementResolver.PurchaseStatus.PENDING)),
                        "supporter_badge"));
    }

    @Test
    public void unrelatedOrMissingPurchasesAreNotOwned() {
        assertEquals(SupporterEntitlementResolver.Result.NOT_OWNED,
                SupporterEntitlementResolver.resolve(Collections.singletonList(
                                entry("another_product",
                                        SupporterEntitlementResolver.PurchaseStatus.PURCHASED)),
                        "supporter_badge"));
        assertEquals(SupporterEntitlementResolver.Result.NOT_OWNED,
                SupporterEntitlementResolver.resolve(Collections.emptyList(),
                        "supporter_badge"));
    }

    private SupporterEntitlementResolver.Entry entry(
            String productId, SupporterEntitlementResolver.PurchaseStatus status) {
        return new SupporterEntitlementResolver.Entry(Collections.singletonList(productId), status);
    }
}
