package com.zzf.bluetoothsmp.ble;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.zzf.bluetoothsmp.BluetoothPermissionUtils;
import com.zzf.bluetoothsmp.BluetoothTextEncoding;
import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.base.BaseActivity;
import com.zzf.bluetoothsmp.databinding.ActivityBleBinding;
import com.zzf.bluetoothsmp.utils.HexUtils;

import java.text.SimpleDateFormat;
import java.util.*;

@SuppressLint("MissingPermission")
public final class BleActivity extends BaseActivity {
    private ActivityBleBinding binding;
    private BleClient client;
    private BleExportState export;
    private final RowAdapter devices = new RowAdapter();
    private final RowAdapter attributes = new RowAdapter();
    private final RowAdapter traffic = new RowAdapter();
    private List<BluetoothGattService> shownServices = Collections.emptyList();
    private final Set<String> expanded = new HashSet<>();
    private boolean pendingScan;
    private AlertDialog dialog;
    private BluetoothGattCharacteristic shownWriteTarget;
    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (BluetoothPermissionUtils.hasDiscoveryPermissions(this) && pendingScan) ensureScan();
                else { pendingScan = false; message(R.string.ble_permission_needed); }
            });
    private final ActivityResultLauncher<Intent> enableBluetooth = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && pendingScan) ensureScan();
                else pendingScan = false;
            });
    private final ActivityResultLauncher<String> document = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/tab-separated-values"), uri -> export.write(uri));

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        client = new ViewModelProvider(this).get(BleClient.class);
        export = new ViewModelProvider(this).get(BleExportState.class);
        binding = ActivityBleBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.bleRoot, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            view.setPadding(bars.left, bars.top, bars.right, bottom);
            return insets;
        });
        binding.bleToolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        binding.bleToolbar.setNavigationContentDescription(R.string.ble_back);
        binding.bleToolbar.setNavigationOnClickListener(view -> finish());
        setupList(binding.bleDevices, devices);
        setupList(binding.bleServices, attributes);
        setupList(binding.bleLogs, traffic);
        int[] titles = {R.string.ble_tab_scan, R.string.ble_tab_services, R.string.ble_tab_terminal};
        for (int title : titles) binding.bleTabs.addTab(binding.bleTabs.newTab().setText(title));
        binding.bleTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showPanel(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        spinner(binding.bleEncoding, new String[]{"HEX", "UTF-8", "ASCII", "GBK"});
        spinner(binding.bleSuffix, new String[]{getString(R.string.ble_no_suffix), "CRLF", "LF", "CR"});
        binding.bleScan.setOnClickListener(view -> { if (client.isScanning()) client.stopScan(); else ensureScan(); });
        TextWatcher filter = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { renderDevices(); }
            @Override public void afterTextChanged(Editable text) { }
        };
        binding.bleFilter.addTextChangedListener(filter);
        binding.bleMinRssi.addTextChangedListener(filter);
        binding.bleConnection.setOnClickListener(view -> {
            if (client.hasConnection()) client.disconnect("USER_DISCONNECTED");
            else if (BluetoothPermissionUtils.hasConnectPermission(this)) client.reconnect();
            else message(R.string.ble_permission_needed);
        });
        binding.bleRssi.setOnClickListener(view -> client.readRssi());
        binding.bleMtu.setOnClickListener(view -> numberDialog(R.string.ble_mtu_prompt, "247", 23, 517, client::requestMtu));
        binding.bleUart.setOnClickListener(view -> {
            if (client.autoUart()) { updateWriteMode(); selectTab(2); }
            else message(R.string.ble_uart_missing);
        });
        binding.blePair.setOnClickListener(view -> pair());
        binding.blePriority.setOnClickListener(view -> {
            dialog = new AlertDialog.Builder(this).setTitle(R.string.ble_priority)
                    .setItems(new String[]{getString(R.string.ble_balanced), getString(R.string.ble_high), getString(R.string.ble_low_power)},
                            (d, which) -> client.priority(which)).show();
        });
        binding.bleSend.setOnClickListener(view -> send(false));
        binding.bleRepeat.setOnClickListener(view -> {
            if (client.isRepeating()) client.stopRepeat(); else send(true);
        });
        binding.bleHexLog.setOnCheckedChangeListener((button, checked) -> renderLogs());
        binding.blePauseLog.setOnCheckedChangeListener((button, checked) -> { if (!checked) renderLogs(); });
        binding.bleClear.setOnClickListener(view -> { client.logs.clear(); client.droppedRx.set(0); renderLogs(); });
        binding.bleExport.setOnClickListener(view -> export.prepare(client.logs.snapshot(),
                client.logs.dropped() + client.droppedRx.get(), client.address()));
        if (state != null) {
            pendingScan = state.getBoolean("pendingScan");
            expanded.addAll(state.getStringArrayList("expanded") == null ? Collections.emptyList() : state.getStringArrayList("expanded"));
            selectTab(state.getInt("tab", 0));
        }
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) message(R.string.ble_unsupported);
    }

    @Override protected void onStart() {
        super.onStart();
        client.setListener(this::render);
        export.listen(this::exportChanged);
    }
    @Override protected void onResume() { super.onResume(); render(); exportChanged(); }
    @Override protected void onStop() {
        client.setListener(null);
        export.listen(null);
        if (!isChangingConfigurations()) client.suspend();
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        out.putBoolean("pendingScan", pendingScan);
        out.putStringArrayList("expanded", new ArrayList<>(expanded));
        out.putInt("tab", binding.bleTabs.getSelectedTabPosition());
        super.onSaveInstanceState(out);
    }

    private void ensureScan() {
        pendingScan = true;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) { pendingScan = false; message(R.string.ble_unsupported); return; }
        if (!BluetoothPermissionUtils.hasDiscoveryPermissions(this)) {
            permissions.launch(BluetoothPermissionUtils.discoveryPermissions()); return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) { pendingScan = false; message(R.string.ble_unsupported); return; }
        try {
            if (!adapter.isEnabled()) { enableBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return; }
            if (Build.VERSION.SDK_INT < 31) {
                LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
                boolean enabled = location != null && (location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
                if (!enabled) {
                    pendingScan = false;
                    dialog = new AlertDialog.Builder(this).setMessage(R.string.ble_location_needed)
                            .setPositiveButton(R.string.ble_settings, (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                            .setNegativeButton(android.R.string.cancel, null).show();
                    return;
                }
            }
            pendingScan = false;
            client.scan();
        } catch (RuntimeException failure) { pendingScan = false; message(R.string.ble_permission_needed); }
    }

    private void showPanel(int position) {
        binding.bleScanPanel.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        binding.bleServicesPanel.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        binding.bleTerminalPanel.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        if (position == 2 && !binding.blePauseLog.isChecked()) renderLogs();
    }
    private void selectTab(int position) { TabLayout.Tab tab = binding.bleTabs.getTabAt(position); if (tab != null) tab.select(); showPanel(position); }
    private void spinner(Spinner view, String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.setAdapter(adapter);
    }
    private void setupList(RecyclerView list, RowAdapter adapter) { list.setLayoutManager(new LinearLayoutManager(this)); list.setAdapter(adapter); }

    private void render() {
        if (binding == null) return;
        binding.bleStatus.setText(getString(R.string.ble_status, getString(client.state), client.address(), client.mtu,
                client.rssi, client.txBytes, client.rxBytes, client.pendingCount())
                + (client.lastError.isEmpty() ? "" : "\n" + client.lastError));
        binding.bleScan.setText(client.isScanning() ? R.string.ble_stop : R.string.ble_scan);
        binding.bleConnection.setText(client.hasConnection() ? R.string.ble_disconnect : R.string.ble_reconnect);
        binding.bleConnection.setEnabled(!client.address().isEmpty());
        boolean ready = client.isReady();
        binding.bleMtu.setEnabled(ready);
        binding.bleRssi.setEnabled(ready);
        binding.bleUart.setEnabled(ready);
        binding.blePair.setEnabled(ready);
        binding.blePriority.setEnabled(ready);
        binding.bleSend.setEnabled(ready && client.writeTarget != null);
        binding.bleRepeat.setEnabled(ready && client.writeTarget != null);
        binding.bleRepeat.setText(client.isRepeating() ? R.string.ble_stop : R.string.ble_repeat);
        binding.bleTxTarget.setText(client.writeTarget == null ? getString(R.string.ble_select_tx) : getString(R.string.ble_tx_target, BleClient.key(client.writeTarget)));
        if (shownWriteTarget != client.writeTarget) updateWriteMode();
        renderDevices();
        List<BluetoothGattService> services = client.services();
        if (!shownServices.equals(services)) { shownServices = new ArrayList<>(services); renderServices(); }
        // Subscription state may change without a service-list change.
        else if (binding.bleTabs.getSelectedTabPosition() == 1) renderServices();
        if (binding.bleTabs.getSelectedTabPosition() == 2 && !binding.blePauseLog.isChecked()) renderLogs();
    }

    private void renderDevices() {
        if (binding == null) return;
        String query = binding.bleFilter.getText().toString().trim().toLowerCase(Locale.ROOT);
        int min = -127;
        try { if (binding.bleMinRssi.length() > 0) min = Integer.parseInt(binding.bleMinRssi.getText().toString()); }
        catch (NumberFormatException ignored) { }
        List<ScanResult> results = new ArrayList<>(client.devices.values());
        Collections.sort(results, (a, b) -> Integer.compare(b.getRssi(), a.getRssi()));
        List<Row> rows = new ArrayList<>();
        for (ScanResult result : results) {
            String summary = deviceName(result) + "\n" + result.getDevice().getAddress() + "  " + result.getRssi() + " dBm";
            ScanRecord record = result.getScanRecord();
            String services = record == null ? "" : String.valueOf(record.getServiceUuids());
            if (result.getRssi() < min || !(summary + services).toLowerCase(Locale.ROOT).contains(query)) continue;
            boolean connectable = Build.VERSION.SDK_INT < 26 || result.isConnectable();
            rows.add(new Row(summary + (connectable ? "" : "\n" + getString(R.string.ble_not_connectable)), () -> {
                if (!connectable) { showAdvertisement(result); return; }
                if (client.hasConnection()) {
                    dialog = new AlertDialog.Builder(this).setMessage(R.string.ble_replace_connection)
                            .setPositiveButton(android.R.string.ok, (d, w) -> connect(result)).setNegativeButton(android.R.string.cancel, null).show();
                } else connect(result);
            }, () -> showAdvertisement(result)));
        }
        devices.set(rows);
        binding.bleScanHint.setText(rows.isEmpty() ? R.string.ble_scan_empty : R.string.ble_scan_hint);
    }

    private String deviceName(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        String name = record == null ? null : record.getDeviceName();
        return name == null || name.isEmpty() ? getString(R.string.ble_unnamed) : name;
    }
    private void connect(ScanResult result) {
        client.connect(result.getDevice());
        shownServices = Collections.emptyList();
        expanded.clear();
        shownWriteTarget = null;
        renderServices();
        selectTab(1);
    }
    private void showAdvertisement(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        StringBuilder details = new StringBuilder(result.getDevice().getAddress()).append("\nRSSI: ").append(result.getRssi()).append(" dBm\n");
        if (Build.VERSION.SDK_INT >= 26) details.append("Connectable: ").append(result.isConnectable()).append("\n");
        if (record != null) {
            details.append("Flags: ").append(record.getAdvertiseFlags()).append("\nTX power: ").append(record.getTxPowerLevel()).append("\nServices: ").append(record.getServiceUuids()).append("\n");
            android.util.SparseArray<byte[]> manufacturers = record.getManufacturerSpecificData();
            for (int i = 0; i < manufacturers.size(); i++) details.append(String.format(Locale.ROOT, "Manufacturer 0x%04X: ", manufacturers.keyAt(i))).append(HexUtils.bytesToHex(manufacturers.valueAt(i))).append("\n");
            for (Map.Entry<ParcelUuid, byte[]> entry : record.getServiceData().entrySet()) details.append("Service data ").append(entry.getKey()).append(": ").append(HexUtils.bytesToHex(entry.getValue())).append("\n");
            details.append("RAW: ").append(HexUtils.bytesToHex(record.getBytes()));
        }
        TextView text = new TextView(this);
        text.setText(details);
        text.setTextIsSelectable(true);
        text.setPadding(24, 16, 24, 16);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        dialog = new AlertDialog.Builder(this).setTitle(deviceName(result)).setView(scroll).setPositiveButton(android.R.string.ok, null).show();
    }

    private void renderServices() {
        List<Row> rows = new ArrayList<>();
        for (BluetoothGattService service : client.services()) {
            String id = service.getUuid() + "#" + service.getInstanceId();
            rows.add(new Row((expanded.contains(id) ? "▾ " : "▸ ") + getString(R.string.ble_service) + " #" + service.getInstanceId() + "\n" + service.getUuid(), () -> {
                if (!expanded.add(id)) expanded.remove(id);
                renderServices();
            }, null));
            if (!expanded.contains(id)) continue;
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                String key = BleClient.key(characteristic);
                String label = "    " + getString(R.string.ble_characteristic) + " #" + characteristic.getInstanceId() + "\n" + characteristic.getUuid() + "\n" + properties(characteristic.getProperties())
                        + (client.writeTarget == characteristic ? "  [TX]" : "") + (client.subscribed.contains(characteristic) ? "  [RX]" : "");
                rows.add(new Row(label, () -> characteristicActions(characteristic), null));
                for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
                    rows.add(new Row("        " + getString(R.string.ble_descriptor) + "\n" + descriptor.getUuid(), () -> {
                        if (!client.isReady()) return;
                        dialog = new AlertDialog.Builder(this).setTitle(descriptor.getUuid().toString())
                                .setMessage(key + "\n" + getString(R.string.ble_descriptor_hint))
                                .setPositiveButton(R.string.ble_read, (d, w) -> client.read(descriptor)).setNegativeButton(android.R.string.cancel, null).show();
                    }, null));
                }
            }
        }
        if (rows.isEmpty()) rows.add(new Row(getString(R.string.ble_no_services), null, null));
        attributes.set(rows);
    }

    private String properties(int flags) {
        StringBuilder value = new StringBuilder();
        int[] masks = {2, 8, 4, 16, 32, 1, 64, 128};
        String[] labels = {"READ", "WRITE", "WRITE_NO_RESPONSE", "NOTIFY", "INDICATE", "BROADCAST", "SIGNED_WRITE", "EXTENDED"};
        for (int i = 0; i < masks.length; i++) if ((flags & masks[i]) != 0) value.append(labels[i]).append(' ');
        return value.toString();
    }

    private void characteristicActions(BluetoothGattCharacteristic characteristic) {
        if (!client.isReady()) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int flags = characteristic.getProperties();
        if ((flags & 2) != 0) addAction(labels, actions, R.string.ble_read, () -> { client.read(characteristic); selectTab(2); });
        if ((flags & (8 | 4)) != 0) addAction(labels, actions, R.string.ble_use_tx, () -> {
            client.selectWrite(characteristic); updateWriteMode(); renderServices(); selectTab(2);
        });
        if ((flags & 16) != 0) addAction(labels, actions, R.string.ble_notify, () -> client.subscribe(characteristic, 1));
        if ((flags & 32) != 0) addAction(labels, actions, R.string.ble_indicate, () -> client.subscribe(characteristic, 2));
        if ((flags & (16 | 32)) != 0) addAction(labels, actions, R.string.ble_unsubscribe, () -> client.subscribe(characteristic, 0));
        dialog = new AlertDialog.Builder(this).setTitle(characteristic.getUuid().toString())
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run()).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void addAction(List<String> labels, List<Runnable> actions, int label, Runnable action) { labels.add(getString(label)); actions.add(action); }
    private void updateWriteMode() {
        shownWriteTarget = client.writeTarget;
        if (client.writeTarget != null) binding.bleNoResponse.setChecked((client.writeTarget.getProperties() & 8) == 0);
    }

    private void send(boolean repeat) {
        try {
            BluetoothTextEncoding[] encodings = {BluetoothTextEncoding.HEX, BluetoothTextEncoding.UTF_8, BluetoothTextEncoding.ASCII, BluetoothTextEncoding.GBK};
            String[] suffixes = {"", "\r\n", "\n", "\r"};
            byte[] payload = BlePayload.encode(binding.blePayload.getText().toString(), encodings[binding.bleEncoding.getSelectedItemPosition()], suffixes[binding.bleSuffix.getSelectedItemPosition()]);
            int interval = 0;
            if (repeat) {
                interval = Integer.parseInt(binding.bleInterval.getText().toString());
                if (interval < 100 || interval > 60000) throw new IllegalArgumentException("interval");
            }
            if (!client.send(payload, binding.bleNoResponse.isChecked(), binding.bleSplit.isChecked(), interval)) message(R.string.ble_send_rejected);
        } catch (IllegalArgumentException failure) { message(R.string.ble_invalid_payload); }
    }

    private void renderLogs() {
        if (binding == null) return;
        List<BleLogStore.Entry> snapshot = client.logs.snapshot();
        List<Row> rows = new ArrayList<>();
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
        long dropped = client.logs.dropped() + client.droppedRx.get();
        if (dropped > 0) rows.add(new Row(getString(R.string.ble_log_dropped, dropped), null, null));
        int start = Math.max(0, snapshot.size() - 100);
        for (int i = start; i < snapshot.size(); i++) {
            BleLogStore.Entry entry = snapshot.get(i);
            rows.add(new Row(time.format(new Date(entry.time)) + " " + entry.preview(binding.bleHexLog.isChecked()), null, null));
        }
        boolean atEnd = !binding.bleLogs.canScrollVertically(1);
        traffic.set(rows);
        if (atEnd && !rows.isEmpty()) binding.bleLogs.scrollToPosition(rows.size() - 1);
    }

    private void pair() {
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        try {
            if (manager == null || manager.getAdapter() == null || client.address().isEmpty()) return;
            BluetoothDevice device = manager.getAdapter().getRemoteDevice(client.address());
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) message(R.string.ble_already_paired);
            else if (device.getBondState() == BluetoothDevice.BOND_BONDING) message(R.string.ble_pairing);
            else if (!device.createBond()) message(R.string.ble_pair_failed);
            else message(R.string.ble_pairing);
        } catch (RuntimeException failure) { message(R.string.ble_pair_failed); }
    }

    private interface NumberAction { void accept(int value); }
    private void numberDialog(int title, String initial, int min, int max, NumberAction action) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(initial);
        dialog = new AlertDialog.Builder(this).setTitle(title).setView(input)
                .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int value = Integer.parseInt(input.getText().toString());
                if (value < min || value > max) throw new IllegalArgumentException();
                action.accept(value); dialog.dismiss();
            } catch (IllegalArgumentException invalid) { input.setError(min + "–" + max); }
        }));
        dialog.show();
    }

    private void exportChanged() {
        if (binding == null) return;
        binding.bleExport.setEnabled(!export.busy());
        int result = export.takeResult();
        if (result != 0) message(result > 0 ? R.string.ble_export_ok : R.string.ble_export_failed);
        if (export.ready() && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            export.claim();
            try { document.launch("BLE-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".tsv"); }
            catch (RuntimeException failure) { export.write(null); message(R.string.ble_export_failed); }
        }
    }
    private void message(int resource) { Toast.makeText(this, resource, Toast.LENGTH_LONG).show(); }

    private static final class Row {
        final String text;
        final Runnable click, longClick;
        Row(String text, Runnable click, Runnable longClick) { this.text = text; this.click = click; this.longClick = longClick; }
    }
    private static final class RowAdapter extends RecyclerView.Adapter<RowHolder> {
        private List<Row> rows = Collections.emptyList();
        @SuppressLint("NotifyDataSetChanged") void set(List<Row> value) { rows = value; notifyDataSetChanged(); }
        @NonNull @Override public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new RowHolder((TextView) LayoutInflater.from(parent.getContext()).inflate(R.layout.ble_row, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            Row row = rows.get(position);
            holder.text.setText(row.text);
            holder.text.setOnClickListener(row.click == null ? null : view -> row.click.run());
            holder.text.setOnLongClickListener(row.longClick == null ? null : view -> { row.longClick.run(); return true; });
        }
        @Override public int getItemCount() { return rows.size(); }
    }
    private static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView text;
        RowHolder(TextView view) { super(view); text = view; }
    }
}
