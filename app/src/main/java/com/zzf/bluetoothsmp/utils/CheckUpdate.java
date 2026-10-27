package com.zzf.bluetoothsmp.utils;

import android.app.Activity;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.zzf.bluetoothsmp.R;

/** Activity-owned IMMEDIATE update controller, registered before the Activity starts. */
public final class CheckUpdate implements AutoCloseable {
    private static final String TAG = "CheckUpdate";
    private final AppCompatActivity activity;
    private final AppUpdateManager manager;
    private final ActivityResultLauncher<IntentSenderRequest> launcher;
    private AlertDialog prompt;
    private boolean checking;
    private boolean flowRunning;
    private boolean closed;

    public CheckUpdate(AppCompatActivity activity) {
        this.activity = activity;
        manager = AppUpdateManagerFactory.create(activity.getApplicationContext());
        launcher = activity.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    flowRunning = false;
                    if (result.getResultCode() != Activity.RESULT_OK) {
                        // A canceled update leaves the utility usable; no immediate prompt loop.
                        Log.i(TAG, "In-app update canceled or did not complete");
                    }
                });
    }

    public void check() {
        query(false);
    }

    public void onResume() {
        // Google may leave an accepted IMMEDIATE update awaiting its UI after interruption.
        query(true);
    }

    private void query(boolean resumeOnly) {
        if (closed || checking || (flowRunning && !resumeOnly) || activity.isFinishing()) return;
        checking = true;
        manager.getAppUpdateInfo().addOnSuccessListener(info -> {
            checking = false;
            if (closed || activity.isFinishing() || activity.isDestroyed()) return;
            if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                flowRunning = manager.startUpdateFlowForResult(info, launcher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build());
            } else if (!resumeOnly && info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    && (prompt == null || !prompt.isShowing())) {
                prompt = new AlertDialog.Builder(activity)
                        .setTitle(R.string.tips).setMessage(R.string.updateApp)
                        .setPositiveButton(R.string.confirm, (dialog, which) -> {
                            if (!closed) {
                                flowRunning = manager.startUpdateFlowForResult(info, launcher,
                                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build());
                            }
                        })
                        .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                        .create();
                prompt.show();
            }
        }).addOnFailureListener(failure -> {
            checking = false;
            Log.i(TAG, "In-app update information is unavailable");
        });
    }

    @Override
    public void close() {
        closed = true;
        if (prompt != null) {
            prompt.dismiss();
            prompt = null;
        }
        launcher.unregister();
        // IMMEDIATE progress and installation belong to Google Play; no FLEXIBLE listener.
    }
}
