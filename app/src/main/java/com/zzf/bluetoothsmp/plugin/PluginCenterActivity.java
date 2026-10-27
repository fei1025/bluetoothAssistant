package com.zzf.bluetoothsmp.plugin;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import com.zzf.bluetoothsmp.BuildConfig;
import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.base.BaseActivity;
import org.json.*;
import java.util.*;

public final class PluginCenterActivity extends BaseActivity {
    private PluginCenterModel model;
    private LinearLayout root, contents;
    private final List<Button> actions = new ArrayList<>();
    private final ActivityResultLauncher<String[]> importer = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri != null && model != null) confirmInstall(getString(R.string.plugin_import), () -> model.installUri(uri));
    });
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        model = new ViewModelProvider(this).get(PluginCenterModel.class);
        root = PluginViews.column(this);
        PluginViews.button(root, R.string.plugin_back, this::finish);
        root.addView(PluginViews.text(this, getString(R.string.plugin_center), 24));
        ScrollView scroll = new ScrollView(this); contents = PluginViews.column(this); scroll.addView(contents);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); PluginViews.insets(root);
        model.state.observe(this, this::render);
    }
    @Override protected void onResume() { super.onResume(); if (model != null) model.reload(); }
    private void render(PluginCenterModel.State state) {
        contents.removeAllViews(); actions.clear();
        if (BuildConfig.PLUGIN_DEVELOPER_MODE) contents.addView(PluginViews.text(this, getString(R.string.plugin_developer_mode), 14));
        contents.addView(PluginViews.text(this, getString(R.string.plugin_install_notice), 14));
        actions.add(PluginViews.button(contents, R.string.plugin_import, () -> importer.launch(new String[]{"*/*"})));
        actions.add(PluginViews.button(contents, R.string.plugin_download_url, this::downloadDialog));
        actions.add(PluginViews.button(contents, R.string.plugin_refresh, model::refresh));
        actions.add(PluginViews.button(contents, R.string.plugin_example_classic, () -> confirmInstall(getString(R.string.plugin_example_classic), () -> model.installBundled("classic"))));
        actions.add(PluginViews.button(contents, R.string.plugin_example_ble, () -> confirmInstall(getString(R.string.plugin_example_ble), () -> model.installBundled("ble"))));
        if (state.busy) contents.addView(new ProgressBar(this));
        if (!state.result.isEmpty()) {
            String result = state.result.equals("OK") ? getString(R.string.plugin_operation_ok)
                    : getString(R.string.plugin_operation_error, state.result);
            TextView status = PluginViews.text(this, result, 14); status.setId(R.id.plugin_center_status); contents.addView(status);
        }
        contents.addView(PluginViews.text(this, getString(R.string.plugin_installed), 20));
        Map<String, PluginStore.Installed> installed = new HashMap<>();
        for (PluginStore.Installed plugin : state.installed) {
            installed.put(plugin.manifest.id, plugin);
            contents.addView(PluginViews.text(this, plugin.manifest.name + " · v" + plugin.manifest.version + "\n" + plugin.manifest.author, 17));
            actions.add(PluginViews.button(contents, R.string.plugin_details, () -> detail(plugin)));
            Button run = PluginViews.button(contents, R.string.plugin_run, () -> startActivity(new Intent(this, PluginActivity.class).putExtra("pluginId", plugin.manifest.id)));
            if (new PluginStore(this).isRevoked(plugin.digest)) { run.setEnabled(false); contents.addView(PluginViews.text(this, "VERSION_REVOKED", 14)); }
            else actions.add(run);
        }
        if (installed.isEmpty()) contents.addView(PluginViews.text(this, getString(R.string.plugin_empty), 14));
        contents.addView(PluginViews.text(this, getString(R.string.plugin_catalogue), 20));
        if (state.catalogue != null) for (int i = 0; i < state.catalogue.length(); i++) {
            JSONObject entry = state.catalogue.optJSONObject(i); if (entry == null) continue;
            String id = entry.optString("id"); int version = entry.optInt("version");
            PluginStore.Installed current = installed.get(id);
            contents.addView(PluginViews.text(this, entry.optString("name") + " · v" + version, 17));
            if (current == null || current.manifest.version < version) actions.add(PluginViews.button(contents,
                    current == null ? R.string.plugin_install : R.string.plugin_update, () -> new AlertDialog.Builder(this)
                            .setTitle(entry.optString("name")).setMessage(R.string.plugin_update_notice)
                            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm,
                                    (dialog, which) -> model.installEntry(entry)).show()));
        }
        for (Button action : actions) action.setEnabled(!state.busy);
    }
    private void downloadDialog() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://…/plugin.btplugin");
        new AlertDialog.Builder(this).setTitle(R.string.plugin_download_url).setMessage(R.string.plugin_update_notice).setView(input)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm,
                        (dialog, which) -> model.installUrl(input.getText().toString().trim(), null)).show();
    }
    private void confirmInstall(String title, Runnable install) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(R.string.plugin_update_notice)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm, (dialog, which) -> install.run()).show();
    }
    private void detail(PluginStore.Installed plugin) {
        String text = getString(R.string.plugin_detail_text, plugin.manifest.id, plugin.manifest.author, plugin.manifest.version,
                PluginActivity.capabilityText(this, plugin.manifest.capabilities), plugin.author);
        new AlertDialog.Builder(this).setTitle(plugin.manifest.name).setMessage(text)
                .setPositiveButton(R.string.plugin_uninstall, (dialog, which) -> new AlertDialog.Builder(this)
                        .setMessage(R.string.plugin_uninstall_notice).setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.confirm, (ignored, button) -> model.uninstall(plugin)).show())
                .setNeutralButton(R.string.plugin_clear_data, (dialog, which) -> new AlertDialog.Builder(this)
                        .setMessage(R.string.plugin_clear_notice).setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.confirm, (ignored, button) -> model.clear(plugin)).show())
                .setNegativeButton(R.string.cancel, null).show();
    }
}
