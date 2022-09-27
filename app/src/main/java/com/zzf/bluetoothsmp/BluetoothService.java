package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;

import java.io.IOException;
import java.util.UUID;

public class BluetoothService implements BluetoothBase {
    private BluetoothAdapter mBluetooth;
    private Context mcontex;
    @SuppressLint("MissingPermission")
    public void createService(Context mcontex, BluetoothAdapter mBluetooth) throws IOException {
        this.mcontex = mcontex;
        this.mBluetooth = mBluetooth;
        BluetoothServerSocket bluetoothService = mBluetooth.listenUsingInsecureRfcommWithServiceRecord("bluetoothSPP", UUID.fromString(BluetoothObject.SPP_UUID));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true){
                        BluetoothSocket accept = bluetoothService.accept();
                        BluetoothServiceConnect bluetoothServiceConnect = new BluetoothServiceConnect();
                        bluetoothServiceConnect.start(mcontex, accept,BluetoothObject.SPP_UUID);
                        Intent liaoTian = new Intent(mcontex, Liao_tian.class);
                        BluetoothDevice bluetoothDevice = accept.getRemoteDevice();
                        String name = bluetoothDevice.getName();
                        if(name == null || name.length()==0){
                            name=  bluetoothDevice.getAddress();
                        }
                        liaoTian.putExtra("bluetoothName",name);
                        liaoTian.putExtra("bluetoothAdd",bluetoothDevice.getAddress());
                        liaoTian.putExtra("bluetoothUUid",BluetoothObject.SPP_UUID);
                        mcontex.startActivity(liaoTian);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}
