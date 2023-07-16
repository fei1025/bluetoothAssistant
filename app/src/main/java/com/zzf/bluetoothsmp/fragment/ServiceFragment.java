package com.zzf.bluetoothsmp.fragment;

import androidx.appcompat.widget.Toolbar;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.os.Message;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import com.example.bluetoothsmp.R;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.zzf.bluetoothsmp.BluetoothObject;
import com.zzf.bluetoothsmp.BluetoothService;
import com.zzf.bluetoothsmp.MainActivity;
import com.zzf.bluetoothsmp.entity.SystemInfoMapper;
import com.zzf.bluetoothsmp.loading.WeiboDialogUtils;
import com.zzf.bluetoothsmp.utils.StringUtils;

import org.litepal.LitePal;
import org.w3c.dom.Text;

import java.io.IOException;
import java.util.UUID;

public class ServiceFragment extends Fragment {

    private MainActivity mainActivity;


    public static ServiceFragment newInstance() {
        return new ServiceFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View inflate = inflater.inflate(R.layout.fragment_service, container, false);
        mainActivity = (MainActivity) getActivity();
        BluetoothService bluetoothService = mainActivity.bluetoothService;

        Toolbar toolbar = inflate.findViewById(R.id.lao_tian_toolbar);
        toolbar.setTitle(R.string.service_name);


        init(inflate);
        initClient(inflate);
        return inflate;
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
                    try {
                        mainActivity.bluetoothService.createService();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    // SwitchMaterial被切换到非选中状态
                    mainActivity.bluetoothService.stop();
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
                    try {
                        Thread.sleep(1000L);
                        enable_iconnect.setChecked(true);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
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

    public void senHandlerMessage(Integer what, Object obj) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = obj;
       mainActivity.mHandler.sendMessage(msg);
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();

    }
}