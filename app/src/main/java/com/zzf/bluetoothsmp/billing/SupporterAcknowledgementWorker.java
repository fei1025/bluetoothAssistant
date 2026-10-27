package com.zzf.bluetoothsmp.billing;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Short background billing work; never opens Bluetooth, launches purchases or grants entitlement. */
public final class SupporterAcknowledgementWorker extends Worker {
    private static final String TAG = "SupporterAck";
    private static final String KEY = "purchase_key";
    private static final Object CLIENT_LOCK = new Object();
    private static final ExecutorService PERSISTENCE = Executors.newSingleThreadExecutor();
    private static final Set<String> PERSISTING = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public SupporterAcknowledgementWorker(@NonNull Context context,
                                         @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    static void enqueue(Context context, String token) {
        Context app = context.getApplicationContext();
        String identity = SupporterAcknowledgementPolicy.tokenKey(token);
        if (!PERSISTING.add(identity)) return;
        PERSISTENCE.execute(() -> {
            try {
                SupporterAcknowledgementStore store = new SupporterAcknowledgementStore(app);
                // commit completes on this IO thread before WorkManager scheduling.
                schedule(app, store.remember(token, System.currentTimeMillis()));
            } catch (RuntimeException failure) {
                Log.w(TAG, "Purchase acknowledgement persistence/scheduling could not complete");
            } finally { PERSISTING.remove(identity); }
        });
    }

    static void restorePending(Context context) {
        Context app = context.getApplicationContext();
        PERSISTENCE.execute(() -> {
            try {
                for (String key : new SupporterAcknowledgementStore(app).keys()) schedule(app, key);
            } catch (RuntimeException failure) {
                Log.w(TAG, "Purchase acknowledgement recovery could not complete");
            }
        });
    }

    private static void schedule(Context context, String key) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(SupporterAcknowledgementWorker.class)
                .setInputData(new Data.Builder().putString(KEY, key).build())
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork("supporter_ack_" + key,
                ExistingWorkPolicy.KEEP, work);
    }

    @NonNull
    @Override
    public Result doWork() {
        String key = getInputData().getString(KEY);
        if (key == null) return Result.failure();
        SupporterAcknowledgementStore store = new SupporterAcknowledgementStore(getApplicationContext());
        String token = store.token(key);
        if (token == null) return Result.success();
        if (!SupporterAcknowledgementPolicy.canRetry(getRunAttemptCount(),
                store.firstSeen(key), System.currentTimeMillis())) {
            store.forget(key);
            Log.w(TAG, "Purchase acknowledgement retry limit reached; foreground Play sync remains available");
            return Result.failure();
        }
        // Separate token jobs must not create multiple background BillingClient connections.
        synchronized (CLIENT_LOCK) {
            if (isStopped()) return Result.retry();
            return acknowledge(store, key, token);
        }
    }

    private Result acknowledge(SupporterAcknowledgementStore store, String key, String token) {
        BillingClient client = BillingClient.newBuilder(getApplicationContext())
                .setListener((result, purchases) -> { /* UI owner handles purchase events. */ })
                .enableAutoServiceReconnection()
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build();
        try {
            AtomicInteger code = new AtomicInteger(BillingClient.BillingResponseCode.SERVICE_TIMEOUT);
            CountDownLatch connected = new CountDownLatch(1);
            client.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(@NonNull BillingResult result) {
                    code.set(result.getResponseCode());
                    connected.countDown();
                }

                @Override
                public void onBillingServiceDisconnected() {
                    code.set(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED);
                    connected.countDown();
                }
            });
            if (!connected.await(30, TimeUnit.SECONDS) || isStopped()) return Result.retry();
            if (code.get() != BillingClient.BillingResponseCode.OK) return resultFor(code.get());

            AtomicReference<List<Purchase>> found = new AtomicReference<>();
            CountDownLatch queried = new CountDownLatch(1);
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP).build(), (result, purchases) -> {
                code.set(result.getResponseCode());
                found.set(purchases);
                queried.countDown();
            });
            if (!queried.await(30, TimeUnit.SECONDS) || isStopped()) return Result.retry();
            if (code.get() != BillingClient.BillingResponseCode.OK) return resultFor(code.get());
            Purchase matching = null;
            if (found.get() == null) return Result.retry();
            for (Purchase purchase : found.get()) {
                if (token.equals(purchase.getPurchaseToken())
                        && purchase.getProducts().contains(SupporterBillingManager.SUPPORTER_BADGE_PRODUCT_ID)) {
                    matching = purchase;
                    break;
                }
            }
            // Successful Play query confirms refund/revocation or already-processed purchase.
            if (matching == null || matching.isAcknowledged()) {
                store.forget(key);
                return Result.success();
            }
            if (matching.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return Result.retry();

            CountDownLatch acknowledged = new CountDownLatch(1);
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(token).build(), result -> {
                code.set(result.getResponseCode());
                acknowledged.countDown();
            });
            if (!acknowledged.await(30, TimeUnit.SECONDS) || isStopped()) return Result.retry();
            if (code.get() == BillingClient.BillingResponseCode.OK) {
                store.forget(key);
                return Result.success();
            }
            return resultFor(code.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (RuntimeException failure) {
            // Do not log SDK exception messages or purchase tokens.
            Log.w(TAG, "Purchase acknowledgement could not complete");
            return Result.retry();
        } finally {
            client.endConnection();
        }
    }

    private Result resultFor(int code) {
        Log.w(TAG, "Purchase acknowledgement returned code=" + code);
        return SupporterAcknowledgementPolicy.isTransient(code) ? Result.retry() : Result.failure();
    }
}
