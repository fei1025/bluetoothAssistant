package com.zzf.bluetoothsmp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.Looper;
import android.util.Log;

import com.zzf.bluetoothsmp.entity.BluetoothDrive;
import com.zzf.bluetoothsmp.entity.SystemInfoMapper;
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.utils.StringUtils;

import org.litepal.LitePal;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class BluetoothService implements BluetoothBase {
    private BluetoothAdapter mBluetooth;
    private Context mcontex;
    private volatile BluetoothSocket accept;
    private volatile Thread thread;
    private volatile BluetoothServerSocket bluetoothService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong serviceGeneration = new AtomicLong();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WeakReference<Handler> messageHandler = new WeakReference<>(null);

    public void createService(Context mcontex, BluetoothAdapter mBluetooth) throws IOException {
        Context applicationContext = mcontex.getApplicationContext();
        this.mcontex = applicationContext == null ? mcontex : applicationContext;
        if (mcontex instanceof MainActivity) {
            messageHandler = new WeakReference<>(((MainActivity) mcontex).mHandler);
        }
        this.mBluetooth = mBluetooth;
        this.createService();
        //中端线程
        //thread.interrupt();
    }

    @SuppressLint("MissingPermission")
    public synchronized void createService() throws IOException {
        if (running.get()) {
            return;
        }
        if (mBluetooth == null) {
            throw new IOException("Bluetooth adapter is unavailable");
        }
        SystemInfoMapper first = LitePal.findFirst(SystemInfoMapper.class);
        String serviceUuid = BluetoothObject.SPP_UUID;

        if (first!=null &&StringUtils.isNotEmpty(first.getServiceSpp())) {
            String serviceSpp = first.getServiceSpp();
            try {
                UUID uuid = UUID.fromString(serviceSpp);
                serviceUuid = serviceSpp;
                bluetoothService = mBluetooth.listenUsingInsecureRfcommWithServiceRecord("bluetoothSPP", uuid);
            } catch (Exception e) {
                Log.e("BluetoothService", "Invalid or unavailable configured SPP service", e);
                throw new IOException("Invalid or unavailable configured SPP service", e);
            }
        } else {
            bluetoothService = mBluetooth.listenUsingInsecureRfcommWithServiceRecord("bluetoothSPP", UUID.fromString(BluetoothObject.SPP_UUID));

        }
        final String activeServiceUuid = serviceUuid;
        final BluetoothServerSocket serverSocket = bluetoothService;
        BluetoothTelemetry.logServerEvent("server_listen_start");
        final long generation = serviceGeneration.incrementAndGet();
        running.set(true);
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                BluetoothSocket pendingSocket = null;
                BluetoothServiceConnect activeAcceptedSession = null;
                try {
                    while (running.get()
                            && serviceGeneration.get() == generation
                            && !Thread.currentThread().isInterrupted()) {
                        pendingSocket = serverSocket.accept();
                        accept = pendingSocket;
                        if (!running.get()) {
                            closeSocketQuietly(pendingSocket);
                            pendingSocket = null;
                            break;
                        }
                        BluetoothServiceConnect bluetoothServiceConnect = new BluetoothServiceConnect();
                        if (!bluetoothServiceConnect.start(BluetoothService.this.mcontex,
                                pendingSocket, activeServiceUuid)) {
                            closeSocketQuietly(pendingSocket);
                            pendingSocket = null;
                            accept = null;
                            continue;
                        }
                        activeAcceptedSession = bluetoothServiceConnect;
                        BluetoothDevice bluetoothDevice = pendingSocket.getRemoteDevice();
                        StaticObject.reconnectManager.onIncomingConnected(
                                bluetoothDevice.getAddress());
                        BluetoothTelemetry.logServerEvent("server_client_connected");
                        pendingSocket = null;
                        accept = null;
                        BluetoothDrive drive = new BluetoothDrive();
                        String name = bluetoothDevice.getName();
                        if (name == null || name.length() == 0) {
                            name = bluetoothDevice.getAddress();
                        }
                        drive.setDriveName(name);
                        drive.setDriveAdd(bluetoothDevice.getAddress());
                        drive.setUuid(activeServiceUuid);
                        BluetoothDeviceProfileStore.markConnected(
                                bluetoothDevice.getAddress(), name, System.currentTimeMillis());
                        final String displayName = name;
                        final String remoteAddress = bluetoothDevice.getAddress();
                        Context appContext = BluetoothService.this.mcontex.getApplicationContext();
                        Intent liaoTian = new Intent(appContext, Liantian_new.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra("bluetoothName", displayName)
                                .putExtra("bluetoothAdd", remoteAddress)
                                .putExtra("bluetoothUUid", activeServiceUuid)
                                .putExtra("BluetoothDrive", drive);
                        mainHandler.post(() -> {
                            if (!MyApplication.isAppInForeground()) {
                                BluetoothConnectionNotification.show(
                                        appContext, displayName, remoteAddress, activeServiceUuid);
                                return;
                            }
                            try {
                                appContext.startActivity(liaoTian);
                            } catch (RuntimeException error) {
                                Log.w("BluetoothService", "Unable to open incoming chat", error);
                                BluetoothConnectionNotification.show(
                                        appContext, displayName, remoteAddress, activeServiceUuid);
                            }
                        });
                        // The session is now owned by the process-level connection map.
                        // Keep it open if the UI handoff is queued successfully.
                        activeAcceptedSession = null;
                    }
                } catch (Exception e) {
                    if (activeAcceptedSession != null) {
                        activeAcceptedSession.closeForLifecycle();
                        activeAcceptedSession = null;
                    }
                    if (running.get() && serviceGeneration.get() == generation) {
                        // An accept failure can be caused by revoked Bluetooth permission
                        // or a platform-level socket failure. Do not leave the service marked
                        // as running, otherwise a later retry would be silently skipped.
                        running.set(false);
                        senHandlerMessage(0, mcontex.getString(R.string.bluetooth_port_error));
                        BluetoothTelemetry.logServerEvent("server_listen_failed");
                        Log.e("BluetoothService", "SPP server stopped unexpectedly", e);
                    }
                } finally {
                    if (activeAcceptedSession != null) {
                        activeAcceptedSession.closeForLifecycle();
                    }
                    closeSocketQuietly(pendingSocket);
                    if (serviceGeneration.get() == generation) {
                        accept = null;
                        thread = null;
                    }
                }
            }
        });
        thread.start();
    }

    public synchronized void stop() {
        running.set(false);
        serviceGeneration.incrementAndGet();
        Thread worker = thread;
        if (thread != null) {
            thread.interrupt();
        }
        closeSocketQuietly(accept);
        try {
            if (bluetoothService != null) {
                bluetoothService.close();
            }
        } catch (IOException e) {
            Log.w("BluetoothService", "Unable to close SPP server socket", e);
        }
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        bluetoothService = null;
        accept = null;
        if (thread == worker) {
            thread = null;
        }
    }

    /** Returns whether the current generation still has a live accept worker. */
    public boolean isRunning() {
        Thread worker = thread;
        return running.get() && worker != null && worker.isAlive();
    }

    private static void closeSocketQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
    public void senHandlerMessage(Integer what, Object obj) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = obj;
        Handler handler = messageHandler.get();
        if (handler != null) {
            handler.sendMessage(msg);
        }
    }
}


