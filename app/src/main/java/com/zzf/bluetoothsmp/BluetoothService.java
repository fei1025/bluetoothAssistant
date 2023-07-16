package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Message;

import com.example.bluetoothsmp.R;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.SystemInfoMapper;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.utils.StringUtils;

import org.litepal.LitePal;

import java.io.IOException;
import java.util.UUID;

public class BluetoothService implements BluetoothBase {
    private BluetoothAdapter mBluetooth;
    private Context mcontex;
    private BluetoothSocket accept;
    private Thread thread;
    BluetoothServerSocket bluetoothService;

    public void createService(Context mcontex, BluetoothAdapter mBluetooth) throws IOException {
        this.mcontex = mcontex;
        this.mBluetooth = mBluetooth;
        this.createService();
        //中端线程
        //thread.interrupt();
    }

    @SuppressLint("MissingPermission")
    public void createService() throws IOException {
        SystemInfoMapper first = LitePal.findFirst(SystemInfoMapper.class);

        if (first!=null &&StringUtils.isNotEmpty(first.getServiceSpp())) {
            String serviceSpp = first.getServiceSpp();
            try {
                UUID uuid = UUID.fromString(serviceSpp);
                bluetoothService = mBluetooth.listenUsingInsecureRfcommWithServiceRecord("bluetoothSPP", uuid);
            } catch (Exception e) {
                senHandlerMessage(0, mcontex.getString(R.string.bluetooth_port_error));
                e.printStackTrace();
                return;
            }
        } else {
            bluetoothService = mBluetooth.listenUsingInsecureRfcommWithServiceRecord("bluetoothSPP", UUID.fromString(BluetoothObject.SPP_UUID));

        }
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        accept = bluetoothService.accept();
                        BluetoothServiceConnect bluetoothServiceConnect = new BluetoothServiceConnect();
                        bluetoothServiceConnect.start(mcontex, accept, BluetoothObject.SPP_UUID);
                        Intent liaoTian = new Intent(mcontex, Liantian_new.class);
                        BluetoothDevice bluetoothDevice = accept.getRemoteDevice();
                        BluetoothDrive drive = new BluetoothDrive();
                        String name = bluetoothDevice.getName();
                        if (name == null || name.length() == 0) {
                            name = bluetoothDevice.getAddress();
                        }
                        drive.setDriveName(name);
                        drive.setDriveAdd(bluetoothDevice.getAddress());
                        drive.setUuid(BluetoothObject.SPP_UUID);
                        liaoTian.putExtra("bluetoothName", name);
                        liaoTian.putExtra("bluetoothAdd", bluetoothDevice.getAddress());
                        liaoTian.putExtra("bluetoothUUid", BluetoothObject.SPP_UUID);
                        liaoTian.putExtra("BluetoothDrive", drive);
                        mcontex.startActivity(liaoTian);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        thread.start();
    }

    public void stop() {
        thread.interrupt();
        try {
            bluetoothService.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public void senHandlerMessage(Integer what, Object obj) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = obj;
        ((MainActivity) mcontex).mHandler.sendMessage(msg);
    }
}


