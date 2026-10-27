package com.zzf.bluetoothsmp.plugin;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.*;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import androidx.core.content.ContextCompat;
import com.zzf.bluetoothsmp.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import static com.zzf.bluetoothsmp.plugin.PluginJson.object;

/** RFCOMM connector with no chat Activity, persistence, saved framing or automatic reconnect. */
@SuppressLint("MissingPermission")
public final class PluginClassicTransport implements PluginTransport {
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService connector = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> new Thread(runnable, "plugin-rfcomm-connect"));
    private final Map<Long, Reply> pending = new HashMap<>();
    private final Set<Long> started = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile BluetoothServiceConnect session;
    private BluetoothSocket socket;
    private Reply connecting;
    private String address = "";
    private volatile long generation;
    private boolean scanning;
    private volatile boolean closed;
    private final Set<String> seen = new HashSet<>();
    private final Runnable scanTimeout = this::stopScan;
    private Runnable connectTimeout;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (!scanning || !StaticObject.foregroundGate.isActive()) return;
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && (seen.contains(device.getAddress()) || seen.size() < 256)) {
                    seen.add(device.getAddress());
                    listener.event("device", object("transport", "classic", "address", device.getAddress(), "name", device.getName(),
                            "rssi", intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) 0), "bondState", device.getBondState()));
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) stopScan();
        }
    };
    public PluginClassicTransport(Context context, Listener listener) { this.context = context.getApplicationContext(); this.listener = listener; }
    private BluetoothAdapter adapter() { BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE); return manager == null ? null : manager.getAdapter(); }
    @Override public void execute(String action, JSONObject params, Reply reply) {
        try {
            BluetoothAdapter adapter = adapter();
            if (adapter == null || !adapter.isEnabled()) { reply.error("BLUETOOTH_DISABLED", "Bluetooth is unavailable"); return; }
            switch (action) {
                case "paired": {
                    JSONArray devices = new JSONArray();
                    for (BluetoothDevice device : adapter.getBondedDevices()) devices.put(object("transport", "classic", "address", device.getAddress(),
                            "name", device.getName(), "bondState", device.getBondState()));
                    reply.success(devices); return;
                }
                case "scan":
                    if (scanning) { reply.success(object("scanning", true)); return; }
                    if (adapter.isDiscovering()) { reply.error("DEVICE_BUSY", "Another discovery is running"); return; }
                    seen.clear();
                    IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND); filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
                    scanning = true;
                    if (!adapter.startDiscovery()) { stopScan(); reply.error("SCAN_FAILED", "Cannot start discovery"); return; }
                    main.postDelayed(scanTimeout, 15000); reply.success(object("scanning", true)); return;
                case "stopScan": stopScan(); reply.success(null); return;
                case "connect": connect(params.getString("address"), params.optString("uuid", BluetoothObject.SPP_UUID), reply); return;
                case "disconnect": disconnect(); reply.success(null); return;
                case "write":
                    if (session == null || !session.isSessionOpen() || !session.getSessionId().equals(params.optString("sessionId"))) {
                        reply.error("SESSION_EXPIRED", "The classic Bluetooth session has ended"); return;
                    }
                    byte[] bytes = Base64.decode(params.getString("bytes"), Base64.NO_WRAP);
                    if (bytes.length == 0 || bytes.length > 65536) throw new IllegalArgumentException();
                    long requestId = BluetoothRequestIds.next(); pending.put(requestId, reply);
                    try { session.sendPlugin(requestId, bytes); }
                    catch (RuntimeException failure) { pending.remove(requestId); reply.error("SESSION_ENDED", "The session has ended"); }
                    return;
                default: reply.error("METHOD_NOT_FOUND", "Unknown classic Bluetooth operation");
            }
        } catch (SecurityException denied) { reply.error("PERMISSION_REQUIRED", "Bluetooth permission is required"); }
        catch (Exception invalid) { reply.error("INVALID_ARGUMENT", "Invalid classic Bluetooth parameters"); }
    }
    private void connect(String target, String uuid, Reply reply) throws Exception {
        if (busy()) { reply.error("DEVICE_BUSY", "Disconnect the current device first"); return; }
        if (!BluetoothAdapter.checkBluetoothAddress(target)) throw new IllegalArgumentException();
        UUID service = UUID.fromString(uuid);
        stopScan();
        BluetoothServiceConnect candidate = new BluetoothServiceConnect();
        synchronized (StaticObject.foregroundGate) {
            if (!StaticObject.foregroundGate.isActive()) { reply.error("FOREGROUND_REQUIRED", "Keep the plugin in the foreground"); return; }
            if (StaticObject.connectionAttemptRegistry.get(target) != null || StaticObject.bluetoothSocketMap.containsKey(target)
                    || !BluetoothSessionOwners.acquire("classic", target, candidate)) {
                reply.error("DEVICE_BUSY", "This device is in use"); return;
            }
            session = candidate; address = target; connecting = reply; generation++;
        }
        final long attempt = generation;
        candidate.configurePlugin(new BluetoothServiceConnect.PluginListener() {
            @Override public void onData(byte[] bytes) {
                // Native producer -> bounded runtime queue, without an unbounded main-thread post per read.
                if (session == candidate) listener.event("data", object("transport", "classic", "sessionId", candidate.getSessionId(),
                        "bytes", Base64.encodeToString(bytes, Base64.NO_WRAP)));
            }
            @Override public void onSend(long id, BluetoothSendStatus status, Throwable error) {
                if (status == BluetoothSendStatus.SENDING) { started.add(id); return; }
                if (!status.isTerminal()) return;
                main.post(() -> {
                    Reply result = pending.remove(id);
                    boolean hadStarted = started.remove(id);
                    if (result == null) return;
                    if (status == BluetoothSendStatus.SENT) result.success(object("status", "SENT"));
                    else result.error(error instanceof BluetoothQueueFullException ? "QUEUE_FULL" : hadStarted ? "OUTCOME_UNKNOWN" : status == BluetoothSendStatus.CANCELED ? "SESSION_ENDED" : "OPERATION_FAILED", "Classic Bluetooth write " + status);
                });
            }
            @Override public void onClosed() { main.post(() -> { if (session == candidate) finishClosed("SESSION_ENDED"); }); }
        });
        final long foreground = StaticObject.foregroundGate.currentGeneration();
        connectTimeout = () -> { if (session == candidate && connecting != null) { connecting.error("CONNECT_TIMEOUT", "Connection timed out"); connecting = null; disconnect(); } };
        main.postDelayed(connectTimeout, 20000);
        try { connector.execute(() -> {
            BluetoothSocket opened = null;
            try {
                opened = adapter().getRemoteDevice(target).createInsecureRfcommSocketToServiceRecord(service);
                synchronized (this) {
                    if (closed || generation != attempt || session != candidate) { opened.close(); return; }
                    socket = opened;
                }
                opened.connect();
                if (closed || generation != attempt || session != candidate || !StaticObject.foregroundGate.isCurrent(foreground)
                        || !candidate.start(context, opened, uuid, foreground)) throw new IllegalStateException();
                main.post(() -> {
                    if (session != candidate || generation != attempt) return;
                    if (connectTimeout != null) main.removeCallbacks(connectTimeout);
                    Reply result = connecting; connecting = null;
                    if (result != null) result.success(snapshot());
                    listener.event("state", snapshot());
                });
            } catch (Exception error) {
                if (opened != null) try { opened.close(); } catch (Exception ignored) { }
                main.post(() -> {
                    if (session != candidate) return;
                    if (connecting != null) { connecting.error("CONNECT_FAILED", "Cannot connect to this device"); connecting = null; }
                    disconnect();
                });
            }
        }); } catch (RejectedExecutionException full) {
            connecting = null; reply.error("QUEUE_FULL", "A previous connection attempt is still closing"); disconnect();
        }
    }
    @Override public JSONObject snapshot() { return object("transport", "classic", "sessionId", session == null ? "" : session.getSessionId(),
            "address", address, "connected", session != null && connecting == null && session.isConnected(), "connecting", connecting != null); }
    @Override public boolean busy() { return session != null; }
    @Override public void stopScan() {
        main.removeCallbacks(scanTimeout);
        if (!scanning) return;
        scanning = false;
        try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) { }
        try { if (adapter() != null) adapter().cancelDiscovery(); } catch (RuntimeException ignored) { }
    }
    private void finishClosed(String reason) {
        BluetoothServiceConnect old = session; session = null; generation++;
        if (old != null && !address.isEmpty()) BluetoothSessionOwners.release("classic", address, old);
        if (connectTimeout != null) main.removeCallbacks(connectTimeout);
        if (connecting != null) { connecting.error(reason, "Connection ended"); connecting = null; }
        for (Map.Entry<Long, Reply> entry : new ArrayList<>(pending.entrySet())) entry.getValue().error(started.contains(entry.getKey()) ? "OUTCOME_UNKNOWN" : reason, "Connection ended");
        pending.clear(); started.clear();
        listener.event("state", snapshot());
    }
    @Override public void disconnect() {
        stopScan();
        BluetoothServiceConnect old = session;
        finishClosed("SESSION_ENDED");
        if (old != null) old.closeForLifecycle();
        BluetoothSocket pendingSocket;
        synchronized (this) { pendingSocket = socket; socket = null; }
        if (pendingSocket != null) try { pendingSocket.close(); } catch (Exception ignored) { }
    }
    @Override public void close() { closed = true; disconnect(); connector.shutdownNow(); main.removeCallbacksAndMessages(null); }
}
