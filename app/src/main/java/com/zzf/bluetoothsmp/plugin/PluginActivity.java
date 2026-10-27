package com.zzf.bluetoothsmp.plugin;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.base.BaseActivity;
import java.util.*;

/** Native controls live outside WebView and cannot be covered by plugin HTML. */
public final class PluginActivity extends BaseActivity implements PluginRuntime.Ui {
    private PluginRuntime runtime;
    private FrameLayout surface;
    private TextView status;
    private AlertDialog prompt;
    private PluginRuntime.Consent permissionResult;
    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                PluginRuntime.Consent callback = permissionResult; permissionResult = null;
                if (callback != null) { boolean allowed = true; for (boolean granted : result.values()) allowed &= granted; callback.result(allowed); }
            });
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        runtime = new ViewModelProvider(this).get(PluginRuntime.class);
        LinearLayout root = PluginViews.column(this);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setOrientation(LinearLayout.HORIZONTAL);
        Button stop = new androidx.appcompat.widget.AppCompatButton(this); stop.setText(R.string.plugin_stop); stop.setId(R.id.plugin_stop);
        stop.setOnClickListener(view -> { runtime.stop(); finish(); }); toolbar.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        Button disconnect = new androidx.appcompat.widget.AppCompatButton(this); disconnect.setText(R.string.plugin_disconnect);
        disconnect.setOnClickListener(view -> { runtime.suspend(); runtime.resume(); }); toolbar.addView(disconnect, new LinearLayout.LayoutParams(0, -2, 1));
        Button revoke = new androidx.appcompat.widget.AppCompatButton(this); revoke.setText(R.string.plugin_revoke);
        revoke.setOnClickListener(view -> { runtime.revoke(); status(getString(R.string.plugin_revoked)); }); toolbar.addView(revoke, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar); status = PluginViews.text(this, getString(R.string.plugin_loading), 14); status.setId(R.id.plugin_runtime_status); root.addView(status);
        surface = new FrameLayout(this); root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); PluginViews.insets(root);
        runtime.attach(this, this);
        runtime.open(getIntent().getStringExtra("pluginId"), () -> {
            if (isDestroyed()) return;
            try {
                WebView web = runtime.view(this); web.setId(R.id.plugin_webview);
                if (web.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) web.getParent()).removeView(web);
                surface.addView(web, new FrameLayout.LayoutParams(-1, -1));
                runtime.updateStatus(); runtime.resume();
            } catch (Exception error) { status(error.getMessage()); }
        }, error -> status(error.getMessage()));
    }
    @Override protected void onResume() { super.onResume(); if (runtime != null) { runtime.attach(this, this); runtime.resume(); } }
    @Override protected void onStop() { if (runtime != null && !isChangingConfigurations()) runtime.suspend(); super.onStop(); }
    @Override protected void onDestroy() {
        if (prompt != null) prompt.dismiss();
        if (permissionResult != null) { permissionResult.result(false); permissionResult = null; }
        if (runtime != null) {
            if (isChangingConfigurations()) { surface.removeAllViews(); runtime.detach(); }
            else runtime.stop();
        }
        super.onDestroy();
    }
    @Override public void status(String value) { status.setText(value); }
    public static String capabilityText(Context context, Collection<String> values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            int resource;
            switch (value) {
                case "classic.scan": resource = R.string.plugin_cap_classic_scan; break;
                case "classic.connect": resource = R.string.plugin_cap_classic_connect; break;
                case "classic.io": resource = R.string.plugin_cap_classic_io; break;
                case "ble.scan": resource = R.string.plugin_cap_ble_scan; break;
                case "ble.connect": resource = R.string.plugin_cap_ble_connect; break;
                default: resource = R.string.plugin_cap_ble_gatt;
            }
            if (text.length() > 0) text.append('\n'); text.append("• ").append(context.getString(resource));
        }
        return text.toString();
    }
    @Override public void capabilities(PluginStore.Installed plugin, Set<String> capabilities, PluginRuntime.Consent result) {
        final boolean[] answered = {false};
        prompt = new AlertDialog.Builder(this).setTitle(R.string.plugin_authorize)
                .setMessage(getString(R.string.plugin_authorize_text, plugin.manifest.name, capabilityText(this, capabilities)))
                .setNegativeButton(R.string.cancel, (dialog, which) -> { answered[0] = true; result.result(false); })
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    answered[0] = true;
                    permissionResult = result;
                    boolean scans = plugin.manifest.capabilities.contains("classic.scan") || plugin.manifest.capabilities.contains("ble.scan");
                    java.util.List<String> permissions = new java.util.ArrayList<>();
                    if (Build.VERSION.SDK_INT >= 31) {
                        permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
                        if (scans) permissions.add(Manifest.permission.BLUETOOTH_SCAN);
                    } else if (scans) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
                    if (permissions.isEmpty()) { permissionResult = null; result.result(true); }
                    else permissionLauncher.launch(permissions.toArray(new String[0]));
                }).create();
        prompt.setOnDismissListener(dialog -> { prompt = null; if (!answered[0]) result.result(false); }); prompt.show();
    }
    @Override public void device(PluginStore.Installed plugin, String transport, String address, PluginRuntime.Consent result) {
        final boolean[] answered = {false};
        prompt = new AlertDialog.Builder(this).setTitle(R.string.plugin_device_authorize)
                .setMessage(getString(R.string.plugin_device_authorize_text, plugin.manifest.name, transport.equals("ble") ? "BLE" : "SPP", address))
                .setNegativeButton(R.string.cancel, (dialog, which) -> { answered[0] = true; result.result(false); })
                .setPositiveButton(R.string.confirm, (dialog, which) -> { answered[0] = true; result.result(true); }).create();
        prompt.setOnDismissListener(dialog -> { prompt = null; if (!answered[0]) result.result(false); }); prompt.show();
    }
}
