package com.zzf.bluetoothsmp.billing;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.billingclient.api.UnfetchedProduct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Client-only owner and lifecycle wrapper for the supporter badge purchase. */
public final class SupporterBillingManager implements PurchasesUpdatedListener {

    public static final String SUPPORTER_BADGE_PRODUCT_ID = "supporter_badge";

    private static final String TAG = "SupporterBilling";
    private static final String PREFS_NAME = "supporter_billing";
    private static final String KEY_LAST_CONFIRMED_OWNED = "last_confirmed_owned";

    public enum State {
        CONNECTING,
        AVAILABLE,
        PENDING,
        OWNED,
        UNAVAILABLE,
        ERROR
    }

    public enum FailureReason {
        NONE,
        PLAY_BILLING_UNAVAILABLE,
        NETWORK,
        PRODUCT_NOT_FOUND,
        NO_ELIGIBLE_OFFER,
        CONFIGURATION,
        UNKNOWN
    }

    public static final class Snapshot {
        private final State state;
        private final boolean owned;
        @Nullable
        private final String formattedPrice;
        @NonNull
        private final FailureReason failureReason;

        Snapshot(State state, boolean owned, @Nullable String formattedPrice,
                 @NonNull FailureReason failureReason) {
            this.state = state;
            this.owned = owned;
            this.formattedPrice = formattedPrice;
            this.failureReason = failureReason;
        }

        public State getState() {
            return state;
        }

        public boolean isOwned() {
            return owned;
        }

        @Nullable
        public String getFormattedPrice() {
            return formattedPrice;
        }

        @NonNull
        public FailureReason getFailureReason() {
            return failureReason;
        }
    }

    public interface Listener {
        void onBillingStateChanged(Snapshot snapshot);
    }

    private final SharedPreferences preferences;
    private final BillingClient billingClient;
    @Nullable
    private Listener listener;
    @Nullable
    private ProductDetails productDetails;
    @Nullable
    private ProductDetails.OneTimePurchaseOfferDetails selectedOffer;
    @Nullable
    private String formattedPrice;
    private boolean connecting;
    private boolean closed;
    private boolean owned;
    private State state;
    private FailureReason failureReason = FailureReason.NONE;

