package com.zzf.bluetoothsmp;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.util.Log;

import com.zzf.bluetoothsmp.entity.Msg;
import com.zzf.bluetoothsmp.event.BluetoothEvent;
import com.zzf.bluetoothsmp.utils.MonitorMessage;
import com.zzf.bluetoothsmp.utils.BluetoothAddressUtils;
import com.zzf.bluetoothsmp.utils.HistoryRepository;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class StaticObject {
    // 当前连接的蓝牙信息
    public static final ConcurrentMap<String, BluetoothServiceConnect> bluetoothSocketMap = new ConcurrentHashMap<>();
    public static final BluetoothConnectionAttemptRegistry connectionAttemptRegistry =
            new BluetoothConnectionAttemptRegistry();
    public static final BluetoothConnectionRegistry connectionRegistry = new BluetoothConnectionRegistry();
    public static final BluetoothReconnectManager reconnectManager = new BluetoothReconnectManager();
    public static final BluetoothForegroundGate foregroundGate = BluetoothForegroundGate.APP;
    private static BluetoothService bluetoothService;
    private static Thread messageDispatcher;
    private static Thread controlDispatcher;
    private static String monitorListenerUuid;
    //队列信息
    public static final BluetoothMessageQueue mTaskQueue = new BluetoothMessageQueue(
            512, 4L * 1024L * 1024L, 64, 256L * 1024L);
    private static final ConcurrentMap<String, Runnable> centralBatchCancellations = new ConcurrentHashMap<>();
    private static final AtomicLong rejectedMessageCount = new AtomicLong();
    //全局事件
    public static BluetoothEvent bluetoothEvent = new BluetoothEvent();
    //本机蓝牙名字
    public static String myBluetoothName ;
    //本机地址
    public static String myBluetoothAdd;

    /** Nonblocking admission for UI and async send producers. */
    public static boolean submitMessage(Msg message) {
        if (message == null) return false;
        boolean outbound = message.getStateType() == 0 && message.getType() == Msg.TYPE_SENT;
        if (outbound) {
            if (!message.claimSubmission()) return false;
            if (message.isSendCanceled() || !message.isForegroundGenerationCurrent()) {
                publishSendStatus(message, BluetoothSendStatus.CANCELED, null);
                return false;
            }
            BluetoothServiceConnect session = findSession(message.getBluetoothAdd());
            try {
                if (session == null || !session.bindOutboundMessage(message)) {
                    publishSendStatus(message, BluetoothSendStatus.FAILED,
                            new IllegalStateException("SESSION_CHANGED_OR_DISCONNECTED"));
                    return false;
                }
            } catch (IllegalArgumentException invalidPayload) {
                publishSendStatus(message, BluetoothSendStatus.FAILED, invalidPayload);
                return false;
            }
            publishSendStatus(message, BluetoothSendStatus.QUEUED, null);
            BluetoothSendBatch batch = message.getSendBatch();
            if (batch != null) {
                Runnable cancellation = () -> {
                    mTaskQueue.remove(message);
                    releaseCentralBatch(message);
                    publishSendStatus(message, BluetoothSendStatus.CANCELED, null);
                };
                centralBatchCancellations.put(message.getMessageId(), cancellation);
                if (!batch.register(cancellation)) {
                    cancellation.run();
                    return false;
                }
            }
        }
        if (!mTaskQueue.offer(message)) {
            rejectedMessageCount.incrementAndGet();
            releaseCentralBatch(message);
            if (outbound) publishSendStatus(message, BluetoothSendStatus.FAILED,
                    new BluetoothQueueFullException("central message request/byte budget exceeded"));
            else Log.w("BluetoothDispatcher", "Message rejected by queue request/byte budget");
            return false;
        }
        // Cancellation may have happened between registration and insertion.
        if (outbound && message.isSendCanceled()) {
            mTaskQueue.remove(message);
            releaseCentralBatch(message);
            publishSendStatus(message, BluetoothSendStatus.CANCELED, null);
            return false;
        }
        return true;
    }

    /** Receiver backpressure preserves frames instead of silently dropping a full queue. */
    public static void submitReceivedMessage(Msg message) throws InterruptedException {
        try {
            mTaskQueue.put(message);
        } catch (InterruptedException | IllegalArgumentException error) {
            rejectedMessageCount.incrementAndGet();
            Log.w("BluetoothDispatcher", "Received frame admission interrupted or exceeds byte budget", error);
            throw error;
        }
    }

    public static long getRejectedMessageCount() { return rejectedMessageCount.get(); }

    private static BluetoothServiceConnect findSession(String address) {
        String normalized = BluetoothAddressUtils.normalize(address);
        return normalized == null ? null : bluetoothSocketMap.get(normalized);
    }

    public static void publishSendStatus(Msg message, BluetoothSendStatus status, Throwable error) {
        if (message.updateSendStatus(status, error == null ? null : error.getMessage())) {
            // Observers may apply disk backpressure. Never hold the message lock
            // through observers: main-thread Stop must still update/cancel promptly.
            // Snapshot consumers use statusVersion; intermediate states may coalesce.
            bluetoothEvent.sendStatus(message);
        }
    }

    private static void releaseCentralBatch(Msg message) {
        Runnable callback = centralBatchCancellations.remove(message.getMessageId());
        if (callback != null && message.getSendBatch() != null) message.getSendBatch().unregister(callback);
    }

    public static synchronized BluetoothService ensureBluetoothService(
            Context context, BluetoothAdapter adapter) throws IOException {
        // User intent survives background suspension and is separate from runtime state.
        if (!BluetoothServerSettings.isEnabled(context) || !foregroundGate.isActive()) {
            stopBluetoothService();
            return null;
        }
        if (bluetoothService == null) {
            bluetoothService = new BluetoothService();
        }
        bluetoothService.createService(context, adapter);
        return bluetoothService;
    }

    public static synchronized void setBluetoothServerEnabled(Context context, boolean enabled) {
        BluetoothServerSettings.setEnabled(context, enabled);
        if (!enabled) {
            stopBluetoothService();
        }
    }

    public static synchronized BluetoothService restartBluetoothService(
            Context context, BluetoothAdapter adapter) throws IOException {
        // Runtime restarts (for example, changing UUID) must never change user intent.
        stopBluetoothService();
        return ensureBluetoothService(context, adapter);
    }

    public static synchronized void stopBluetoothService() {
        if (bluetoothService != null) {
            bluetoothService.stop();
            bluetoothService = null;
        }
    }

    @android.annotation.SuppressLint({"MissingPermission", "HardwareIds"})
    public static void resumeForegroundBluetooth(Context context) {
        if (!foregroundGate.isActive() || !BluetoothPermissionUtils.hasConnectPermission(context)) {
            return;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return;
            }
            myBluetoothName = adapter.getName();
            myBluetoothAdd = adapter.getAddress();
            ensureMessageMonitor();
            ensureMessageDispatcher();
            ensureBluetoothService(context, adapter);
            reconnectManager.restorePendingConnections();
        } catch (IOException | RuntimeException error) {
            Log.w("BluetoothRuntime", "Unable to restore foreground Bluetooth runtime", error);
        }
    }

    /** Call after invalidating foregroundGate so late workers cannot register a socket. */
    @android.annotation.SuppressLint("MissingPermission")
    public static void suspendForegroundBluetooth(Context context) {
        reconnectManager.cancelAll();
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && BluetoothPermissionUtils.hasScanPermission(context)) {
                adapter.cancelDiscovery();
            }
        } catch (RuntimeException error) {
            Log.w("BluetoothRuntime", "Unable to cancel discovery while suspending", error);
        }
        stopBluetoothService();
        closeAllConnections(true);
        // Do not replay outbound work from the old foreground interval on a
        // connection restored after the user returns.
        for (Iterator<Msg> messages = mTaskQueue.iterator(); messages.hasNext();) {
            Msg message = messages.next();
            if (message.getStateType() == 0 && message.getType() != 0) {
                messages.remove();
                releaseCentralBatch(message);
                publishSendStatus(message, BluetoothSendStatus.CANCELED, null);
            }
        }
        Log.i("BluetoothRuntime", "App background or screen locked: Bluetooth runtime suspended");
    }

    public static synchronized void ensureMessageDispatcher() {
        if (controlDispatcher == null || !controlDispatcher.isAlive()) {
            controlDispatcher = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) dispatchMessage(mTaskQueue.takeControl());
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally {
                    synchronized (StaticObject.class) {
                        if (controlDispatcher == Thread.currentThread()) controlDispatcher = null;
                    }
                }
            }, "bluetooth-control-dispatcher");
            controlDispatcher.setDaemon(true);
            controlDispatcher.start();
        }
        if (messageDispatcher != null && messageDispatcher.isAlive()) {
            return;
        }
        messageDispatcher = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Msg message = mTaskQueue.takeData();
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
        if (controlDispatcher != null) { controlDispatcher.interrupt(); controlDispatcher = null; }
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
        if (message != null) releaseCentralBatch(message);
        if (message == null || message.isSendCanceled()) {
            if (message != null && message.getType() == Msg.TYPE_SENT) publishSendStatus(message, BluetoothSendStatus.CANCELED, null);
            return;
        }
        if (message.getStateType() == 0 && message.getType() == Msg.TYPE_SENT
                && !message.isForegroundGenerationCurrent()) {
            publishSendStatus(message, BluetoothSendStatus.CANCELED, null);
            return;
        }
        if (message.getStateType() == 1
                && message.getForegroundGeneration() != foregroundGate.currentGeneration()) {
            return;
        }
        try {
            switch (message.getStateType()) {
                case 0:
                    if (message.getType() == 0) {
                        bluetoothEvent.receiveMsg(message);
                    } else {
                        BluetoothServiceConnect current = findSession(message.getBluetoothAdd());
                        if (current == null || !current.bindOutboundMessage(message)) {
                            publishSendStatus(message, BluetoothSendStatus.FAILED,
                                    new IllegalStateException("SESSION_CHANGED_OR_DISCONNECTED"));
                            return;
                        }
                        if (!HistoryRepository.reserveSend(message) || message.isSendCanceled()
                                || !message.isForegroundGenerationCurrent()
                                || findSession(message.getBluetoothAdd()) != current
                                || !current.bindOutboundMessage(message)) {
                            HistoryRepository.abandonSend(message);
                            publishSendStatus(message, BluetoothSendStatus.CANCELED, null);
                            return;
                        }
                        bluetoothEvent.senMsg(message);
                        if (!message.hasTransportAdmission()) {
                            publishSendStatus(message, message.isForegroundGenerationCurrent() ? BluetoothSendStatus.FAILED : BluetoothSendStatus.CANCELED,
                                    new IllegalStateException("SESSION_CHANGED_OR_DISCONNECTED"));
                            return;
                        }
                    }
                    // Stop can arrive while SEND observers are running. Do not
                    // subsequently update conversation history with that canceled send.
                    if (!message.isSendCanceled()
                            && (message.getType() == Msg.TYPE_RECEIVED || message.isForegroundGenerationCurrent())) {
                        bluetoothEvent.AllMsg(message);
                    }
                    break;
                case 1:
                    BluetoothServiceConnect current = findSession(message.getBluetoothAdd());
                    if (current != null && !current.getSessionId().equals(message.getSessionId())) return;
                    bluetoothEvent.notConnect(message);
                    break;
                default:
                    break;
            }
        } catch (RuntimeException error) {
            if (message.getStateType() == 0 && message.getType() == Msg.TYPE_SENT) {
                publishSendStatus(message, BluetoothSendStatus.FAILED, error);
            }
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
        com.zzf.bluetoothsmp.ble.BleRuntime.suspendAll();
          com.zzf.bluetoothsmp.plugin.PluginRuntime.suspendAll();
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
                msg.setSessionId(connection.getSessionId());
                msg.setSendUuid(connection.getSendUuid());
                msg.setBluetoothName(connection.getBluetoothName());
                msg.setStateType(1);
                submitMessage(msg);
            }
        }
        bluetoothSocketMap.clear();
        connectionRegistry.clear();
    }


}
