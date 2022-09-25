package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;

public class StaticObject {
    // 当前连接的蓝牙信息
    public static Map<String, BluetoothServiceConnect> bluetoothSocketMap = new HashMap<>();
    //队列信息
    public static final BlockingQueue<Msg> mTaskQueue = new PriorityBlockingQueue<Msg>();
    //全局事件
    public static BluetoothEvent bluetoothEvent = new BluetoothEvent();
    //本机蓝牙名字
    public static String myBluetoothName ;


}
