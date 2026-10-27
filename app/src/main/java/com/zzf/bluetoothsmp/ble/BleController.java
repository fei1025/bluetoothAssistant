package com.zzf.bluetoothsmp.ble;

import android.annotation.SuppressLint;
import android.app.Application;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.lifecycle.AndroidViewModel;
import com.zzf.bluetoothsmp.BluetoothPermissionUtils;
import com.zzf.bluetoothsmp.R;
import com.zzf.bluetoothsmp.StaticObject;
import com.zzf.bluetoothsmp.BluetoothSessionOwners;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** One foreground BLE central connection. All GATT procedures are serialized on main. */
@SuppressLint("MissingPermission")
public class BleController extends AndroidViewModel {
    public interface Listener { void onUpdated(); }
    public interface Observer {
        void onData(BluetoothGattCharacteristic characteristic, byte[] bytes);
        void onResult(long requestId, String kind, int status, String error, byte[] bytes, int completedBytes);
        void onScan(ScanResult result);
        void onState(boolean ready, String address, String error);
        void onOverflow();
    }
    Observer observer;
    boolean pluginMode;
    private final AtomicLong operationIds = new AtomicLong();
    private final Map<Long, Request> observed = new LinkedHashMap<>();
    public void closeController() { onCleared(); }

    private static final UUID CCCD = BlePayload.uuid("2902");
    private static final long OP_TIMEOUT = 15_000L;
    private final BleGattConnection.Factory connectionFactory;
    private final BleGattConnection.Gate foregroundGate;
    private final BleGattConnection.Gate permissionGate;
    private final long operationTimeoutMillis;
    private final long connectTimeoutMillis;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BleRequestQueue<Request> queue = new BleRequestQueue<>(128, 2 * 1024 * 1024);
    final BleLogStore logs = new BleLogStore();
    final LinkedHashMap<String, ScanResult> devices = new LinkedHashMap<>();
    final Set<BluetoothGattCharacteristic> subscribed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicInteger pendingRx = new AtomicInteger();
    final AtomicLong droppedRx = new AtomicLong();
    private BluetoothLeScanner scanner;
    private volatile ScanCallback scanCallback;
    private volatile BleGattConnection gatt;
    private String target = "";
    private long sessionId;
    private volatile long procedureGeneration;
    private volatile boolean procedureInFlight;
    private volatile boolean cleared;
    private boolean connecting;
    private boolean ready;
    private boolean mtuRequested;
    private boolean updatePosted;
    private long lastScan = -10_000L;
    private Listener listener;
    int state = R.string.ble_disconnected;
    String lastError = "";
    int mtu = 23;
    int rssi;
    long txBytes, rxBytes;
    BluetoothGattCharacteristic writeTarget;
    private Request repeat;
    private long repeatGeneration;
    private int repeatInterval;
    private final Runnable scanTimeout = this::stopScan;
    private Runnable timeout;
    private final Runnable repeatNext = () -> {
        if (repeat != null && isReady()) {
            Request next = repeat.copy();
            next.repeated = true;
            next.repeatGeneration = repeatGeneration;
            if (!enqueue(next)) stopRepeat();
        }
    };

    public BleController(Application application) {
        this(application, (address, events) -> AndroidBleGattConnection.open(application, address, events),
                StaticObject.foregroundGate::isActive,
                () -> BluetoothPermissionUtils.hasConnectPermission(application), OP_TIMEOUT, 20_000L);
    }

    BleController(Application application, BleGattConnection.Factory factory,
              BleGattConnection.Gate foreground, BleGattConnection.Gate permission,
              long operationTimeoutMillis, long connectTimeoutMillis) {
        super(application);
        connectionFactory = factory;
        foregroundGate = foreground;
        permissionGate = permission;
        this.operationTimeoutMillis = operationTimeoutMillis;
        this.connectTimeoutMillis = connectTimeoutMillis;
        BleRuntime.register(this);
    }

