package com.zzf.bluetoothsmp.ble;

import android.annotation.SuppressLint;
import android.app.Application;
import android.bluetooth.*;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanRecord;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Base64;
import org.json.*;
import java.util.*;
import com.zzf.bluetoothsmp.plugin.PluginTransport;
import static com.zzf.bluetoothsmp.plugin.PluginJson.object;

/** Structured adapter over the very same controller used by the native BLE screen. Main-thread only. */
@SuppressLint("MissingPermission")
public final class PluginBleTransport implements PluginTransport, BleController.Observer {
    private final BleController controller;
    private final Listener listener;
    private final Map<String, BluetoothGattCharacteristic> handles = new HashMap<>();
    private final Map<Long, Reply> pending = new HashMap<>();
    private long nextRequest;
    private Reply connecting;
    private String session = "";
    private boolean closing;
    private String prefix = "";

    public PluginBleTransport(Application application, Listener listener) {
        this.listener = listener;
        controller = new BleController(application);
        controller.pluginMode = true;
        controller.observer = this;
    }
    PluginBleTransport(BleController controller, Listener listener) {
        this.listener = listener; this.controller = controller;
        controller.pluginMode = true; controller.observer = this;
    }
    @Override public void execute(String action, JSONObject params, Reply reply) {
        try {
            switch (action) {
                case "scan":
                    prefix = params.optString("namePrefix", "");
                    controller.scan();
                    if (!controller.isScanning()) reply.error("SCAN_FAILED", controller.lastError);
                    else reply.success(object("scanning", true));
                    return;
                case "stopScan": stopScan(); reply.success(null); return;
                case "connect":
                    if (busy()) { reply.error("DEVICE_BUSY", "Disconnect the current device first"); return; }
                    connecting = reply; session = UUID.randomUUID().toString(); handles.clear();
                    controller.lastError = "";
                    controller.connect(params.getString("address"));
                    if (!controller.hasConnection() && connecting != null) {
                        connecting.error(controller.lastError.contains("DEVICE_BUSY") ? "DEVICE_BUSY" : "CONNECT_FAILED", controller.lastError);
                        connecting = null; session = "";
                    }
                    return;
                case "disconnect": disconnect(); reply.success(null); return;
                case "services":
                    if (!controller.isReady() || !session.equals(params.optString("sessionId"))) { reply.error("SESSION_EXPIRED", "The BLE session has ended"); return; }
                    reply.success(serviceTree()); return;
                default: break;
            }
            if (!controller.isReady() || !session.equals(params.optString("sessionId"))) {
                reply.error("SESSION_EXPIRED", "The BLE session has ended"); return;
            }
            BluetoothGattCharacteristic target = null;
            String kind; byte[] bytes = null; int argument = 0; boolean split = false;
            if (action.equals("read") || action.equals("write") || action.equals("subscribe")) {
                target = handles.get(params.optString("characteristic"));
                if (target == null) { reply.error("STALE_CHARACTERISTIC", "Use a characteristic from this session"); return; }
            }
            switch (action) {
                case "read": kind = "READ"; break;
                case "write":
                    kind = "WRITE"; bytes = Base64.decode(params.getString("bytes"), Base64.NO_WRAP);
                    argument = params.optBoolean("withoutResponse") ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                    split = params.optBoolean("split", false); break;
                case "subscribe":
                    kind = "SUBSCRIBE"; argument = params.optInt("mode", 1);
                    if (argument < 0 || argument > 2) throw new IllegalArgumentException();
                    break;
                case "requestMtu":
                    kind = "MTU"; argument = params.getInt("mtu");
                    if (argument < 23 || argument > 517) throw new IllegalArgumentException();
                    break;
                case "readRssi": kind = "RSSI"; break;
                default: reply.error("METHOD_NOT_FOUND", "Unknown BLE operation"); return;
            }
            long id = ++nextRequest; pending.put(id, reply);
            if (controller.submitPlugin(id, kind, target, bytes, argument, split) == 0) {
                pending.remove(id); reply.error(controller.lastError.contains("QUEUE_FULL") ? "QUEUE_FULL" : "INVALID_ARGUMENT", controller.lastError);
            }
        } catch (Exception error) { reply.error("INVALID_ARGUMENT", "Invalid BLE parameters"); }
    }
    private JSONArray serviceTree() throws JSONException {
        JSONArray services = new JSONArray();
        for (BluetoothGattService service : controller.services()) {
            JSONArray characteristics = new JSONArray();
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                String handle = null;
                for (Map.Entry<String, BluetoothGattCharacteristic> item : handles.entrySet()) if (item.getValue() == characteristic) handle = item.getKey();
                if (handle == null) { handle = UUID.randomUUID().toString(); handles.put(handle, characteristic); }
                JSONArray descriptors = new JSONArray();
                for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) descriptors.put(descriptor.getUuid().toString());
                characteristics.put(object("handle", handle, "uuid", characteristic.getUuid().toString(), "instanceId", characteristic.getInstanceId(),
                        "properties", characteristic.getProperties(), "descriptors", descriptors));
            }
            services.put(object("uuid", service.getUuid().toString(), "instanceId", service.getInstanceId(), "characteristics", characteristics));
        }
        return services;
    }
    @Override public JSONObject snapshot() {
        return object("transport", "ble", "sessionId", session, "address", controller.address(), "connected", controller.isReady(),
                "connecting", connecting != null, "mtu", controller.mtu, "rssi", controller.rssi);
    }
    @Override public boolean busy() { return controller.hasConnection() || connecting != null; }
    @Override public void stopScan() { controller.stopScan(); }
    @Override public void disconnect() {
        closing = true;
        if (connecting != null) { Reply reply = connecting; connecting = null; reply.error("SESSION_ENDED", "Connection canceled"); }
        controller.disconnect("USER_DISCONNECTED"); handles.clear(); session = "";
        closing = false;
    }
    @Override public void close() { disconnect(); controller.observer = null; controller.closeController(); }
    @Override public void onData(BluetoothGattCharacteristic characteristic, byte[] bytes) {
        String handle = null;
        for (Map.Entry<String, BluetoothGattCharacteristic> item : handles.entrySet()) if (item.getValue() == characteristic) handle = item.getKey();
        if (handle != null) listener.event("data", object("transport", "ble", "sessionId", session, "characteristic", handle,
                "bytes", Base64.encodeToString(bytes, Base64.NO_WRAP)));
    }
    @Override public void onResult(long requestId, String kind, int status, String error, byte[] bytes, int completedBytes) {
        Reply reply = pending.remove(requestId);
        if (reply == null) return;
        if (status == 0) reply.success(object("status", kind.equals("WRITE_NO_RESPONSE") ? "SENT_UNCONFIRMED" : kind.equals("WRITE") ? "SENT" : "OK", "completedBytes", completedBytes,
                "bytes", Base64.encodeToString(bytes, Base64.NO_WRAP), "mtu", controller.mtu, "rssi", controller.rssi));
        else reply.error(error, "BLE operation failed (status=" + status + ", completedBytes=" + completedBytes + ")");
    }
    @Override public void onScan(ScanResult result) {
        String name = result.getDevice().getName(); ScanRecord record = result.getScanRecord();
        if (record != null && record.getDeviceName() != null) name = record.getDeviceName();
        if (!prefix.isEmpty() && (name == null || !name.startsWith(prefix))) return;
        JSONArray services = new JSONArray(); JSONObject manufacturer = new JSONObject(), serviceData = new JSONObject();
        try {
            if (record != null) {
                if (record.getServiceUuids() != null) for (ParcelUuid uuid : record.getServiceUuids()) services.put(uuid.toString());
                android.util.SparseArray<byte[]> values = record.getManufacturerSpecificData();
                for (int i = 0; i < values.size(); i++) manufacturer.put(Integer.toString(values.keyAt(i)), Base64.encodeToString(values.valueAt(i), Base64.NO_WRAP));
                for (Map.Entry<ParcelUuid, byte[]> item : record.getServiceData().entrySet()) serviceData.put(item.getKey().toString(), Base64.encodeToString(item.getValue(), Base64.NO_WRAP));
            }
        } catch (JSONException ignored) { }
        listener.event("device", object("transport", "ble", "address", result.getDevice().getAddress(), "name", name,
                "rssi", result.getRssi(), "connectable", Build.VERSION.SDK_INT < 26 || result.isConnectable(), "serviceUuids", services,
                "manufacturerData", manufacturer, "serviceData", serviceData,
                "advertisement", record == null ? "" : Base64.encodeToString(record.getBytes(), Base64.NO_WRAP)));
    }
    @Override public void onState(boolean ready, String address, String error) {
        if (connecting != null && ready) {
            Reply reply = connecting; connecting = null;
            reply.success(snapshot());
        } else if (connecting != null && !error.isEmpty()) {
            Reply reply = connecting; connecting = null;
            reply.error(error.contains("DEVICE_BUSY") ? "DEVICE_BUSY" : "CONNECT_FAILED", error);
        }
        if (!closing) listener.event("state", snapshot());
    }
    @Override public void onOverflow() { listener.event("dataLoss", object("transport", "ble", "sessionId", session)); disconnect(); }
}
