package com.zzf.bluetoothsmp.fragment;

import android.Manifest;
import androidx.appcompat.widget.Toolbar;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;

import android.os.Message;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import com.zzf.bluetoothsmp.R;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.zzf.bluetoothsmp.BluetoothObject;
import com.zzf.bluetoothsmp.BluetoothService;
import com.zzf.bluetoothsmp.BluetoothServerSettings;
import com.zzf.bluetoothsmp.MainActivity;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.utils.BluetoothUuidRepository;
import com.zzf.bluetoothsmp.utils.ToastUtil;


import java.io.IOException;
import java.util.UUID;

public class ServiceFragment extends BaseFragment {

    @Override
    protected String getAnalyticsScreenName() {
        return "service";
    }

    private static final int REQUEST_DISCOVERABLE = 0x31;
    private static final int DISCOVERABLE_DURATION_SECONDS = 300;

    private MainActivity mainActivity;
    private TextView discoverableStatus;
    private Button openDiscoverable;
    private boolean scanModeReceiverRegistered;
    private SwitchMaterial serverSwitch;
    private boolean serverSwitchReady;
    private View currentRoot;
    private int viewGeneration;
    private int uuidRequest;
    private boolean uuidLoaded;
    private String serverUuid = BluetoothObject.SPP_UUID;
    private String clientUuid = BluetoothObject.SPP_UUID;
    private Button editServerUuid;
    private Button editClientUuid;
    private TextView serverUuidText;
    private TextView clientUuidText;
    private AlertDialog uuidDialog;

