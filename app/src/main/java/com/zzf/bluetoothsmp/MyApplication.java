package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import com.zzf.bluetoothsmp.utils.LanguageUtils;

import org.litepal.LitePal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


public class MyApplication  extends Application {
    @SuppressLint("StaticFieldLeak")
    private static Context context;
    private static Application mApplication;

    @Override
    public void onCreate() {
        super.onCreate();
        context=getApplicationContext();
        LitePal.initialize(this);
        mApplication = this;
        registerActivityLifecycleCallbacks();



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

            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {

            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {

            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {

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
}
