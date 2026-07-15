package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;

public class StaticObject {
    // 当前连接的蓝牙信息
    public static final ConcurrentMap<String, BluetoothServiceConnect> bluetoothSocketMap = new ConcurrentHashMap<>();
    public static final BluetoothConnectionRegistry connectionRegistry = new BluetoothConnectionRegistry();
    //队列信息
    public static final BlockingQueue<Msg> mTaskQueue = new LinkedBlockingQueue<>();
    //全局事件
    public static BluetoothEvent bluetoothEvent = new BluetoothEvent();
    //本机蓝牙名字
    public static String myBluetoothName ;
    //本机地址
    public static String myBluetoothAdd;

    public static void closeAllConnections() {
        for (BluetoothServiceConnect connection : bluetoothSocketMap.values()) {
            connection.close();
        }
        bluetoothSocketMap.clear();
        connectionRegistry.clear();
    }


}
