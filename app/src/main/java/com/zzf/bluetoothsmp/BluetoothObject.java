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
import com.zzf.bluetoothsmp.entity.SystemInfoMapper;
import com.zzf.bluetoothsmp.event.EventDispatcher;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.loading.WeiboDialogUtils;
import com.zzf.bluetoothsmp.utils.StringUtils;
import com.zzf.bluetoothsmp.utils.ToastUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.util.UUID;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import static com.zzf.bluetoothsmp.R.string.bluetoothConnection;

import org.litepal.LitePal;

public class BluetoothObject extends EventDispatcher {
    static final String TAG = "BluetoothObject";
    public static final String SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB";

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
    private Thread checkConnect;
    private Thread waitingThread;
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
        boolean ownsConnectionSlot = reconnect
                ? StaticObject.connectionRegistry.get(bluetoothDevice == null ? null : bluetoothDevice.getAddress())
                == BluetoothConnectionState.CONNECTING
                : bluetoothDevice != null
                && StaticObject.connectionRegistry.beginConnect(bluetoothDevice.getAddress());
        if (bluetoothDevice == null || !ownsConnectionSlot) {
            senHandlerMessage(0, contex.getString(R.string.bluetooth_connection_in_progress));
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
        StaticObject.connectionAttemptRegistry.register(bluetoothDevice.getAddress(), this);
        shouldStop = false; // 重置停止标志
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
        connectFlag = new Thread(this::connectSocket, "bt-connect");
        checkConnect = new Thread(this::waitForConnection, "bt-connect-monitor");
        waitingThread = new Thread(this::connectionTimeout, "bt-connect-timeout");
        connectFlag.start();
        checkConnect.start();
        waitingThread.start();

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
        if (checkConnect != null && checkConnect.isAlive()) {
            checkConnect.interrupt();
        }
        if (waitingThread != null && waitingThread.isAlive()) {
            waitingThread.interrupt();
        }
        if (insecureRfcommSocketToServiceRecord != null && !connectStart) {
            try {
                insecureRfcommSocketToServiceRecord.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void connectionTimeout() {
            try {
                Thread.sleep(1000L * 15);
                if (!connectStart && !cancelRequested.get()) {
                    WeiboDialogUtils.closeDialog(loadingDialog);
                    stopThreads();
                    connectStart = false;
                    failConnection(BluetoothConnectionErrorCode.CONNECTION_TIMEOUT, R.string.connection_timeout);

                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "Connection timeout monitor failed", e);
            }
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


    private void waitForConnection() {
            while (!shouldStop && !Thread.currentThread().isInterrupted()) {
                if (connectStart) {
                    WeiboDialogUtils.closeDialog(loadingDialog);
                    return;
                }
                try {
                    Thread.sleep(100); // 避免 CPU 空转
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
    }

    @SuppressLint("MissingPermission")
    private void connectSocket() {
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
                if (shouldStop || cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                
                //BluetoothBle bluetoothBle = new BluetoothBle(bluetoothDevice);
                if (requestedUuid == null) {
                    SystemInfoMapper first = LitePal.findFirst(SystemInfoMapper.class);
                    if (first != null && StringUtils.isNotEmpty(first.getClientSpp())) {
                        requestedUuid = first.getClientSpp();
                    }
                }
                UUID uuid = UUID.fromString(requestedUuid == null ? SPP_UUID : requestedUuid);
                connectionUuid = requestedUuid == null ? SPP_UUID : requestedUuid;
                insecureRfcommSocketToServiceRecord = bluetoothDevice.createInsecureRfcommSocketToServiceRecord(uuid);

                // 再次检查是否应该停止
                if (shouldStop || Thread.currentThread().isInterrupted()) {
                    return;
                }

                insecureRfcommSocketToServiceRecord.connect();
                
                // 连接后再次检查
                if (shouldStop || cancelRequested.get() || Thread.currentThread().isInterrupted()) {
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
                if (!bluetoothServiceConnect.start(mcontex, insecureRfcommSocketToServiceRecord, connectionUuid)) {
                    if (bluetoothServiceConnect.isDuplicateSession()) {
                        // A parallel/manual connection already owns this MAC. Treat this
                        // attempt as superseded instead of opening a duplicate chat or
                        // consuming a reconnect attempt.
                        StaticObject.reconnectManager.onConnected(bluetoothDevice.getAddress());
                        return;
                    }
                    throw new IllegalStateException("Unable to initialize Bluetooth session");
                }
                activeSession = bluetoothServiceConnect;
                sessionStarted.set(true);
                if (shouldStop || cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                    closeActiveSession(cancelRequested.get());
                    return;
                }
                connectStart = true;
                if (shouldStop || cancelRequested.get()) {
                    closeActiveSession(cancelRequested.get());
                    return;
                }
                boolean acceptedConnection = reconnectAttempt > 0
                        ? StaticObject.reconnectManager.onConnected(
                                bluetoothDevice.getAddress(), reconnectAttempt)
                        : acceptManualConnection(bluetoothDevice.getAddress());
                if (!acceptedConnection) {
                    closeActiveSession(false);
                    return;
                }
                StaticObject.reconnectManager.remember(bluetoothDevice, connectionUuid);
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
                BluetoothDeviceProfileStore.markConnected(
                        bluetoothDevice.getAddress(), name, System.currentTimeMillis());
                liaoTian.putExtra("bluetoothName", name);
                liaoTian.putExtra("bluetoothAdd", bluetoothDevice.getAddress());
                liaoTian.putExtra("bluetoothUUid", connectionUuid);
                BluetoothDrive drive = new BluetoothDrive();
                drive.setDriveName(name);
                drive.setDriveAdd(bluetoothDevice.getAddress());
                drive.setUuid(connectionUuid);
                liaoTian.putExtra("BluetoothDrive", drive);
                final String displayName = name;
                final String remoteAddress = bluetoothDevice.getAddress();
                final String activeConnectionUuid = connectionUuid;
                getMainHandler().post(() -> {
                    if (cancelRequested.get() || shouldStop) {
                        closeActiveSession(cancelRequested.get());
                        return;
                    }
                    if (!MyApplication.isAppInForeground()) {
                        BluetoothConnectionNotification.show(
                                appContext, displayName, remoteAddress, activeConnectionUuid);
                        return;
                    }
                    try {
                        appContext.startActivity(liaoTian);
                    } catch (RuntimeException error) {
                        Log.w(TAG, "Unable to open outgoing chat", error);
                        BluetoothConnectionNotification.show(
                                appContext, displayName, remoteAddress, activeConnectionUuid);
                    }
                });
            } catch (Exception e) {
                WeiboDialogUtils.closeDialog(loadingDialog);
                connectStart = false;
                if (cancelRequested.get()) {
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

    private boolean acceptManualConnection(String address) {
        StaticObject.reconnectManager.onConnected(address);
        return true;
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
        cancelRequested.set(true);
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
        if (cancelRequested.get() || !failureGate.tryReport()) {
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
            StaticObject.reconnectManager.onAttemptFailed(address, reconnectAttempt);
        } else {
            StaticObject.reconnectManager.onAttemptFailed(address);
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
        StaticObject.connectionRegistry.clearError(address);
        StaticObject.connectionRegistry.set(address, BluetoothConnectionState.DISCONNECTED);
    }

    private void markConnectionFailed(BluetoothConnectionErrorCode errorCode) {
        if (bluetoothDevice != null) {
            StaticObject.connectionAttemptRegistry.remove(bluetoothDevice.getAddress(), this);
            setConnectionError(errorCode);
            StaticObject.connectionRegistry.set(bluetoothDevice.getAddress(), BluetoothConnectionState.FAILED);
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
