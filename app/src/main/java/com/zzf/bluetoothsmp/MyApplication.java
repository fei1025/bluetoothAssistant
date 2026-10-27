package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.PowerManager;

import com.zzf.bluetoothsmp.entity.KeyboardEntity;
import com.zzf.bluetoothsmp.entity.MessageMapper;
import com.zzf.bluetoothsmp.utils.LanguageUtils;

import org.litepal.LitePal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.List;


public class MyApplication  extends Application {
    @SuppressLint("StaticFieldLeak")
    private static Context context;
    private static Application mApplication;

    @Override
    public void onCreate() {
        super.onCreate();
        context=getApplicationContext();
        LanguageUtils.initialize(this);
        LitePal.initialize(this);
        com.zzf.bluetoothsmp.utils.HistoryRepository.initializeRetention(this);
        mApplication = this;
        StaticObject.reconnectManager.initialize(this);

        registerActivityLifecycleCallbacks();
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        ContextCompat.registerReceiver(this, new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    StaticObject.foregroundGate.screenUnavailable();
                    StaticObject.suspendForegroundBluetooth(MyApplication.this);
                } else {
                    resumeIfScreenAvailable();
                }
            }
        }, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        // Local debug/test runs share the production package name, so they must not
        // contaminate production Analytics or Crashlytics reports.
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        BluetoothTelemetry.initialize(this, !debuggable && !BuildConfig.OFFLINE_AUDIT);
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
                StaticObject.foregroundGate.activityStarted(isScreenAvailable());
                StaticObject.resumeForegroundBluetooth(activity);
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (!BluetoothPermissionUtils.hasConnectPermission(activity)) {
                    // Permission revocation can happen while a chat Activity is alive.
                    StaticObject.stopBluetoothService();
                    StaticObject.closeAllConnections(true);
                } else {
                    resumeIfScreenAvailable();
                }
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {

            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                boolean wasActive = StaticObject.foregroundGate.isActive();
                StaticObject.foregroundGate.activityStopped(activity.isChangingConfigurations());
                if (wasActive && !StaticObject.foregroundGate.isActive()) {
                    StaticObject.suspendForegroundBluetooth(activity);
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
        return LanguageUtils.localizedContext(mApplication);
    }
    public static Context getContext(){
        return LanguageUtils.localizedContext(context);
    }

    public static boolean isAppInForeground() {
        return StaticObject.foregroundGate.isActive();
    }

    private boolean isScreenAvailable() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return power != null && power.isInteractive()
                && (keyguard == null || !keyguard.isKeyguardLocked());
    }

    private void resumeIfScreenAvailable() {
        if (isScreenAvailable()) {
            StaticObject.foregroundGate.screenAvailable();
            StaticObject.resumeForegroundBluetooth(this);
        } else if (StaticObject.foregroundGate.isActive()) {
            StaticObject.foregroundGate.screenUnavailable();
            StaticObject.suspendForegroundBluetooth(this);
        }
    }
}
