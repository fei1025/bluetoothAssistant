package com.zzf.bluetoothsmp.utils;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentSender;

import com.google.android.gms.tasks.Task;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

import androidx.appcompat.app.AlertDialog;

public class CheckUpdate {
    private int MY_REQUEST_CODE = 01;

    public void check(Context context) {
        AppUpdateManager appUpdateManager = AppUpdateManagerFactory.create(context);

        // Returns an intent object that you use to check for an update.
        //返回一个用于检查更新的意图对象。
        Task<AppUpdateInfo> appUpdateInfoTask = appUpdateManager.getAppUpdateInfo();
        // Checks that the platform will allow the specified type of update.
        // 检查平台是否允许指定类型的更新
        appUpdateInfoTask.addOnSuccessListener(appUpdateInfo -> {
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                // Request the update.
                AlertDialog.Builder dialog = new AlertDialog.Builder(context);
                dialog.setTitle("提示");
                dialog.setMessage("确定更新吗?");
                dialog.setCancelable(false);
                dialog.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                        try {
                            appUpdateManager.startUpdateFlowForResult(
                                    // Pass the intent that is returned by 'getAppUpdateInfo()'.
                                    appUpdateInfo,
                                    // Or 'AppUpdateType.FLEXIBLE' for flexible updates.
                                    AppUpdateType.IMMEDIATE,
                                    // The current activity making the update request.
                                    (Activity) context,
                                    // Include a request code to later monitor this update request.
                                    MY_REQUEST_CODE);
                        } catch (IntentSender.SendIntentException e) {
                            e.printStackTrace();
                        }

                        // Create a listener to track request state updates.
                        InstallStateUpdatedListener listener = state -> {
                            // (Optional) Provide a download progress bar.
                            if (state.installStatus() == InstallStatus.DOWNLOADING) {
                                //进度
                                long bytesDownloaded = state.bytesDownloaded();
                                //总共数据
                                long totalBytesToDownload = state.totalBytesToDownload();
                                // Implement progress bar.

                            }
                            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                                appUpdateManager.completeUpdate();
                            }
                        };
                        // 开始以前提供监听
                        appUpdateManager.registerListener(listener);

                        // 不需要的时候取消监听
                        appUpdateManager.unregisterListener(listener);
                    }
                });
                dialog.show();
            }
        });
    }

    public void tishi(String message, Context context) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(context);
        dialog.setTitle("提示");
        dialog.setMessage(message);
        dialog.setCancelable(false);
        dialog.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });

        dialog.show();
    }


}