    private final BroadcastReceiver scanModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(intent.getAction())) {
                updateDiscoverableStatus();
            }
        }
    };


    public static ServiceFragment newInstance() {
        return new ServiceFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View inflate = inflater.inflate(R.layout.fragment_service, container, false);
        currentRoot = inflate;
        viewGeneration++;
        // 适配 Android 15 边缘到边模式
        setupEdgeToEdge(inflate);

        mainActivity = (MainActivity) getActivity();

        Toolbar toolbar = inflate.findViewById(R.id.lao_tian_toolbar);
        toolbar.setTitle(R.string.service_name);



        init(inflate);
        initClient(inflate);
        initReconnect(inflate);
        initDiscoverable(inflate);
        loadUuidConfig(null);
        return inflate;
    }

    private void initReconnect(View inflate) {
        SwitchMaterial autoReconnectSwitch = inflate.findViewById(R.id.autoReconnectSwitch);
        if (autoReconnectSwitch == null) {
            return;
        }
        autoReconnectSwitch.setChecked(com.zzf.bluetoothsmp.StaticObject.reconnectManager.isGlobalEnabled());
        autoReconnectSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                com.zzf.bluetoothsmp.StaticObject.reconnectManager.setGlobalEnabled(isChecked));
    }

    public void init(View inflate) {
        serverUuidText = inflate.findViewById(R.id.currentIdentifierTextViewUUid);
        serverUuidText.setText(serverUuid);
        editServerUuid = inflate.findViewById(R.id.editUUid);
        editServerUuid.setOnClickListener(view -> requestUuidDialog(true));
        serverSwitchReady = false;
        serverSwitch = inflate.findViewById(R.id.enable_iconnect);
        serverSwitch.setSaveEnabled(false);
        serverSwitch.setChecked(BluetoothServerSettings.isEnabled(requireContext()));
        serverSwitch.setOnCheckedChangeListener((buttonView, enabled) -> {
            if (!serverSwitchReady || mainActivity == null) return;
            try { mainActivity.setBluetoothServerEnabled(enabled); }
            catch (IOException | RuntimeException error) {
                senHandlerMessage(0, getString(R.string.bluetooth_port_error));
            }
        });
    }

    public void initClient(View inflate) {
        clientUuidText = inflate.findViewById(R.id.currentclientIdentifierTextViewUUid);
        clientUuidText.setText(clientUuid);
        editClientUuid = inflate.findViewById(R.id.editClietnUUid);
        editClientUuid.setOnClickListener(view -> requestUuidDialog(false));
    }

    private void loadUuidConfig(Runnable afterLoad) {
        final int generation = viewGeneration;
        final int request = ++uuidRequest;
        uuidLoaded = false;
        setUuidEditorsEnabled(false);
        BluetoothUuidRepository.load(new BluetoothUuidRepository.Callback<BluetoothUuidRepository.Config>() {
            @Override public void onSuccess(BluetoothUuidRepository.Config config) {
                if (!currentUuidRequest(generation, request)) return;
                applyUuidConfig(config);
                setUuidEditorsEnabled(true);
                if (afterLoad != null) afterLoad.run();
            }
            @Override public void onError(Throwable error) {
                if (!currentUuidRequest(generation, request)) return;
                setUuidEditorsEnabled(true);
                ToastUtil.toastWord(requireContext(), getString(R.string.uuid_load_failed));
            }
        });
    }

    private boolean currentUuidRequest(int generation, int request) {
        return isAdded() && currentRoot != null && generation == viewGeneration && request == uuidRequest;
    }

    private void setUuidEditorsEnabled(boolean enabled) {
        if (editServerUuid != null) editServerUuid.setEnabled(enabled);
        if (editClientUuid != null) editClientUuid.setEnabled(enabled);
    }

    private void applyUuidConfig(BluetoothUuidRepository.Config config) {
        serverUuid = config.getServerUuid();
        clientUuid = config.getClientUuid();
        uuidLoaded = true;
        if (serverUuidText != null) serverUuidText.setText(serverUuid);
        if (clientUuidText != null) clientUuidText.setText(clientUuid);
    }

    private void requestUuidDialog(boolean server) {
        if (currentRoot == null || !isAdded() || (uuidDialog != null && uuidDialog.isShowing())) return;
        if (!uuidLoaded) {
            loadUuidConfig(() -> requestUuidDialog(server));
            return;
        }
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.edit_uuid_layout, null);
        EditText input = content.findViewById(R.id.uuidEditText);
        View reset = content.findViewById(R.id.resetButton);
        input.setText(server ? serverUuid : clientUuid);
        reset.setOnClickListener(view -> input.setText(BluetoothObject.SPP_UUID));
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setView(content)
                .setPositiveButton(R.string.confirm, null).setNegativeButton(R.string.cancel, null).create();
        uuidDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (uuidDialog == dialog) uuidDialog = null; });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            final String value;
            try { value = UUID.fromString(input.getText().toString().trim()).toString(); }
            catch (IllegalArgumentException error) {
                ToastUtil.toastWord(requireContext(), getString(R.string.Wrong_format));
                return;
            }
            if (value.equalsIgnoreCase(server ? serverUuid : clientUuid)) {
                ToastUtil.toastWord(requireContext(), getString(R.string.Not_modify));
                dialog.dismiss();
                return;
            }
            final int generation = viewGeneration;
            final int request = ++uuidRequest;
            final Context applicationContext = requireContext().getApplicationContext();
            final BluetoothAdapter adapter = mainActivity == null
                    ? BluetoothAdapter.getDefaultAdapter() : mainActivity.getmBluetooth();
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            input.setEnabled(false);
            reset.setEnabled(false);
            setUuidEditorsEnabled(false);
            BluetoothUuidRepository.Callback<BluetoothUuidRepository.Config> callback =
                    new BluetoothUuidRepository.Callback<BluetoothUuidRepository.Config>() {
                @Override public void onSuccess(BluetoothUuidRepository.Config config) {
                    Throwable restartError = null;
                    if (server) {
                        try { StaticObject.restartBluetoothService(applicationContext, adapter); }
                        catch (IOException | RuntimeException error) { restartError = error; }
                    }
                    if (!currentUuidRequest(generation, request)) return;
                    applyUuidConfig(config);
                    setUuidEditorsEnabled(true);
                    dialog.dismiss();
                    ToastUtil.toastWord(requireContext(), getString(restartError == null
                            ? R.string.success : R.string.bluetooth_port_error));
                }
                @Override public void onError(Throwable error) {
                    if (!currentUuidRequest(generation, request)) return;
                    dialog.setCancelable(true);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    input.setEnabled(true);
                    reset.setEnabled(true);
                    setUuidEditorsEnabled(true);
                    ToastUtil.toastWord(requireContext(), getString(R.string.uuid_save_failed));
                }
            };
            if (server) BluetoothUuidRepository.updateServer(value, callback);
            else BluetoothUuidRepository.updateClient(value, callback);
        });
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        syncServerSwitch();
        serverSwitchReady = true;
    }

    @Override
    public void onResume() {
        super.onResume();
        syncServerSwitch();
    }

    private void syncServerSwitch() {
        if (serverSwitch == null) {
            return;
        }
        boolean wasReady = serverSwitchReady;
        serverSwitchReady = false;
        serverSwitch.setChecked(BluetoothServerSettings.isEnabled(requireContext()));
        serverSwitchReady = wasReady;
    }

    private void initDiscoverable(View inflate) {
        discoverableStatus = inflate.findViewById(R.id.discoverableStatus);
        openDiscoverable = inflate.findViewById(R.id.openDiscoverable);
        openDiscoverable.setOnClickListener(v -> {
            if (!hasBluetoothPermissions()) {
                requestBluetoothPermissions();
                return;
            }
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION_SECONDS);
            startActivityForResult(discoverableIntent, REQUEST_DISCOVERABLE);
        });
        updateDiscoverableStatus();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!scanModeReceiverRegistered) {
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
            ContextCompat.registerReceiver(requireContext(), scanModeReceiver, filter,
                    ContextCompat.RECEIVER_EXPORTED);
            scanModeReceiverRegistered = true;
        }
        updateDiscoverableStatus();
    }

    @Override
    public void onStop() {
        if (scanModeReceiverRegistered) {
            requireContext().unregisterReceiver(scanModeReceiver);
            scanModeReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DISCOVERABLE) {
            updateDiscoverableStatus();
            if (resultCode == Activity.RESULT_CANCELED) {
                ToastUtil.toastWord(requireContext(), getString(R.string.discoverable_request_denied));
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private void updateDiscoverableStatus() {
        if (discoverableStatus == null || openDiscoverable == null || mainActivity == null) {
            return;
        }
        BluetoothAdapter adapter = mainActivity.getmBluetooth();
        if (adapter == null || !adapter.isEnabled()) {
            discoverableStatus.setText(R.string.discoverable_status_unavailable);
            discoverableStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.red));
            openDiscoverable.setEnabled(false);
            return;
        }

        if (!hasBluetoothPermissions()) {
            discoverableStatus.setText(R.string.discoverable_status_unavailable);
            discoverableStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.red));
            openDiscoverable.setEnabled(false);
            return;
        }

        openDiscoverable.setEnabled(true);
        boolean discoverable = adapter.getScanMode()
                == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        if (discoverable) {
            discoverableStatus.setText(R.string.discoverable_status_visible);
            discoverableStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.teal_700));
            openDiscoverable.setText(R.string.renew_discoverable);
        } else {
            discoverableStatus.setText(R.string.discoverable_status_hidden);
            discoverableStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.red));
            openDiscoverable.setText(R.string.open_discoverable);
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(requireActivity(), new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, 0x31);
        }
    }

    public void senHandlerMessage(Integer what, Object obj) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = obj;
        mainActivity.mHandler.sendMessage(msg);
    }

    @Override
    public void onDestroyView() {
        viewGeneration++;
        uuidRequest++;
        if (uuidDialog != null) {
            uuidDialog.dismiss();
            uuidDialog = null;
        }
        currentRoot = null;
        serverUuidText = null;
        clientUuidText = null;
        editServerUuid = null;
        editClientUuid = null;
        mainActivity = null;
        super.onDestroyView();
        serverSwitchReady = false;
        serverSwitch = null;
        discoverableStatus = null;
        openDiscoverable = null;
    }
}
