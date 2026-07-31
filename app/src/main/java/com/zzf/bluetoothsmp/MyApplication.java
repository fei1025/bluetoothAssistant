package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.zzf.bluetoothsmp.entity.KeyboardEntity;
import com.zzf.bluetoothsmp.entity.MessageMapper;
import com.zzf.bluetoothsmp.utils.LanguageUtils;

import org.litepal.LitePal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


public class MyApplication  extends Application {
    @SuppressLint("StaticFieldLeak")
    private static Context context;
    private static Application mApplication;
    private FirebaseAnalytics mFirebaseAnalytics;
    private static final AtomicInteger startedActivities = new AtomicInteger();

    @Override
    public void onCreate() {
        super.onCreate();
        context=getApplicationContext();
        LitePal.initialize(this);
        mApplication = this;
        StaticObject.reconnectManager.initialize(this);

        registerActivityLifecycleCallbacks();
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);
        mFirebaseAnalytics.setAnalyticsCollectionEnabled(true);
        BluetoothTelemetry.initialize(this);
    }



    private void registerActivityLifecycleCallbacks() {
        mApplication.registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                // 对Application和Activity更新上下文的语言环境
                LanguageUtils.applyAppLanguage(activity);
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                startedActivities.incrementAndGet();
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (!BluetoothPermissionUtils.hasConnectPermission(activity)) {
                    // Permission revocation can happen while a chat Activity or the
                    // foreground service is alive. Close transport resources before
                    // the next write/read reaches a platform SecurityException.
                    StaticObject.closeAllConnections(true);
                    StaticObject.stopBluetoothService();
                    BluetoothConnectionForegroundService.stop(activity);
                }
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {

            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                int remaining = startedActivities.decrementAndGet();
                if (remaining < 0) {
                    startedActivities.set(0);
                }
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {

            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {

            }
        });
    }

    public static Context getAppContext() {
        return mApplication;
    }
    public static Context getContext(){
        return context;
    }

    public static boolean isAppInForeground() {
        return startedActivities.get() > 0;
    }
}
