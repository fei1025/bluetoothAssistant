package com.zzf.bluetoothsmp.billing;

import java.util.Collections;
import java.util.List;

/** Pure purchase-state reduction kept separate from the Play Billing client. */
public final class SupporterEntitlementResolver {

    public enum PurchaseStatus {
        PURCHASED,
        PENDING,
        OTHER
    }

    public enum Result {
        OWNED,
        PENDING,
        NOT_OWNED
    }

    public static final class Entry {
        private final List<String> productIds;
        private final PurchaseStatus status;

        public Entry(List<String> productIds, PurchaseStatus status) {
            this.productIds = productIds == null ? Collections.emptyList() : productIds;
            this.status = status == null ? PurchaseStatus.OTHER : status;
        }
    }

    private SupporterEntitlementResolver() {
    }

    public static Result resolve(List<Entry> entries, String targetProductId) {
        boolean pending = false;
        if (entries == null || targetProductId == null) {
            return Result.NOT_OWNED;
        }
        for (Entry entry : entries) {
            if (entry == null || !entry.productIds.contains(targetProductId)) {
                continue;
            }
            if (entry.status == PurchaseStatus.PURCHASED) {
                return Result.OWNED;
            }
            if (entry.status == PurchaseStatus.PENDING) {
                pending = true;
            }
        }
        return pending ? Result.PENDING : Result.NOT_OWNED;
    }
}
