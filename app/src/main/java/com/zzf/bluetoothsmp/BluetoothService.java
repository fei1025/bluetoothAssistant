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
import com.zzf.bluetoothsmp.liaoTian.Liantian_new;
import com.zzf.bluetoothsmp.utils.BluetoothUuidRepository;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class BluetoothService implements BluetoothBase {
    interface UuidLoader { String load(); }
    interface Listener {
        BluetoothSocket accept() throws IOException;
        void close() throws IOException;
    }
    interface ListenerFactory { Listener open(BluetoothAdapter adapter, String uuid) throws IOException; }
    private static final ListenerFactory DEFAULT_FACTORY = BluetoothService::openListener;
    // Opening and closing listeners share one executor, so a restart releases the
    // old service record before registering its replacement. accept() is separate.
    private static final ScheduledThreadPoolExecutor SERVER_IO = new ScheduledThreadPoolExecutor(1, task -> {
        Thread worker = new Thread(task, "bluetooth-server-io");
        worker.setDaemon(true);
        return worker;
    });
    static { SERVER_IO.setRemoveOnCancelPolicy(true); }
    private BluetoothAdapter mBluetooth;
    private Context mcontex;
    private volatile BluetoothSocket accept;
    private volatile Thread thread;
    private volatile Listener bluetoothService;
    private Future<?> startupTask;
    private final UuidLoader uuidLoader;
    private final ListenerFactory listenerFactory;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong serviceGeneration = new AtomicLong();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WeakReference<Handler> messageHandler = new WeakReference<>(null);

    public BluetoothService() {
        this(() -> BluetoothUuidRepository.loadForTransport().getServerUuid(), DEFAULT_FACTORY);
    }
    // Controlled instrumentation can model delayed database/socket operations
    // without relying on emulator Bluetooth hardware.
    BluetoothService(UuidLoader uuidLoader, ListenerFactory listenerFactory) {
        this.uuidLoader = uuidLoader;
        this.listenerFactory = listenerFactory;
    }
    @SuppressLint("MissingPermission")
    private static Listener openListener(BluetoothAdapter adapter, String uuid) throws IOException {
        if (adapter == null) throw new IOException("Bluetooth adapter is unavailable");
        BluetoothServerSocket socket = adapter.listenUsingInsecureRfcommWithServiceRecord("bluetoothSPP", UUID.fromString(uuid));
        return new Listener() {
            public BluetoothSocket accept() throws IOException { return socket.accept(); }
            public void close() throws IOException { socket.close(); }
        };
    }

    public synchronized void createService(Context mcontex, BluetoothAdapter mBluetooth) throws IOException {
        Context applicationContext = mcontex.getApplicationContext();
        this.mcontex = applicationContext == null ? mcontex : applicationContext;
        if (mcontex instanceof MainActivity) {
            messageHandler = new WeakReference<>(((MainActivity) mcontex).mHandler);
        }
        this.mBluetooth = mBluetooth;
        this.createService();
    }

    @SuppressLint("MissingPermission")
    public synchronized void createService() throws IOException {
        if (running.get()) {
            return;
        }
        final long foregroundGeneration = StaticObject.foregroundGate.currentGeneration();
        if (foregroundGeneration < 0L) {
            return;
        }
        if (mBluetooth == null && listenerFactory == DEFAULT_FACTORY) throw new IOException("Bluetooth adapter is unavailable");
        final long generation = serviceGeneration.incrementAndGet();
        running.set(true);
        Listener previous = bluetoothService;
        BluetoothSocket previousPending = accept;
        bluetoothService = null;
        accept = null;
        if (previous != null || previousPending != null) SERVER_IO.execute(() -> {
            closeSocketQuietly(previousPending);
            closeListenerQuietly(previous);
        });
        final BluetoothAdapter adapter = mBluetooth;
        startupTask = SERVER_IO.submit(() -> startListener(adapter, generation, foregroundGeneration));
    }

    private boolean ownsGeneration(long generation, long foregroundGeneration) {
        return running.get() && serviceGeneration.get() == generation
                && StaticObject.foregroundGate.isCurrent(foregroundGeneration);
    }

    private void startListener(BluetoothAdapter adapter, long generation, long foregroundGeneration) {
        Listener listener = null;
        boolean published = false;
        try {
            if (!ownsGeneration(generation, foregroundGeneration)) return;
            String activeUuid = uuidLoader.load();
            if (!ownsGeneration(generation, foregroundGeneration) || Thread.currentThread().isInterrupted()) return;
            listener = listenerFactory.open(adapter, activeUuid);
            final Listener ready = listener;
            synchronized (this) {
                if (!ownsGeneration(generation, foregroundGeneration) || Thread.currentThread().isInterrupted()) return;
                bluetoothService = ready;
                thread = new Thread(() -> acceptConnections(ready, activeUuid, generation, foregroundGeneration), "bluetooth-spp-accept");
                thread.start();
                published = true;
                startupTask = null;
            }
            BluetoothTelemetry.logServerEvent("server_listen_start");
        } catch (Exception error) {
            boolean report;
            synchronized (this) {
                report = ownsGeneration(generation, foregroundGeneration);
                if (report) running.set(false);
            }
            if (report) {
                senHandlerMessage(0, mcontex.getString(R.string.bluetooth_port_error));
                BluetoothTelemetry.logServerEvent("server_listen_failed");
                Log.e("BluetoothService", "Unable to start SPP server", error);
            }
        } finally {
            if (!published) closeListenerQuietly(listener);
            synchronized (this) {
                if (serviceGeneration.get() == generation && !published) {
                    startupTask = null;
                    running.set(false);
                    if (bluetoothService == listener) bluetoothService = null;
                    thread = null;
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void acceptConnections(Listener serverSocket, String activeServiceUuid, long generation, long foregroundGeneration) {
                BluetoothSocket pendingSocket = null;
                BluetoothServiceConnect activeAcceptedSession = null;
                try {
                    while (running.get()
                            && serviceGeneration.get() == generation
                            && StaticObject.foregroundGate.isCurrent(foregroundGeneration)
                            && !Thread.currentThread().isInterrupted()) {
                        pendingSocket = serverSocket.accept();
                        synchronized (BluetoothService.this) {
                            if (ownsGeneration(generation, foregroundGeneration)) accept = pendingSocket;
                        }
                        if (!ownsGeneration(generation, foregroundGeneration)) {
                            closeSocketQuietly(pendingSocket);
                            pendingSocket = null;
                            break;
                        }
                        BluetoothServiceConnect bluetoothServiceConnect = new BluetoothServiceConnect();
                        if (!bluetoothServiceConnect.start(BluetoothService.this.mcontex,
                                pendingSocket, activeServiceUuid, foregroundGeneration,
                                () -> running.get() && serviceGeneration.get() == generation)) {
                            closeSocketQuietly(pendingSocket);
                            pendingSocket = null;
                            clearPending(generation);
                            continue;
                        }
                        activeAcceptedSession = bluetoothServiceConnect;
                        BluetoothDevice bluetoothDevice = pendingSocket.getRemoteDevice();
                        if (!StaticObject.reconnectManager.onIncomingConnected(
                                bluetoothDevice.getAddress(), foregroundGeneration)) {
                            activeAcceptedSession.closeForLifecycle();
                            activeAcceptedSession = null;
                            break;
                        }
                        BluetoothTelemetry.logServerEvent("server_client_connected");
                        pendingSocket = null;
                        clearPending(generation);
                        BluetoothDrive drive = new BluetoothDrive();
                        String name = bluetoothDevice.getName();
                        if (name == null || name.length() == 0) {
                            name = bluetoothDevice.getAddress();
                        }
                        drive.setDriveName(name);
                        drive.setDriveAdd(bluetoothDevice.getAddress());
                        drive.setUuid(activeServiceUuid);
                        try {
                            BluetoothDeviceProfileStore.markConnected(
                                    bluetoothDevice.getAddress(), name, System.currentTimeMillis());
                        } catch (RuntimeException metadataError) {
                            Log.w("BluetoothService", "Accepted session device metadata could not be saved", metadataError);
                        }
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
                            if (!StaticObject.foregroundGate.isCurrent(foregroundGeneration)
                                    || StaticObject.bluetoothSocketMap.get(remoteAddress)
                                        != bluetoothServiceConnect || !bluetoothServiceConnect.isConnected()) {
                                bluetoothServiceConnect.closeForLifecycle();
                                return;
                            }
                            try {
                                appContext.startActivity(liaoTian);
                            } catch (RuntimeException error) {
                                Log.w("BluetoothService", "Unable to open incoming chat", error);
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
                    boolean report;
                    synchronized (BluetoothService.this) {
                        report = ownsGeneration(generation, foregroundGeneration);
                        if (report) running.set(false);
                    }
                    if (report) {
                        // An accept failure can be caused by revoked Bluetooth permission
                        // or a platform-level socket failure. Do not leave the service marked
                        // as running, otherwise a later retry would be silently skipped.
                        senHandlerMessage(0, mcontex.getString(R.string.bluetooth_port_error));
                        BluetoothTelemetry.logServerEvent("server_listen_failed");
                        Log.e("BluetoothService", "SPP server stopped unexpectedly", e);
                    }
                } finally {
                    if (activeAcceptedSession != null) {
                        activeAcceptedSession.closeForLifecycle();
                    }
                    closeSocketQuietly(pendingSocket);
                    // Every generation owns its local listener, including unexpected
                    // accept/metadata errors. Never close the replacement listener.
                    closeListenerQuietly(serverSocket);
                    synchronized (BluetoothService.this) {
                        if (serviceGeneration.get() == generation) {
                            running.set(false);
                            accept = null;
                            thread = null;
                            if (bluetoothService == serverSocket) bluetoothService = null;
                        }
                    }
                }
    }

    private synchronized void clearPending(long generation) {
        if (serviceGeneration.get() == generation) accept = null;
    }

    public synchronized void stop() {
        // Serialize the owner check with session publication, including a UUID
        // restart within the same foreground interval.
        synchronized (StaticObject.foregroundGate) {
            running.set(false);
            serviceGeneration.incrementAndGet();
        }
        Thread worker = thread;
        if (worker != null) worker.interrupt();
        if (startupTask != null) startupTask.cancel(true);
        startupTask = null;
        BluetoothSocket pendingSocket = accept;
        Listener listener = bluetoothService;
        bluetoothService = null;
        accept = null;
        if (thread == worker) {
            thread = null;
        }
        if (listener != null || pendingSocket != null) SERVER_IO.execute(() -> {
            closeSocketQuietly(pendingSocket);
            closeListenerQuietly(listener);
        });
    }

    /** Includes a generation whose background configuration/listen startup is pending. */
    public boolean isRunning() {
        return running.get();
    }

    private static void closeListenerQuietly(Listener listener) {
        if (listener == null) return;
        try { listener.close(); }
        catch (IOException error) { Log.w("BluetoothService", "Unable to close SPP listener", error); }
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