    void setListener(Listener value) { listener = value; if (value != null) value.onUpdated(); }
    private boolean foreground() { return !cleared && foregroundGate.allowed(); }
    boolean isScanning() { return scanCallback != null; }
    boolean hasConnection() { return gatt != null; }
    boolean isReady() { return ready && gatt != null && foreground(); }
    boolean isRepeating() { return repeat != null; }
    String address() { return target; }
    int pendingCount() { return queue.count(); }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) getApplication().getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    void scan() {
        if (!foreground() || isScanning()) return;
        BluetoothAdapter adapter = adapter();
        if (!BluetoothPermissionUtils.hasDiscoveryPermissions(getApplication())) { error("PERMISSION_REQUIRED"); return; }
        if (SystemClock.elapsedRealtime() - lastScan < 6_000L) { error("SCAN_THROTTLED: wait 6 s"); return; }
        try {
            if (adapter == null || !adapter.isEnabled()) { error("BLUETOOTH_DISABLED"); return; }
            adapter.cancelDiscovery();
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { error("BLE_SCANNER_UNAVAILABLE"); return; }
            devices.clear();
            lastError = "";
            ScanCallback callback = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    if (scanCallback != this || !foreground()) return;
                    String mac = result.getDevice().getAddress();
                    if (devices.containsKey(mac) || devices.size() < 256) {
                        devices.put(mac, result);
                        if (observer != null) observer.onScan(result);
                    }
                    changed();
                }
                @Override public void onBatchScanResults(List<ScanResult> results) {
                    for (ScanResult result : results) onScanResult(0, result);
                }
                @Override public void onScanFailed(int code) {
                    if (scanCallback != this) return;
                    stopScan();
                    error("SCAN_FAILED " + code);
                }
            };
            scanCallback = callback;
            lastScan = SystemClock.elapsedRealtime();
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
            main.postDelayed(scanTimeout, 15_000L);
            changed();
        } catch (RuntimeException failure) { stopScan(); error(failure.getClass().getSimpleName()); }
    }

    void stopScan() {
        ScanCallback old = scanCallback;
        scanCallback = null;
        main.removeCallbacks(scanTimeout);
        if (old != null && scanner != null) {
            try { scanner.stopScan(old); } catch (RuntimeException ignored) { }
        }
        scanner = null;
        changed();
    }

    void connect(BluetoothDevice device) {
        if (device == null) { error("INVALID_DEVICE"); return; }
        connect(device.getAddress());
    }

    void connect(String address) {
        if (!foreground()) return;
        if (!permissionGate.allowed()) { suspend(); error("PERMISSION_REQUIRED"); return; }
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) { error("INVALID_ADDRESS"); return; }
        if (BluetoothSessionOwners.heldByOther("ble", address, this)) { error("DEVICE_BUSY"); return; }
        stopScan();
        disconnect(null);
        if (!BluetoothSessionOwners.acquire("ble", address, this)) { error("DEVICE_BUSY"); return; }
        target = address;
        sessionId++;
        lastError = "";
        mtu = 23;
        mtuRequested = false;
        rssi = 0;
        txBytes = rxBytes = 0;
        state = R.string.ble_connecting;
        connecting = true;
        try {
            gatt = connectionFactory.open(address, callback);
            if (gatt == null) { disconnect("CONNECT_START_FAILED"); return; }
            armTimeout(connectTimeoutMillis);
            record("CONNECT " + address(), null);
        } catch (RuntimeException failure) { disconnect("CONNECT_FAILED " + failure.getClass().getSimpleName()); }
        changed();
    }

    void reconnect() { if (!target.isEmpty()) connect(target); }

    private void cancelTimeout() {
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
    }

    private void armTimeout(long delay) {
        cancelTimeout();
        BleGattConnection source = gatt;
        long generation = procedureGeneration;
        timeout = () -> {
            if (source == gatt && generation == procedureGeneration) {
                disconnect("TIMEOUT: outcome unknown; reconnect before retry");
            }
        };
        main.postDelayed(timeout, delay);
    }

    void disconnect(String reason) {
        stopRepeat();
        cancelTimeout();
        boolean wasInFlight = procedureInFlight;
        procedureInFlight = false;
        procedureGeneration++;
        Request active = queue.current();
        if (queue.count() > 0) record("CANCELED " + queue.count() + " request(s); active written bytes="
                + (active == null ? 0 : active.offset), null);
        for (Request pending : new ArrayList<>(observed.values())) {
            if (observer != null) observer.onResult(pending.id, pending.kind.name(), -1,
                    pending == active && ((reason != null && reason.contains("TIMEOUT"))
                            || pending.kind == Kind.WRITE && (wasInFlight || pending.offset > 0)) ? "OUTCOME_UNKNOWN" : "SESSION_ENDED",
                    pending.result, pending.offset);
        }
        observed.clear();
        queue.clear();
        if (!target.isEmpty()) BluetoothSessionOwners.release("ble", target, this);
        ready = connecting = false;
        writeTarget = null;
        subscribed.clear();
        state = R.string.ble_disconnected;
        BleGattConnection old = gatt;
        gatt = null; // Reject late callbacks before calling into the platform.
        if (old != null) {
            try { old.disconnect(); } catch (RuntimeException ignored) { }
            try { old.close(); } catch (RuntimeException ignored) { }
        }
        if (reason != null) {
            if (!reason.equals("USER_DISCONNECTED") && !reason.equals("FOREGROUND_SUSPENDED")) lastError = reason;
            record(reason, null);
        }
        changed();
    }

    public void suspend() {
        if (cleared) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            BleGattConnection capturedConnection = gatt;
            ScanCallback capturedScan = scanCallback;
            main.post(() -> {
                if (cleared) return;
                if (scanCallback == capturedScan) stopScan();
                if (capturedConnection != null && gatt == capturedConnection) disconnect("FOREGROUND_SUSPENDED");
            });
            return;
        }
        stopScan();
        if (gatt != null) disconnect("FOREGROUND_SUSPENDED");
    }

    List<BluetoothGattService> services() {
        return isReady() ? gatt.getServices() : Collections.emptyList();
    }

    void selectWrite(BluetoothGattCharacteristic characteristic) {
        if (!isReady() || !owns(characteristic)) { error("STALE_CHARACTERISTIC"); return; }
        if ((characteristic.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE
                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) { error("WRITE_TYPE_UNSUPPORTED"); return; }
        stopRepeat();
        writeTarget = characteristic;
        record("TX_TARGET " + key(characteristic), null);
        if (!pluginMode) getApplication().getSharedPreferences("ble_endpoints", Context.MODE_PRIVATE).edit()
                .putString(address(), key(characteristic)).apply();
    }

    boolean send(byte[] payload, boolean noResponse, boolean split, int interval) {
        if (!isReady() || writeTarget == null) { error("SELECT_WRITE_CHARACTERISTIC"); return false; }
        int property = noResponse ? BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE : BluetoothGattCharacteristic.PROPERTY_WRITE;
        if ((writeTarget.getProperties() & property) == 0) { error("WRITE_TYPE_UNSUPPORTED"); return false; }
        if (payload == null || payload.length == 0 || payload.length > BlePayload.MAX_SEND_BYTES) { error("INVALID_PAYLOAD_SIZE"); return false; }
        if (interval != 0 && (interval < 100 || interval > 60000)) { error("REPEAT_INTERVAL_100_60000"); return false; }
        if (!split && payload.length > BlePayload.chunkSize(mtu)) { error("EXCEEDS_MTU: enable splitting"); return false; }
        Request request = new Request(Kind.WRITE, writeTarget, null, payload.clone(),
                noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        request.split = split;
        if (interval > 0) {
            stopRepeat();
            repeat = request.copy();
            repeatInterval = interval;
            request.repeated = true;
            request.repeatGeneration = repeatGeneration;
        }
        boolean admitted = enqueue(request);
        if (!admitted && interval > 0) stopRepeat();
        return admitted;
    }

    void stopRepeat() { repeatGeneration++; repeat = null; main.removeCallbacks(repeatNext); changed(); }
    void read(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null || (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) { error("READ_UNSUPPORTED"); return; }
        enqueue(new Request(Kind.READ, characteristic, null, null, 0));
    }
    void read(BluetoothGattDescriptor descriptor) {
        if (descriptor == null || descriptor.getCharacteristic() == null) { error("INVALID_DESCRIPTOR"); return; }
        enqueue(new Request(Kind.DESCRIPTOR_READ, descriptor.getCharacteristic(), descriptor, null, 0));
    }
    void readRssi() { enqueue(new Request(Kind.RSSI, null, null, null, 0)); }
    void priority(int value) {
        if (!isReady()) return;
        int[] priorities = {BluetoothGatt.CONNECTION_PRIORITY_BALANCED, BluetoothGatt.CONNECTION_PRIORITY_HIGH,
                BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER};
        if (value < 0 || value >= priorities.length) return;
        try { record("CONNECTION_PRIORITY_REQUEST " + value + " accepted=" + gatt.requestConnectionPriority(priorities[value]), null); }
        catch (RuntimeException failure) { error("PRIORITY_REQUEST_FAILED"); }
    }
    void requestMtu(int value) {
        if (value < 23 || value > 517) { error("MTU_RANGE_23_517"); return; }
        enqueue(new Request(Kind.MTU, null, null, null, value));
    }

    void subscribe(BluetoothGattCharacteristic characteristic, int mode) {
        // mode: 0 disabled, 1 notification, 2 indication. Prefer explicit user choice.
        if (characteristic == null || mode < 0 || mode > 2) { error("INVALID_SUBSCRIPTION"); return; }
        int property = mode == 2 ? BluetoothGattCharacteristic.PROPERTY_INDICATE : BluetoothGattCharacteristic.PROPERTY_NOTIFY;
        if (mode != 0 && (characteristic.getProperties() & property) == 0) { error("SUBSCRIPTION_UNSUPPORTED"); return; }
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) { error("CCCD_MISSING"); return; }
        byte[] value = mode == 0 ? BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                : mode == 2 ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        enqueue(new Request(Kind.SUBSCRIBE, characteristic, descriptor, value.clone(), mode));
    }

    boolean autoUart() {
        for (BluetoothGattService service : services()) {
            String uuid = service.getUuid().toString();
            BluetoothGattCharacteristic tx = null, rx = null;
            if (uuid.equals("6e400001-b5a3-f393-e0a9-e50e24dcca9e")) {
                tx = service.getCharacteristic(BlePayload.uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e"));
                rx = service.getCharacteristic(BlePayload.uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"));
            } else if (uuid.equals(BlePayload.uuid("ffe0").toString())) {
                tx = rx = service.getCharacteristic(BlePayload.uuid("ffe1"));
            }
            if (tx != null && rx != null && (tx.getProperties() & (8 | 4)) != 0 && (rx.getProperties() & (16 | 32)) != 0) {
                selectWrite(tx);
                subscribe(rx, (rx.getProperties() & 16) != 0 ? 1 : 2);
                return true;
            }
        }
        error("UART_NOT_FOUND: select characteristics manually");
        return false;
    }

    long submitPlugin(long requestId, String kind, BluetoothGattCharacteristic characteristic, byte[] bytes, int argument, boolean split) {
        Kind type = Kind.valueOf(kind);
        if (type == Kind.WRITE && (characteristic == null || bytes == null || bytes.length == 0 || bytes.length > 65536)) {
            error("INVALID_ARGUMENT"); return 0;
        }
        if (characteristic != null) {
            int property = type == Kind.READ ? BluetoothGattCharacteristic.PROPERTY_READ
                    : type == Kind.WRITE ? (argument == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ? 4 : 8)
                    : argument == 2 ? 32 : 16;
            if ((characteristic.getProperties() & property) == 0 && !(type == Kind.SUBSCRIBE && argument == 0)) {
                error("PROPERTY_UNSUPPORTED"); return 0;
            }
        }
        BluetoothGattDescriptor descriptor = type == Kind.SUBSCRIBE ? characteristic.getDescriptor(CCCD) : null;
        if (type == Kind.SUBSCRIBE && descriptor == null) { error("CCCD_MISSING"); return 0; }
        byte[] payload = type == Kind.SUBSCRIBE ? (argument == 0 ? BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                : argument == 2 ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) : bytes;
        Request request = new Request(type, characteristic, descriptor, payload, argument);
        request.id = requestId; request.split = split;
        observed.put(request.id, request);
        if (!enqueue(request)) { observed.remove(request.id); return 0; }
        return request.id;
    }

    private boolean enqueue(Request request) {
        if (!isReady()) { error("NOT_CONNECTED"); return false; }
        if (!permissionGate.allowed()) { disconnect("PERMISSION_REVOKED"); return false; }
        if (request.characteristic != null && !owns(request.characteristic)) { error("STALE_CHARACTERISTIC"); return false; }
        if (request.descriptor != null && !owns(request.descriptor)) { error("STALE_DESCRIPTOR"); return false; }
        if (!queue.offer(request, 128 + request.payload.length)) { error("QUEUE_FULL"); return false; }
        record("QUEUED " + request.kind + " " + key(request.characteristic), null);
        pump();
        return true;
    }

    private void pump() {
        if (!isReady()) return;
        Request next = queue.startNext();
        if (next == null) return;
        if (next.repeated && next.repeatGeneration != repeatGeneration) {
            record("CANCELED_REPEAT", null);
            queue.finish();
            main.post(this::pump);
        } else start(next);
    }

    private boolean owns(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return false;
        for (BluetoothGattService service : services()) {
            for (BluetoothGattCharacteristic current : service.getCharacteristics()) if (current == characteristic) return true;
        }
        return false;
    }

    private boolean owns(BluetoothGattDescriptor descriptor) {
        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        if (!owns(characteristic)) return false;
        for (BluetoothGattDescriptor current : characteristic.getDescriptors()) if (current == descriptor) return true;
        return false;
    }

    @SuppressWarnings("deprecation")
    private void start(Request request) {
        if (!isReady()) { disconnect("FOREGROUND_SUSPENDED"); return; }
        if (!permissionGate.allowed()) { disconnect("PERMISSION_REVOKED"); return; }
        procedureGeneration++;
        procedureInFlight = true;
        armTimeout(operationTimeoutMillis);
        boolean started;
        try {
            switch (request.kind) {
                case READ: started = gatt.readCharacteristic(request.characteristic); break;
                case DESCRIPTOR_READ: started = gatt.readDescriptor(request.descriptor); break;
                case RSSI: started = gatt.readRemoteRssi(); break;
                case MTU:
                    if (Build.VERSION.SDK_INT >= 34 && mtuRequested) { error("MTU_ALREADY_REQUESTED: Android 14+ negotiates once"); complete(-1); return; }
                    started = gatt.requestMtu(request.argument);
                    if (started) mtuRequested = true;
                    break;
                case SUBSCRIBE:
                    // Enable local delivery first. For disable, keep delivery alive until
                    // the remote CCCD acknowledges, so failure cannot lose live packets.
                    if (request.argument != 0) {
                        request.localNotificationBefore = subscribed.contains(request.characteristic);
                        if (!gatt.setCharacteristicNotification(request.characteristic, true)) { complete(-1); return; }
                        request.localNotificationChanged = true;
                    }
                    int descriptorStatus = gatt.writeDescriptor(request.descriptor, request.payload);
                    if (descriptorStatus != 0) { startRejected(descriptorStatus); return; }
                    started = true;
                    break;
                case WRITE:
                    if (!request.split && request.payload.length > BlePayload.chunkSize(mtu)) {
                        error("EXCEEDS_MTU: enable splitting"); complete(-1); return;
                    }
                    byte[] chunk = BlePayload.chunk(request.payload, request.offset, mtu);
                    request.chunkLength = chunk.length;
                    int writeStatus = gatt.writeCharacteristic(request.characteristic, chunk, request.argument);
                    if (writeStatus != 0) { startRejected(writeStatus); return; }
                    started = true;
                    break;
                default: started = false;
            }
            if (!started) complete(-1);
        } catch (SecurityException denied) { disconnect("PERMISSION_REVOKED"); }
        catch (RuntimeException failure) { disconnect("GATT_START_FAILED " + failure.getClass().getSimpleName() + ": outcome unknown"); }
    }

    // SDK integer constants are inlined; the pre-33 adapter only returns 0/-1.
    @SuppressLint("InlinedApi")
    private void startRejected(int status) {
        if (status == BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION) {
            disconnect("PERMISSION_REVOKED status=" + status);
        } else if (status == BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED
                || status == BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND) {
            disconnect("GATT_UNAVAILABLE status=" + status);
        } else complete(status);
    }

    private void complete(int status) {
        Request request = queue.current();
        if (request == null) return;
        cancelTimeout();
        procedureInFlight = false;
        procedureGeneration++;
        if (request.kind == Kind.SUBSCRIBE) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (request.argument == 0) {
                    if (!setLocalNotification(request.characteristic, false)) { disconnect("NOTIFICATION_DISABLE_FAILED"); return; }
                    subscribed.remove(request.characteristic);
                } else subscribed.add(request.characteristic);
            } else if (request.localNotificationChanged) {
                if (!setLocalNotification(request.characteristic, request.localNotificationBefore)) {
                    disconnect("NOTIFICATION_ROLLBACK_FAILED"); return;
                }
            }
        }
        if (status == BluetoothGatt.GATT_SUCCESS && request.kind == Kind.WRITE) {
            request.offset += request.chunkLength;
            txBytes += request.chunkLength;
            if (request.offset < request.payload.length) {
                if (request.repeated && request.repeatGeneration != repeatGeneration) {
                    record("CANCELED_REPEAT completedBytes=" + request.offset, null);
                    queue.finish();
                    main.post(this::pump);
                    return;
                }
                BleGattConnection source = gatt;
                long generation = procedureGeneration;
                main.post(() -> {
                    if (gatt != source || generation != procedureGeneration || queue.current() != request) return;
                    if (request.repeated && request.repeatGeneration != repeatGeneration) {
                        record("CANCELED_REPEAT completedBytes=" + request.offset, null);
                        queue.finish();
                        pump();
                    } else start(request);
                });
                return;
            }
        }
        String result = status == 0 ? (request.kind == Kind.WRITE && request.argument == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ? "SENT_UNCONFIRMED" : "OK") : "FAILED status=" + status + " completedBytes=" + request.offset;
        record(result + " " + request.kind + " " + key(request.characteristic),
                request.kind == Kind.WRITE ? request.payload : null);
        if (request.id != 0) {
            observed.remove(request.id);
            if (observer != null) observer.onResult(request.id, request.kind == Kind.WRITE && request.argument == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ? "WRITE_NO_RESPONSE" : request.kind.name(), status,
                    status == 0 ? "" : "OPERATION_FAILED", request.result, request.offset);
        }
        queue.finish();
        if (request.repeated && repeat != null && request.repeatGeneration == repeatGeneration) {
            if (status == 0) main.postDelayed(repeatNext, repeatInterval); else stopRepeat();
        }
        main.post(this::pump);
    }

    private boolean setLocalNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        try { return gatt != null && gatt.setCharacteristicNotification(characteristic, enabled); }
        catch (RuntimeException failure) { return false; }
    }

    private void dispatch(BleGattConnection source, Runnable action) {
        if (cleared) return;
        main.post(() -> {
            if (cleared || source != gatt) return;
            if (!foreground()) { suspend(); return; }
            action.run();
        });
    }

    private void dispatchProcedure(BleGattConnection source, Runnable action) {
        if (!procedureInFlight) return;
        long generation = procedureGeneration;
        dispatch(source, () -> { if (procedureInFlight && generation == procedureGeneration) action.run(); });
    }

    private boolean expected(Kind kind, BluetoothGattCharacteristic characteristic) {
        Request request = queue.current();
        return request != null && request.kind == kind && request.characteristic == characteristic;
    }

    private void receive(BleGattConnection source, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (value == null || cleared || source != gatt) return;
        if (pendingRx.incrementAndGet() > 256) {
            pendingRx.decrementAndGet(); droppedRx.incrementAndGet();
            if (observer != null) main.post(() -> { if (source == gatt) observer.onOverflow(); });
            return;
        }
        byte[] copy = value.clone();
        main.post(() -> {
            try {
                if (source == gatt && foreground() && owns(characteristic)) {
                    rxBytes += copy.length;
                    record("NOTIFY " + key(characteristic), copy);
                    if (observer != null) observer.onData(characteristic, copy.clone());
                }
            } finally { pendingRx.decrementAndGet(); }
        });
    }

    private final BleGattConnection.Events callback = new BleGattConnection.Events() {
        @Override public void connectionState(BleGattConnection source, int status, int newState) {
            dispatch(source, () -> {
                if (status != 0 || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnect("DISCONNECTED status=" + status); return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && connecting) {
                    connecting = false;
                    state = R.string.ble_discovering;
                    procedureGeneration++;
                    armTimeout(operationTimeoutMillis);
                    try { if (!source.discoverServices()) disconnect("DISCOVERY_START_FAILED"); }
                    catch (RuntimeException failure) { disconnect("DISCOVERY_FAILED"); }
                    changed();
                }
            });
        }
        @Override public void servicesDiscovered(BleGattConnection source, int status) {
            dispatch(source, () -> {
                if (state != R.string.ble_discovering) return;
                cancelTimeout();
                if (status != 0) { disconnect("DISCOVERY_FAILED status=" + status); return; }
                ready = true;
                state = R.string.ble_connected;
                String saved = pluginMode ? "" : getApplication().getSharedPreferences("ble_endpoints", Context.MODE_PRIVATE).getString(address(), "");
                for (BluetoothGattService service : services()) for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                    if (saved.equals(key(characteristic)) && (characteristic.getProperties() & (8 | 4)) != 0) writeTarget = characteristic;
                }
                record("SERVICES " + services().size(), null);
            });
        }
        @Override public void characteristicRead(BleGattConnection source, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            byte[] copy = value == null ? new byte[0] : value.clone();
            dispatchProcedure(source, () -> {
                if (!expected(Kind.READ, characteristic)) return;
                if (status == 0) { queue.current().result = copy; rxBytes += copy.length; record("READ " + key(characteristic), copy); }
                complete(status);
            });
        }
        @Override public void notification(BleGattConnection source, BluetoothGattCharacteristic characteristic, byte[] value) { receive(source, characteristic, value); }
        @Override public void characteristicWritten(BleGattConnection source, BluetoothGattCharacteristic characteristic, int status) {
            dispatchProcedure(source, () -> { if (expected(Kind.WRITE, characteristic)) complete(status); });
        }
        @Override public void descriptorWritten(BleGattConnection source, BluetoothGattDescriptor descriptor, int status) {
            dispatchProcedure(source, () -> {
                Request request = queue.current();
                if (request != null && request.kind == Kind.SUBSCRIBE && request.descriptor == descriptor) complete(status);
            });
        }
        @Override public void descriptorRead(BleGattConnection source, BluetoothGattDescriptor descriptor, byte[] value, int status) {
            byte[] copy = value == null ? new byte[0] : value.clone();
            dispatchProcedure(source, () -> {
                Request request = queue.current();
                if (request != null && request.kind == Kind.DESCRIPTOR_READ && request.descriptor == descriptor) {
                    if (status == 0) record("DESCRIPTOR " + key(descriptor.getCharacteristic()) + "/" + descriptor.getUuid(), copy);
                    complete(status);
                }
            });
        }
        @Override public void mtuChanged(BleGattConnection source, int value, int status) {
            long generation = procedureGeneration;
            dispatch(source, () -> {
                // MTU changes are connection state even when another ATT procedure
                // has advanced since this unsolicited callback was delivered.
                if (status == 0 && value >= 23 && value <= 517) {
                    mtu = value;
                    if (Build.VERSION.SDK_INT >= 34) mtuRequested = true;
                }
                record("MTU " + mtu + " status=" + status, null);
                if (procedureInFlight && generation == procedureGeneration && expected(Kind.MTU, null)) complete(status);
            });
        }
        @Override public void rssiRead(BleGattConnection source, int value, int status) {
            dispatchProcedure(source, () -> {
                if (!expected(Kind.RSSI, null)) return;
                if (status == 0) rssi = value;
                record("RSSI " + value + " dBm status=" + status, null);
                complete(status);
            });
        }
        @Override public void servicesChanged(BleGattConnection source) {
            dispatch(source, () -> disconnect("SERVICES_CHANGED: reconnect to rediscover"));
        }
    };

    static String key(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return "";
        BluetoothGattService service = characteristic.getService();
        return service.getUuid() + "#" + service.getInstanceId() + "/" + characteristic.getUuid() + "#" + characteristic.getInstanceId();
    }

    void error(String message) { lastError = message; record("ERROR " + message, null); }
    private void record(String message, byte[] payload) {
        logs.add(message + (target.isEmpty() ? "" : " [" + target + "; session=" + sessionId + "]"), payload);
        changed();
    }
    void changed() {
        if (!cleared && observer != null) observer.onState(isReady(), target, lastError);
        if (cleared || updatePosted) return;
        updatePosted = true;
        main.postDelayed(() -> { updatePosted = false; if (listener != null) listener.onUpdated(); }, 100L);
    }

    @Override protected void onCleared() {
        cleared = true;
        listener = null;
        stopScan();
        disconnect(null);
        main.removeCallbacksAndMessages(null);
        BleRuntime.unregister(this);
    }

    private enum Kind { READ, WRITE, SUBSCRIBE, DESCRIPTOR_READ, MTU, RSSI }
    private static final class Request {
        long id;
        byte[] result = new byte[0];
        final Kind kind;
        final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor;
        final byte[] payload;
        final int argument;
        int offset, chunkLength;
        boolean repeated;
        boolean split;
        boolean localNotificationBefore;
        boolean localNotificationChanged;
        long repeatGeneration;
        Request(Kind kind, BluetoothGattCharacteristic characteristic, BluetoothGattDescriptor descriptor, byte[] payload, int argument) {
            this.kind = kind; this.characteristic = characteristic; this.descriptor = descriptor;
            this.payload = payload == null ? new byte[0] : payload; this.argument = argument;
        }
        Request copy() {
            Request copy = new Request(kind, characteristic, descriptor, payload, argument);
            copy.split = split;
            return copy;
        }
    }
}