    public SupporterBillingManager(@NonNull Context context) {
        Context applicationContext = context.getApplicationContext();
        preferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        owned = preferences.getBoolean(KEY_LAST_CONFIRMED_OWNED, false);
        state = owned ? State.OWNED : State.CONNECTING;
        billingClient = BillingClient.newBuilder(applicationContext)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build())
                .build();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
        publish();
    }

    public void start() {
        if (closed) {
            return;
        }
        if (billingClient.isReady()) {
            refreshFromPlay();
            return;
        }
        if (connecting) {
            return;
        }
        connecting = true;
        if (!owned) {
            state = State.CONNECTING;
            publish();
        }
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                connecting = false;
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    refreshFromPlay();
                } else {
                    handleBillingFailure("setup", billingResult);
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                connecting = false;
                failureReason = FailureReason.PLAY_BILLING_UNAVAILABLE;
                state = owned ? State.OWNED : State.ERROR;
                Log.w(TAG, "Google Play Billing service disconnected");
                publish();
            }
        });
    }

    public void syncPurchases() {
        if (billingClient.isReady()) {
            refreshFromPlay();
        } else {
            start();
        }
    }

    public void launchPurchase(@NonNull Activity activity) {
        if (owned) {
            state = State.OWNED;
            publish();
            return;
        }
        if (!billingClient.isReady() || productDetails == null || selectedOffer == null) {
            if (!billingClient.isReady()) {
                failureReason = FailureReason.PLAY_BILLING_UNAVAILABLE;
            } else if (failureReason == FailureReason.NONE) {
                failureReason = productDetails == null
                        ? FailureReason.PRODUCT_NOT_FOUND
                        : FailureReason.NO_ELIGIBLE_OFFER;
            }
            state = State.UNAVAILABLE;
            publish();
            start();
            return;
        }

        BillingFlowParams.ProductDetailsParams productParams =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(selectedOffer.getOfferToken())
                        .build();
        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(productParams))
                .build();
        BillingResult result = billingClient.launchBillingFlow(activity, flowParams);
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            handleBillingFailure("launch", result);
        }
    }

    public void close() {
        closed = true;
        listener = null;
        billingClient.endConnection();
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult,
                                   @Nullable List<Purchase> purchases) {
        int responseCode = billingResult.getResponseCode();
        if (responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            processPurchases(purchases, false);
        } else if (responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            queryPurchases();
        } else if (responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            handleBillingFailure("purchase update", billingResult);
        }
    }

    private void refreshFromPlay() {
        if (!owned) {
            failureReason = FailureReason.NONE;
            state = State.CONNECTING;
            publish();
        }
        queryProductDetails();
        queryPurchases();
    }

    private void queryProductDetails() {
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SUPPORTER_BADGE_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(product))
                .build();
        billingClient.queryProductDetailsAsync(params, (billingResult, result) -> {
            if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK
                    || result.getProductDetailsList().isEmpty()) {
                productDetails = null;
                selectedOffer = null;
                formattedPrice = null;
                handleProductLookupFailure(billingResult, result.getUnfetchedProductList());
                return;
            }
            productDetails = result.getProductDetailsList().get(0);
            selectedOffer = selectBuyOffer(productDetails);
            formattedPrice = selectedOffer == null ? null : selectedOffer.getFormattedPrice();
            if (!owned && state != State.PENDING) {
                if (selectedOffer == null) {
                    failureReason = FailureReason.NO_ELIGIBLE_OFFER;
                    state = State.UNAVAILABLE;
                } else {
                    failureReason = FailureReason.NONE;
                    state = State.AVAILABLE;
                }
            }
            publish();
        });
    }

    @Nullable
    private ProductDetails.OneTimePurchaseOfferDetails selectBuyOffer(ProductDetails details) {
        List<ProductDetails.OneTimePurchaseOfferDetails> offers =
                details.getOneTimePurchaseOfferDetailsList();
        if (offers != null) {
            for (ProductDetails.OneTimePurchaseOfferDetails offer : offers) {
                if (offer.getRentalDetails() == null) {
                    return offer;
                }
            }
        }
        return details.getOneTimePurchaseOfferDetails();
    }

    private void queryPurchases() {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        billingClient.queryPurchasesAsync(params, (billingResult, purchases) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases, true);
            } else {
                handleBillingFailure("purchase query", billingResult);
            }
        });
    }

    private void processPurchases(List<Purchase> purchases, boolean authoritativeQuery) {
        List<SupporterEntitlementResolver.Entry> entries = new ArrayList<>();
        Purchase supporterPurchase = null;
        for (Purchase purchase : purchases) {
            SupporterEntitlementResolver.PurchaseStatus purchaseStatus =
                    SupporterEntitlementResolver.PurchaseStatus.OTHER;
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                purchaseStatus = SupporterEntitlementResolver.PurchaseStatus.PURCHASED;
            } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                purchaseStatus = SupporterEntitlementResolver.PurchaseStatus.PENDING;
            }
            entries.add(new SupporterEntitlementResolver.Entry(purchase.getProducts(), purchaseStatus));
            if (purchaseStatus == SupporterEntitlementResolver.PurchaseStatus.PURCHASED
                    && purchase.getProducts().contains(SUPPORTER_BADGE_PRODUCT_ID)) {
                supporterPurchase = purchase;
            }
        }

        SupporterEntitlementResolver.Result result =
                SupporterEntitlementResolver.resolve(entries, SUPPORTER_BADGE_PRODUCT_ID);
        if (result == SupporterEntitlementResolver.Result.OWNED) {
            setOwned(true);
            failureReason = FailureReason.NONE;
            state = State.OWNED;
            publish();
            acknowledgeIfNeeded(supporterPurchase);
        } else if (result == SupporterEntitlementResolver.Result.PENDING) {
            if (authoritativeQuery) {
                setOwned(false);
            }
            failureReason = FailureReason.NONE;
            state = State.PENDING;
            publish();
        } else if (authoritativeQuery) {
            setOwned(false);
            if (selectedOffer != null) {
                failureReason = FailureReason.NONE;
                state = State.AVAILABLE;
            } else {
                // Product lookup runs in parallel with the purchase query. Do not
                // overwrite a completed product failure with CONNECTING forever.
                state = failureReason == FailureReason.NONE
                        ? State.CONNECTING
                        : State.UNAVAILABLE;
            }
            publish();
        }
    }

    private void handleProductLookupFailure(@NonNull BillingResult billingResult,
                                            @NonNull List<UnfetchedProduct> unfetchedProducts) {
        if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            handleBillingFailure("product query", billingResult);
            return;
        }

        failureReason = FailureReason.PRODUCT_NOT_FOUND;
        for (UnfetchedProduct unfetchedProduct : unfetchedProducts) {
            if (!SUPPORTER_BADGE_PRODUCT_ID.equals(unfetchedProduct.getProductId())) {
                continue;
            }
            switch (unfetchedProduct.getStatusCode()) {
                case UnfetchedProduct.StatusCode.INVALID_PRODUCT_ID_FORMAT:
                    failureReason = FailureReason.CONFIGURATION;
                    break;
                case UnfetchedProduct.StatusCode.NO_ELIGIBLE_OFFER:
                    failureReason = FailureReason.NO_ELIGIBLE_OFFER;
                    break;
                case UnfetchedProduct.StatusCode.PRODUCT_NOT_FOUND:
                    failureReason = FailureReason.PRODUCT_NOT_FOUND;
                    break;
                case UnfetchedProduct.StatusCode.UNKNOWN:
                default:
                    failureReason = FailureReason.UNKNOWN;
                    break;
            }
            Log.w(TAG, "Product unavailable: id=" + unfetchedProduct.getProductId()
                    + ", status=" + unfetchedProduct.getStatusCode());
            break;
        }
        state = owned ? State.OWNED : State.UNAVAILABLE;
        publish();
    }

    private void handleBillingFailure(@NonNull String operation,
                                      @NonNull BillingResult billingResult) {
        switch (billingResult.getResponseCode()) {
            case BillingClient.BillingResponseCode.NETWORK_ERROR:
            case BillingClient.BillingResponseCode.SERVICE_TIMEOUT:
                failureReason = FailureReason.NETWORK;
                break;
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
            case BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED:
            case BillingClient.BillingResponseCode.SERVICE_DISCONNECTED:
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
                failureReason = FailureReason.PLAY_BILLING_UNAVAILABLE;
                break;
            case BillingClient.BillingResponseCode.ITEM_UNAVAILABLE:
                failureReason = FailureReason.PRODUCT_NOT_FOUND;
                break;
            case BillingClient.BillingResponseCode.DEVELOPER_ERROR:
                failureReason = FailureReason.CONFIGURATION;
                break;
            default:
                failureReason = FailureReason.UNKNOWN;
                break;
        }
        state = owned ? State.OWNED : State.ERROR;
        Log.w(TAG, "Billing " + operation + " failed: code="
                + billingResult.getResponseCode() + ", message="
                + billingResult.getDebugMessage());
        publish();
    }

    private void acknowledgeIfNeeded(@Nullable Purchase purchase) {
        if (purchase == null || purchase.isAcknowledged()) {
            return;
        }
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();
        billingClient.acknowledgePurchase(params, billingResult -> {
            // An unacknowledged purchase remains queryable, so a later foreground sync retries.
            if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                state = State.OWNED;
                Log.w(TAG, "Purchase acknowledgement failed: code="
                        + billingResult.getResponseCode() + ", message="
                        + billingResult.getDebugMessage());
                publish();
            }
        });
    }

    private void setOwned(boolean owned) {
        this.owned = owned;
        preferences.edit().putBoolean(KEY_LAST_CONFIRMED_OWNED, owned).apply();
    }

    private void publish() {
        if (listener != null) {
            listener.onBillingStateChanged(
                    new Snapshot(state, owned, formattedPrice, failureReason));
        }
    }
}
