package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.event.EventDispatcher;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.loading.WeiboDialogUtils;
import com.zzf.bluetoothsmp.utils.BluetoothUuidRepository;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.util.UUID;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import static com.zzf.bluetoothsmp.R.string.bluetoothConnection;


public class BluetoothObject extends EventDispatcher {
    static final String TAG = "BluetoothObject";
    public static final String SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB";
    private static final ScheduledThreadPoolExecutor CONNECT_TIMEOUTS = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread worker = new Thread(runnable, "bt-connect-timeouts");
        worker.setDaemon(true);
        return worker;
    });
    static { CONNECT_TIMEOUTS.setRemoveOnCancelPolicy(true); }

    private BluetoothDevice bluetoothDevice;
    private BufferedOutputStream bufferedOutputStream;
    private BufferedInputStream bufferedInputStream;
    private WeakReference<Handler> mHandlerReference = new WeakReference<>(null);
    private Context mcontex;
    private Context lanLaContex;
    BluetoothSocket insecureRfcommSocketToServiceRecord;
    private RecyclerView msgRecyclerView;
    private Dialog loadingDialog;
    private volatile boolean connectStart = false;
    private volatile boolean shouldStop = false; // 用于安全停止线程的标志
    private Thread connectFlag;
    private volatile ScheduledFuture<?> connectionTimeoutTask;
    private final AtomicBoolean sessionStarted = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final BluetoothConnectionFailureGate failureGate =
            new BluetoothConnectionFailureGate();
    private volatile Handler mainHandler;
    private BroadcastReceiver bondReceiver;
    private Runnable pairingTimeout;
    private volatile boolean waitingForPairing;
    private String requestedUuid;
    private int reconnectAttempt;
    private BluetoothServiceConnect activeSession;
    private long connectStartedAtMillis;
    private long foregroundGeneration;
    private volatile boolean userCanceled;

    public Context getLanLaContex() {
        return lanLaContex;
    }

    public void setLanLaContex(Context lanLaContex) {
        this.lanLaContex = lanLaContex;
    }

    public void connect(@NonNull Context contex, Handler mHandler) {
        connectInternal(contex, mHandler, false, null);
    }

    public void connectForReconnect(@NonNull Context contex, Handler mHandler, String uuid) {
        connectInternal(contex, mHandler, true, uuid, 0);
    }

    public void connectForReconnect(@NonNull Context contex, Handler mHandler,
                                    String uuid, int attempt) {
        connectInternal(contex, mHandler, true, uuid, attempt);
    }

    private void connectInternal(@NonNull Context contex, Handler mHandler,
                                 boolean reconnect, String reconnectUuid) {
        connectInternal(contex, mHandler, reconnect, reconnectUuid, 0);
    }

    private void connectInternal(@NonNull Context contex, Handler mHandler,
                                 boolean reconnect, String reconnectUuid,
                                 int attempt) {
        mcontex = contex.getApplicationContext();
        if (mcontex == null) {
            mcontex = contex;
        }
        setmHandler(mHandler);
        requestedUuid = reconnectUuid;
        reconnectAttempt = attempt;
        connectStartedAtMillis = System.currentTimeMillis();
        cancelRequested.set(false);
        failureGate.reset();
        connectStart = false;
        sessionStarted.set(false);
        shouldStop = false;
        userCanceled = false;
        foregroundGeneration = StaticObject.foregroundGate.currentGeneration();
        final boolean[] ownsConnectionSlot = {false};
        StaticObject.foregroundGate.runIfCurrent(foregroundGeneration, () -> {
            ownsConnectionSlot[0] = bluetoothDevice != null
                    && !BluetoothSessionOwners.heldByOther("classic", bluetoothDevice.getAddress(), this) && (reconnect
                    ? StaticObject.connectionRegistry.get(bluetoothDevice.getAddress())
                        == BluetoothConnectionState.CONNECTING
                    : StaticObject.connectionRegistry.beginConnect(bluetoothDevice.getAddress()));
            if (ownsConnectionSlot[0]) {
                StaticObject.connectionAttemptRegistry.register(bluetoothDevice.getAddress(), this);
            }
        });
        if (!ownsConnectionSlot[0]) {
            return;
        }
        if (!isCurrentForegroundAttempt()) {
            cancelConnectForLifecycle();
            return;
        }
        boolean bonded = false;
        try {
            bonded = bluetoothDevice.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException ignored) {
            // The actual connection path reports the permission error below.
        }
        BluetoothTelemetry.logConnectAttempt("spp_client", bonded, requestedUuid,
                reconnect, reconnectAttempt);
        if (mcontex instanceof Activity) {
            loadingDialog = WeiboDialogUtils.createLoadingDialog(mcontex, contex.getString(bluetoothConnection));
        }
        try {
            if (bluetoothDevice.getBondState() == BluetoothDevice.BOND_NONE) {
                beginPairing();
                return;
            }
        } catch (SecurityException error) {
            failConnection(BluetoothConnectionErrorCode.PERMISSION_DENIED, R.string.NoBluetoothAccess);
            return;
        }
        startConnectionThreads();
    }

    private void startConnectionThreads() {
        if (!isCurrentForegroundAttempt()) {
            cancelConnectForLifecycle();
            return;
        }
        connectFlag = new Thread(this::connectSocket, "bt-connect");
        connectionTimeoutTask = CONNECT_TIMEOUTS.schedule(this::connectionTimeout, 15L, TimeUnit.SECONDS);
        connectFlag.start();

    }

    @SuppressLint("MissingPermission")
    private void beginPairing() {
        waitingForPairing = true;
        StaticObject.connectionRegistry.set(bluetoothDevice.getAddress(), BluetoothConnectionState.PAIRING);
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        bondReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null || !bluetoothDevice.getAddress().equals(device.getAddress())) {
                    return;
                }
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (state == BluetoothDevice.BOND_BONDED) {
                    unregisterPairingReceiver();
                    startConnectionThreads();
                } else if (state == BluetoothDevice.BOND_NONE) {
                    unregisterPairingReceiver();
                    failConnection(BluetoothConnectionErrorCode.PAIRING_FAILED, R.string.pairing_failed);
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(mcontex, bondReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
            } else {
                mcontex.registerReceiver(bondReceiver, filter);
            }
            pairingTimeout = () -> {
                if (waitingForPairing) {
                    unregisterPairingReceiver();
                    failConnection(BluetoothConnectionErrorCode.PAIRING_FAILED, R.string.pairing_failed);
                }
            };
            getMainHandler().postDelayed(pairingTimeout, 60_000L);
            if (!bluetoothDevice.createBond()) {
                unregisterPairingReceiver();
                failConnection(BluetoothConnectionErrorCode.PAIRING_FAILED, R.string.pairing_failed);
            }
        } catch (SecurityException error) {
            unregisterPairingReceiver();
            failConnection(BluetoothConnectionErrorCode.PERMISSION_DENIED, R.string.NoBluetoothAccess);
        } catch (RuntimeException error) {
            unregisterPairingReceiver();
            failConnection(BluetoothConnectionErrorCode.PAIRING_FAILED, R.string.pairing_failed);
        }
    }

    private void unregisterPairingReceiver() {
        waitingForPairing = false;
        if (pairingTimeout != null) {
            getMainHandler().removeCallbacks(pairingTimeout);
            pairingTimeout = null;
        }
        if (bondReceiver != null) {
            try {
                mcontex.unregisterReceiver(bondReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            bondReceiver = null;
        }
    }

    /**
     * 安全地停止所有线程
     * 使用 interrupt() 方法而不是已废弃的 stop() 方法
     */
    private void stopThreads() {
        shouldStop = true;
        if (connectFlag != null && connectFlag.isAlive()) {
            connectFlag.interrupt();
        }
        cancelConnectionTimeout();
        if (insecureRfcommSocketToServiceRecord != null && !connectStart) {
            try {
                insecureRfcommSocketToServiceRecord.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void connectionTimeout() {
            try {
                if (!connectStart && !cancelRequested.get()) {
                    WeiboDialogUtils.closeDialog(loadingDialog);
                    stopThreads();
                    connectStart = false;
                    failConnection(BluetoothConnectionErrorCode.CONNECTION_TIMEOUT, R.string.connection_timeout);

                }
            } catch (Exception e) {
                Log.e(TAG, "Connection timeout monitor failed", e);
            }
    }

    private void cancelConnectionTimeout() {
        ScheduledFuture<?> task = connectionTimeoutTask;
        connectionTimeoutTask = null;
        if (task != null) task.cancel(false);
    }


    public void senHandlerMessage(Integer what, Object obj) {
        Handler handler = mHandlerReference.get();
        if (handler == null) {
            Log.w(TAG, "Unable to report connection result: handler is unavailable");
            return;
        }
        Message msg = new Message();
        msg.what = what;
        msg.obj = obj;
        handler.sendMessage(msg);
    }


    @SuppressLint("MissingPermission")
    private void connectSocket() {
            if (!isCurrentForegroundAttempt()) {
                cancelConnectionTimeout();
                return;
            }
            String connectionUuid = SPP_UUID;
            try {
                BluetoothManager manager = (BluetoothManager) mcontex.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                if (adapter == null) {
                    failConnection(BluetoothConnectionErrorCode.BLUETOOTH_NOT_SUPPORTED, R.string.BluetoothNotFound);
                    return;
                }
                if (!adapter.isEnabled()) {
                    failConnection(BluetoothConnectionErrorCode.BLUETOOTH_DISABLED, R.string.bluetooth_disabled);
                    return;
                }
                if (adapter != null && adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                }
                // 检查是否应该停止
                if (!isCurrentForegroundAttempt() || Thread.currentThread().isInterrupted()) {
                    return;
                }

                //BluetoothBle bluetoothBle = new BluetoothBle(bluetoothDevice);
                if (requestedUuid == null) {
                    requestedUuid = BluetoothUuidRepository.loadForTransport().getClientUuid();
                }
                UUID uuid = UUID.fromString(requestedUuid == null ? SPP_UUID : requestedUuid);
                connectionUuid = requestedUuid == null ? SPP_UUID : requestedUuid;
                insecureRfcommSocketToServiceRecord = bluetoothDevice.createInsecureRfcommSocketToServiceRecord(uuid);

                // 再次检查是否应该停止
                if (!isCurrentForegroundAttempt() || Thread.currentThread().isInterrupted()) {
                    return;
                }

                insecureRfcommSocketToServiceRecord.connect();

                // 连接后再次检查
                if (!isCurrentForegroundAttempt() || Thread.currentThread().isInterrupted()) {
                    if (insecureRfcommSocketToServiceRecord != null && insecureRfcommSocketToServiceRecord.isConnected()) {
                        try {
                            insecureRfcommSocketToServiceRecord.close();
                        } catch (Exception e) {
                            Log.w(TAG, "Unable to close canceled Bluetooth socket", e);
                        }
                    }
                    return;
                }

                BluetoothServiceConnect bluetoothServiceConnect = new BluetoothServiceConnect();
                if (!bluetoothServiceConnect.start(mcontex, insecureRfcommSocketToServiceRecord,
                        connectionUuid, foregroundGeneration, this::isCurrentForegroundAttempt)) {
                    if (bluetoothServiceConnect.isDuplicateSession()) {
                        // A parallel/manual connection already owns this MAC. Treat this
                        // attempt as superseded instead of opening a duplicate chat or
                        // consuming a reconnect attempt.
                        // This attempt has a terminal outcome. Its independent
                        // timeout must never later mark the authoritative session FAILED.
                        finishSupersededAttempt();
                        StaticObject.reconnectManager.onClientConnected(
                                bluetoothDevice.getAddress(), 0, foregroundGeneration);
                        return;
                    }
                    throw new IllegalStateException("Unable to initialize Bluetooth session");
                }
                activeSession = bluetoothServiceConnect;
                sessionStarted.set(true);
                if (!isCurrentForegroundAttempt() || Thread.currentThread().isInterrupted()) {
                    closeActiveSession(userCanceled);
                    return;
                }
                connectStart = true;
                if (!isCurrentForegroundAttempt()) {
                    closeActiveSession(userCanceled);
                    return;
                }
                boolean acceptedConnection = StaticObject.reconnectManager.onClientConnected(
                        bluetoothDevice.getAddress(), reconnectAttempt, foregroundGeneration,
                        bluetoothServiceConnect.getSessionId());
                if (!acceptedConnection) {
                    closeActiveSession(false);
                    return;
                }
                StaticObject.reconnectManager.remember(bluetoothDevice, connectionUuid, foregroundGeneration);
                BluetoothTelemetry.logConnectSuccess("spp_client", true, connectionUuid,
                        reconnectAttempt > 0, reconnectAttempt,
                        System.currentTimeMillis() - connectStartedAtMillis);
                //Intent liaoTian = new Intent(mcontex, Liao_tian.class);
                Context appContext = mcontex.getApplicationContext();
                Intent liaoTian = new Intent(appContext, Liantian_new.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                String name = bluetoothDevice.getName();
                if (name == null || name.length() == 0) {
                    name = bluetoothDevice.getAddress();
                }
                try {
                    BluetoothDeviceProfileStore.markConnected(
                            bluetoothDevice.getAddress(), name, System.currentTimeMillis());
                } catch (RuntimeException metadataError) {
                    Log.w(TAG, "Connected session device metadata could not be saved", metadataError);
                }
                liaoTian.putExtra("bluetoothName", name);
                liaoTian.putExtra("bluetoothAdd", bluetoothDevice.getAddress());
                liaoTian.putExtra("bluetoothUUid", connectionUuid);
                BluetoothDrive drive = new BluetoothDrive();
                drive.setDriveName(name);
                drive.setDriveAdd(bluetoothDevice.getAddress());
                drive.setUuid(connectionUuid);
                liaoTian.putExtra("BluetoothDrive", drive);
                getMainHandler().post(() -> {
                    if (!isCurrentForegroundAttempt()
                            || StaticObject.bluetoothSocketMap.get(bluetoothDevice.getAddress()) != bluetoothServiceConnect
                            || !bluetoothServiceConnect.isConnected()) {
                        closeActiveSession(userCanceled);
                        return;
                    }
                    try {
                        appContext.startActivity(liaoTian);
                    } catch (RuntimeException error) {
                        Log.w(TAG, "Unable to open outgoing chat", error);
                    }
                });
            } catch (Exception e) {
                WeiboDialogUtils.closeDialog(loadingDialog);
                connectStart = false;
                if (!isCurrentForegroundAttempt()) {
                    return;
                }
                if (bluetoothDevice != null && sessionStarted.get()) {
                    BluetoothServiceConnect session = activeSession;
                    if (session != null) {
                        // The socket failed during post-connect handoff. This is not a
                        // user-requested disconnect; preserve the device's reconnect
                        // preference so the coordinator can retry when appropriate.
                        session.closeForLifecycle();
                    }
                    activeSession = null;
                    sessionStarted.set(false);
                }
                if (!failureGate.tryReport()) {
                    return;
                }
                BluetoothConnectionErrorCode errorCode = BluetoothConnectionErrorCode.classifySocketConnect(e);
                setConnectionError(errorCode);
                senHandlerMessage(0, messageFor(errorCode));
                markConnectionFailed(errorCode);
                BluetoothTelemetry.logConnectFailed("spp_client", errorCode, e,
                        reconnectAttempt > 0, reconnectAttempt,
                        System.currentTimeMillis() - connectStartedAtMillis);
                notifyReconnectAttemptFailed();
                Log.e(TAG, "Bluetooth connection failed", e);
            } finally {
                cancelConnectionTimeout();
                getMainHandler().post(() -> WeiboDialogUtils.closeDialog(loadingDialog));
                if (bluetoothDevice != null) {
                    StaticObject.connectionAttemptRegistry.remove(
                            bluetoothDevice.getAddress(), this);
                }
                if (!sessionStarted.get()) {
                    closeSocketQuietly(insecureRfcommSocketToServiceRecord);
                }
            }
    }

    private static void closeSocketQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Exception e) {
            Log.w(TAG, "Unable to close Bluetooth socket", e);
        }
    }

    /** A parallel accepted session owns this MAC; this client attempt is finished. */
    void finishSupersededAttempt() {
        failureGate.tryReport();
        stopThreads();
    }

    private Handler getMainHandler() {
        Handler current = mainHandler;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (mainHandler == null) {
                mainHandler = new Handler(Looper.getMainLooper());
            }
            return mainHandler;
        }
    }

    private void closeActiveSession(boolean userCanceled) {
        BluetoothServiceConnect session = activeSession;
        activeSession = null;
        if (session != null) {
            if (userCanceled) {
                session.close();
            } else {
                session.closeForLifecycle();
            }
        }
        sessionStarted.set(false);
        connectStart = false;
        closeSocketQuietly(insecureRfcommSocketToServiceRecord);
    }

    private boolean isCurrentForegroundAttempt() {
        return !shouldStop && !cancelRequested.get()
                && StaticObject.foregroundGate.isCurrent(foregroundGeneration);
    }

    public void cancelConnect() {
        cancelConnect(true);
    }

    /** Cancels an in-flight attempt during Activity/process cleanup without changing preferences. */
    void cancelConnectForLifecycle() {
        cancelConnect(false);
    }

    boolean isReconnectAttempt() {
        return reconnectAttempt > 0;
    }

    private void cancelConnect(boolean userInitiated) {
        synchronized (StaticObject.foregroundGate) {
            userCanceled = userInitiated;
            cancelRequested.set(true);
        }
        unregisterPairingReceiver();
        stopThreads();
        if (reconnectAttempt > 0 && bluetoothDevice != null) {
            StaticObject.reconnectManager.cancelAttempt(
                    bluetoothDevice.getAddress(), reconnectAttempt);
        }
        closeActiveSession(userInitiated);
        if (bluetoothDevice != null) {
            StaticObject.connectionAttemptRegistry.remove(bluetoothDevice.getAddress(), this);
        }
        WeiboDialogUtils.closeDialog(loadingDialog);
        markConnectionCanceled();
    }

    private void failConnection(BluetoothConnectionErrorCode errorCode, int messageId) {
        if (cancelRequested.get() || !StaticObject.foregroundGate.isCurrent(foregroundGeneration)
                || !failureGate.tryReport()) {
            return;
        }
        WeiboDialogUtils.closeDialog(loadingDialog);
        setConnectionError(errorCode);
        markConnectionFailed(errorCode);
        BluetoothTelemetry.logConnectFailed("spp_client", errorCode, null,
                reconnectAttempt > 0, reconnectAttempt,
                System.currentTimeMillis() - connectStartedAtMillis);
        senHandlerMessage(0, mcontex.getString(messageId));
        notifyReconnectAttemptFailed();
    }

    private void notifyReconnectAttemptFailed() {
        String address = bluetoothDevice == null ? null : bluetoothDevice.getAddress();
        if (reconnectAttempt > 0) {
            StaticObject.reconnectManager.onAttemptFailed(address, reconnectAttempt, foregroundGeneration);
        }
    }

    private void setConnectionError(BluetoothConnectionErrorCode errorCode) {
        if (bluetoothDevice != null) {
            StaticObject.connectionRegistry.setError(bluetoothDevice.getAddress(), errorCode);
        }
    }

    private String messageFor(BluetoothConnectionErrorCode errorCode) {
        switch (errorCode) {
            case BLUETOOTH_DISABLED:
                return mcontex.getString(R.string.bluetooth_disabled);
            case PERMISSION_DENIED:
                return mcontex.getString(R.string.NoBluetoothAccess);
            case CONNECTION_TIMEOUT:
                return mcontex.getString(R.string.connection_timeout);
            case CONNECTION_REFUSED:
                return mcontex.getString(R.string.connection_refused);
            default:
                return mcontex.getString(R.string.connect_fails);
        }
    }

    private void markConnectionFailed() {
        markConnectionFailed(BluetoothConnectionErrorCode.UNKNOWN_ERROR);
    }

    private void markConnectionCanceled() {
        if (bluetoothDevice == null) {
            return;
        }
        String address = bluetoothDevice.getAddress();
        StaticObject.connectionAttemptRegistry.remove(address, this);
        StaticObject.foregroundGate.runIfCurrent(foregroundGeneration, () -> {
            if (StaticObject.connectionAttemptRegistry.get(address) == null) {
                StaticObject.connectionRegistry.clearError(address);
                StaticObject.connectionRegistry.set(address, BluetoothConnectionState.DISCONNECTED);
            }
        });
    }

    private void markConnectionFailed(BluetoothConnectionErrorCode errorCode) {
        if (bluetoothDevice != null) {
            StaticObject.connectionAttemptRegistry.remove(bluetoothDevice.getAddress(), this);
            StaticObject.foregroundGate.runIfCurrent(foregroundGeneration, () -> {
                setConnectionError(errorCode);
                StaticObject.connectionRegistry.set(bluetoothDevice.getAddress(), BluetoothConnectionState.FAILED);
            });
        }
    }

    public BluetoothDevice getBluetoothDevice() {
        return bluetoothDevice;
    }

    public void setBluetoothDevice(BluetoothDevice bluetoothDevice) {
        this.bluetoothDevice = bluetoothDevice;
    }

    public Handler getmHandler() {
        return mHandlerReference.get();
    }

    public void setmHandler(Handler mHandler) {
        mHandlerReference = new WeakReference<>(mHandler);
    }

    public RecyclerView getMsgRecyclerView() {
        return msgRecyclerView;
    }

    public void setMsgRecyclerView(RecyclerView msgRecyclerView) {
        this.msgRecyclerView = msgRecyclerView;
    }

    public boolean isConnectStart() {
        return connectStart;
    }

    public void setConnectStart(boolean connectStart) {
        this.connectStart = connectStart;
    }

    public BufferedOutputStream getBufferedOutputStream() {
        return bufferedOutputStream;
    }

    public void setBufferedOutputStream(BufferedOutputStream bufferedOutputStream) {
        this.bufferedOutputStream = bufferedOutputStream;
    }

    public BufferedInputStream getBufferedInputStream() {
        return bufferedInputStream;
    }

    public void setBufferedInputStream(BufferedInputStream bufferedInputStream) {
        this.bufferedInputStream = bufferedInputStream;
    }
}
