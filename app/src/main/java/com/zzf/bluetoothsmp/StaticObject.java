package com.zzf.bluetoothsmp;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.util.Log;

import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothEvent;
import com.zzf.bluetoothsmp.utils.MonitorMessage;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;

public class StaticObject {
    // 当前连接的蓝牙信息
    public static final ConcurrentMap<String, BluetoothServiceConnect> bluetoothSocketMap = new ConcurrentHashMap<>();
    public static final BluetoothConnectionAttemptRegistry connectionAttemptRegistry =
            new BluetoothConnectionAttemptRegistry();
    public static final BluetoothConnectionRegistry connectionRegistry = new BluetoothConnectionRegistry();
    public static final BluetoothReconnectManager reconnectManager = new BluetoothReconnectManager();
    private static BluetoothService bluetoothService;
    private static Thread messageDispatcher;
    private static String monitorListenerUuid;
    //队列信息
    public static final BlockingQueue<Msg> mTaskQueue = new LinkedBlockingQueue<>();
    //全局事件
    public static BluetoothEvent bluetoothEvent = new BluetoothEvent();
    //本机蓝牙名字
    public static String myBluetoothName ;
    //本机地址
    public static String myBluetoothAdd;

    public static synchronized BluetoothService ensureBluetoothService(
            Context context, BluetoothAdapter adapter) throws IOException {
        if (bluetoothService == null) {
            bluetoothService = new BluetoothService();
        }
        bluetoothService.createService(context, adapter);
        return bluetoothService;
    }

    public static synchronized void stopBluetoothService() {
        if (bluetoothService != null) {
            bluetoothService.stop();
            bluetoothService = null;
        }
    }

    public static synchronized void ensureMessageDispatcher() {
        if (messageDispatcher != null && messageDispatcher.isAlive()) {
            return;
        }
        messageDispatcher = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Msg message = mTaskQueue.take();
                    dispatchMessage(message);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (StaticObject.class) {
                    if (messageDispatcher == Thread.currentThread()) {
                        messageDispatcher = null;
                    }
                }
            }
        }, "bluetooth-message-dispatcher");
        messageDispatcher.setDaemon(true);
        messageDispatcher.start();
    }

    public static synchronized void stopMessageDispatcher() {
        if (messageDispatcher != null) {
            messageDispatcher.interrupt();
            messageDispatcher = null;
        }
    }

    public static synchronized void ensureMessageMonitor() {
        if (monitorListenerUuid == null) {
            monitorListenerUuid = new MonitorMessage().MonitorAndSaveMse();
        }
    }

    public static synchronized void stopMessageMonitor() {
        if (monitorListenerUuid != null) {
            bluetoothEvent.deleteAllEventByUuid(monitorListenerUuid);
            monitorListenerUuid = null;
        }
    }

    private static void dispatchMessage(Msg message) {
        if (message == null) {
            return;
        }
        try {
            switch (message.getStateType()) {
                case 0:
                    if (message.getType() == 0) {
                        bluetoothEvent.receiveMsg(message);
                    } else {
                        bluetoothEvent.senMsg(message);
                    }
                    bluetoothEvent.AllMsg(message);
                    break;
                case 1:
                    bluetoothEvent.notConnect(message);
                    break;
                default:
                    break;
            }
        } catch (RuntimeException error) {
            Log.e("BluetoothDispatcher", "Unable to dispatch Bluetooth message", error);
        }
    }

    public static void closeAllConnections() {
        closeAllConnections(false);
    }

    /**
     * Closes every active transport and optionally tells foreground screens that
     * their current session ended. Lifecycle teardown keeps the notification
     * disabled because those listeners are being destroyed at the same time.
     */
    public static void closeAllConnections(boolean notifyDisconnect) {
        reconnectManager.cancelAll();
        for (BluetoothObject attempt : connectionAttemptRegistry.snapshot()) {
            attempt.cancelConnectForLifecycle();
        }
        connectionAttemptRegistry.clear();
        for (java.util.Map.Entry<String, BluetoothServiceConnect> entry
                : bluetoothSocketMap.entrySet()) {
            String address = entry.getKey();
            BluetoothServiceConnect connection = entry.getValue();
            connection.closeForLifecycle();
            if (notifyDisconnect && address != null) {
                Msg msg = new Msg(address);
                msg.setStateType(1);
                mTaskQueue.offer(msg);
            }
        }
        bluetoothSocketMap.clear();
        connectionRegistry.clear();
    }


}
