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
import com.zzf.bluetoothsmp.MainActivity;
import com.zzf.bluetoothsmp.entity.SystemInfoMapper;
import com.zzf.bluetoothsmp.utils.StringUtils;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import org.litepal.LitePal;

import java.io.IOException;
import java.util.UUID;

public class ServiceFragment extends BaseFragment {

    private static final int REQUEST_DISCOVERABLE = 0x31;
    private static final int DISCOVERABLE_DURATION_SECONDS = 300;

    private MainActivity mainActivity;
    private TextView discoverableStatus;
    private Button openDiscoverable;
    private boolean scanModeReceiverRegistered;

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
        // 适配 Android 15 边缘到边模式
        setupEdgeToEdge(inflate);

        mainActivity = (MainActivity) getActivity();
        BluetoothService bluetoothService = mainActivity.bluetoothService;

        Toolbar toolbar = inflate.findViewById(R.id.lao_tian_toolbar);
        toolbar.setTitle(R.string.service_name);



        init(inflate);
        initClient(inflate);
        initReconnect(inflate);
        initDiscoverable(inflate);
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

        TextView viewById = inflate.findViewById(R.id.currentIdentifierTextViewUUid);
        SystemInfoMapper first = LitePal.findFirst(SystemInfoMapper.class);
        if(first!=null && StringUtils.isNotEmpty(first.getServiceSpp())){
            viewById.setText(first.getServiceSpp());
        }


        SwitchMaterial enable_iconnect = inflate.findViewById(R.id.enable_iconnect);
        enable_iconnect.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // 切换事件发生时执行的操作
                if (isChecked) {
                    // SwitchMaterial被切换到选中状态
                    if (mainActivity.bluetoothService == null) {
                        enable_iconnect.setChecked(false);
                        senHandlerMessage(0, getString(R.string.bluetooth_port_error));
                        return;
                    }
                    try {
                        mainActivity.bluetoothService.createService();
                    } catch (IOException e) {
                        enable_iconnect.setChecked(false);
                        senHandlerMessage(0, getString(R.string.bluetooth_port_error));
                    }
                } else {
                    // SwitchMaterial被切换到非选中状态
                    if (mainActivity.bluetoothService != null) {
                        mainActivity.bluetoothService.stop();
                    }
                }
            }
        });



        Button editUUid = inflate.findViewById(R.id.editUUid);
        editUUid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SystemInfoMapper first = LitePal.findFirst(SystemInfoMapper.class);
                String serviceSpp = new String(BluetoothObject.SPP_UUID);
                if(first!=null && StringUtils.isNotEmpty(first.getServiceSpp())){
                    serviceSpp=first.getServiceSpp();
                }
                viewById.setText(serviceSpp);
                LayoutInflater layoutInflater = LayoutInflater.from(getContext());
                View dialogView = layoutInflater.inflate(R.layout.edit_uuid_layout, null);
                //恢复默认serviceSPP
                View resetButton = dialogView.findViewById(R.id.resetButton);
                EditText uuidEditText = dialogView.findViewById(R.id.uuidEditText);
                uuidEditText.setText(serviceSpp);
                resetButton.setOnClickListener((View var1)->{
                    uuidEditText.setText(BluetoothObject.SPP_UUID);
                });



                AlertDialog.Builder builder = new AlertDialog.Builder(getContext()).setView(dialogView) .setCancelable(false).setPositiveButton(getString(R.string.confirm), (dialog, which) -> {
                    Editable text = uuidEditText.getText();
                    try {
                        UUID uuid = UUID.fromString(text.toString());
                    }catch (Exception e){
                        senHandlerMessage(0, mainActivity.getString(R.string.Wrong_format));
                        return;
                    }
                    SystemInfoMapper first1 = LitePal.findFirst(SystemInfoMapper.class);
                    if(first1==null){
                        first1=new SystemInfoMapper();
                    }
                    if(text.toString().equals(first1.getServiceSpp())){
                        senHandlerMessage(0, mainActivity.getString(R.string.Not_modify));
                        return;
                    }
                    senHandlerMessage(0, mainActivity.getString(R.string.Service_restart));
                    first1.setServiceSpp(text.toString());
                    first1.save();
                    viewById.setText(text.toString());
                    enable_iconnect.setChecked(false);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (isAdded() && mainActivity != null && mainActivity.bluetoothService != null) {
                            enable_iconnect.setChecked(true);
                        }
                    }, 1000L);
                    senHandlerMessage(0, mainActivity.getString(R.string.success));


                }).setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    dialog.dismiss();
                });

                builder.show();
            }
        });

    }


    public void initClient(View inflate){
        TextView clientPort = inflate.findViewById(R.id.currentclientIdentifierTextViewUUid);
        Button currentclientIdentifierTextViewUUid = inflate.findViewById(R.id.editClietnUUid);
        SystemInfoMapper first = LitePal.findFirst(SystemInfoMapper.class);
        if(first!=null &&StringUtils.isNotEmpty(first.getClientSpp())){
            clientPort.setText(first.getClientSpp());
        }else {
            clientPort.setText(BluetoothObject.SPP_UUID);
        }
        currentclientIdentifierTextViewUUid.setOnClickListener(view -> {
            LayoutInflater layoutInflater = LayoutInflater.from(getContext());
            View dialogView = layoutInflater.inflate(R.layout.edit_uuid_layout, null);
            //恢复默认serviceSPP
            View resetButton = dialogView.findViewById(R.id.resetButton);
            SystemInfoMapper first1 = LitePal.findFirst(SystemInfoMapper.class);

            EditText uuidEditText = dialogView.findViewById(R.id.uuidEditText);
            if(first1!=null &&StringUtils.isNotEmpty(first1.getClientSpp())){
                uuidEditText.setText(first1.getClientSpp());
            }else {
                uuidEditText.setText(BluetoothObject.SPP_UUID);
            }
            resetButton.setOnClickListener((View var1)->{
                uuidEditText.setText(BluetoothObject.SPP_UUID);
            });
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext()).setView(dialogView).setCancelable(false).setPositiveButton(getString(R.string.confirm), (dialog, which) -> {
                Editable text = uuidEditText.getText();
                try {
                    UUID uuid = UUID.fromString(text.toString());
                }catch (Exception e){
                    senHandlerMessage(0, mainActivity.getString(R.string.Wrong_format));
                    return;
                }
                SystemInfoMapper first2 = LitePal.findFirst(SystemInfoMapper.class);
                if(first2==null){
                    first2=new SystemInfoMapper();
                }
                if(text.toString().equals(first2.getClientSpp())){
                    senHandlerMessage(0, mainActivity.getString(R.string.Not_modify));
                    return;
                }
                first2.setClientSpp(text.toString());
                first2.save();
                clientPort.setText(first2.getClientSpp());
                senHandlerMessage(0, mainActivity.getString(R.string.success));

            }).setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                dialog.dismiss();
            });
            builder.show();

        });

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
        super.onDestroyView();
        discoverableStatus = null;
        openDiscoverable = null;
    }
}
