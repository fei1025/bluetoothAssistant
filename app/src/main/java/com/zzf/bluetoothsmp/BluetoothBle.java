package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.Build;
import android.util.Log;

import java.util.List;
import java.util.UUID;

import androidx.annotation.RequiresApi;

public class BluetoothBle {
    private final String TAG = "BluetoothBle";
    // private BluetoothDevice bluetoothDevice;
    private BluetoothGatt bluetoothGatt;


    @SuppressLint("MissingPermission")
    public BluetoothBle(BluetoothDevice bluetoothDevice) {
        bluetoothGatt = bluetoothDevice.connectGatt(MyApplication.getContext(), true, mGattCallback);
        // bluetoothGatt.connect();
    }


    /**
     * gatt连接结果的返回
     */
    private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        /**
         * Callback indicating when GATT client has connected/disconnected to/from a remote GATT server
         *
         * @param gatt     返回连接建立的gatt对象
         * @param status   返回的是此次gatt操作的结果，成功了返回0
         * @param newState 每次client连接或断开连接状态变化，STATE_CONNECTED 0，STATE_CONNECTING 1,STATE_DISCONNECTED 2,STATE_DISCONNECTING 3
         */
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange status:" + status + "  newState:" + newState);
            //progressDialog.dismiss();
            // Message msg = new Message();

            Log.d(TAG, "onConnectionStateChange:" + bluetoothGatt.equals(gatt));

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onConnectionStateChange: ========》》连接成功");



                 gatt.discoverServices();
                //bluetoothGatt.close();
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (bluetoothGatt == null) {
                    return;
                }
 /*               //设置接收数据长度，默认20
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    //LogHelper.log(bluetoothGatt.requestMtu(32)?"修改长度成功":"修改长度失败");
                }

                Log.e("state", "连接成功");
                msg.what = TAG_CONNECTED;
                handler.sendMessage(msg);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                // 断开成功
                Log.e("state", "连接断开");
                release(); // 断开必须释放，否则会导致无法再次连接
                msg.what = TAG_DISCONNECTED;
                handler.sendMessage(msg);
            */
            }
        }

        /**
         * Callback invoked when the list of remote services, characteristics and descriptors for the remote device have been updated, ie new services have been discovered.
         *
         * @param gatt   返回的是本次连接的gatt对象
         * @param status
         */
        @SuppressLint("MissingPermission")
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered status" + status);
            List<BluetoothGattService> services = gatt.getServices();
            Log.d(TAG, "services:" + services.size());
            for (int i = 0; i < services.size(); i++) {
                BluetoothGattService bluetoothGattService = services.get(i);
                UUID uuid = bluetoothGattService.getUuid();
                Log.d(TAG, "BluetoothGattServiceUUid: " + uuid.toString());
                int type = bluetoothGattService.getType();
                Log.d(TAG, "BluetoothType: " + type);


                List<BluetoothGattService> includedServices = bluetoothGattService.getIncludedServices();
                Log.d(TAG, "includedServices: " + includedServices.size());

                for (int j = 0; j < includedServices.size(); j++) {
                    BluetoothGattService bluetoothGattService1 = includedServices.get(i);
                    UUID uuid1 = bluetoothGattService1.getUuid();
                    Log.d(TAG, "includedServices: " + uuid1.toString());

                }

                List<BluetoothGattCharacteristic> characteristics = bluetoothGattService.getCharacteristics();
                Log.d(TAG, "characteristics: " + characteristics.size());

                for (int j = 0; j < characteristics.size(); j++) {
                    BluetoothGattCharacteristic bluetoothGattCharacteristic = characteristics.get(i);
                    UUID uuid1 = bluetoothGattCharacteristic.getUuid();
                    Log.d(TAG, "characteristics: " + uuid1.toString());
                    int properties = bluetoothGattCharacteristic.getProperties();
                    Log.d(TAG, "characteristics  :properties :" + properties);
                    Log.d(TAG, "characteristics  :permissions: " + bluetoothGattCharacteristic.getPermissions());

                    List<BluetoothGattDescriptor> descriptors = bluetoothGattCharacteristic.getDescriptors();
                    Log.d(TAG, "descriptors: " + descriptors.size());
                    for (int l = 0; l < descriptors.size(); l++) {
                        BluetoothGattDescriptor bluetoothGattDescriptor = descriptors.get(l);
                        int permissions = bluetoothGattDescriptor.getPermissions();

                        Log.d(TAG, "descriptors: permissions:" + permissions);

                    }

                }

                //bluetoothGattService.getCharacteristic()
            }

      /*      mServiceList = gatt.getServices();
            if (mServiceList == null) {
                LogHelper.log("无services" + "\n");
                return;
            }*/
            //遍历获取uuid
//            for (BluetoothGattService service : mServiceList) {
//                List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
//                System.out.println();
//                LogHelper.log("扫描到Service：" + service.getUuid() + "\n");
//                    for (BluetoothGattCharacteristic characteristic : characteristics) {
//                        LogHelper.log("characteristic: " + characteristic.getUuid() + "\n");
//                    }
//                    break;
//            }

            //协议uuid验证，验证完之后进行绑定，如果状态发生改变则回调onCharacteristicChanged
         /*   if (gatt != null) {
                // Service
                BluetoothGattService dataService = gatt.getService(
                        UUID.fromString(UUID_SERVICE_DATA_TRANSFER));//UUID_SERVICE_DATA_TRANSFER
                if (dataService == null) {
                    LogHelper.log("dataService为空,Service uuid错误:" + "\n");
                    return;
                }
                // characteristic 接收ble蓝牙数据
                BluetoothGattCharacteristic receiveCharacteristic = dataService.getCharacteristic(
                        UUID.fromString(UUID_CHARACTERISTIC_DATA_RECEIVE));//UUID_CHARACTERISTIC_DATA_RECEIVE
                if (receiveCharacteristic == null) {
                    LogHelper.log("receiveCharacteristic为空,receiveCharacteristic uuid错误:" + "\n");
                    return;
                }
                //绑定
                bluetoothGatt.setCharacteristicNotification(receiveCharacteristic, true);
                // descriptor
                BluetoothGattDescriptor descriptor = receiveCharacteristic.getDescriptor(
                        UUID.fromString(UUID_DESCRIPTOR_CONFIG));//UUID_DESCRIPTOR_CONFIG
                if (descriptor == null) {
                    LogHelper.log("descriptor null\n");
                    return;
                }
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);

                LogHelper.log("uuid验证完成" + "\n");
            }*/
        }


        /**
         * 指示描述符写入操作结果的回调
         *
         * @param gatt
         * @param descriptor
         * @param status
         */
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            //LogHelper.log("onDescriptorWrite\n");
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
            // LogHelper.log("onDescriptorRead\n");
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            if (BluetoothGatt.GATT_SUCCESS == status) {
                //LogHelper.log("onMtuChanged success MTU = " + mtu);
                // bluetoothGatt.discoverServices();
            } else {
                // LogHelper.log("onMtuChanged fail ");
            }
        }

        @Override
        public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            super.onPhyRead(gatt, txPhy, rxPhy, status);
            // LogHelper.log("onPhyRead\n");
        }

        @Override
        public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status);
            //  LogHelper.log("onPhyUpdate\n");
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            super.onReadRemoteRssi(gatt, rssi, status);
            // LogHelper.log("onReadRemoteRssi\n");
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            super.onReliableWriteCompleted(gatt, status);
            // LogHelper.log("onReliableWriteCompleted\n");
        }
    };

    @SuppressLint("MissingPermission")
    private void release() {
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

}
