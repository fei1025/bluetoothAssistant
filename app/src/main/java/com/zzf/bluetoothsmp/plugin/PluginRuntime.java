package com.zzf.bluetoothsmp.plugin;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import androidx.lifecycle.AndroidViewModel;
import androidx.webkit.JavaScriptReplyProxy;
import com.zzf.bluetoothsmp.*;
import com.zzf.bluetoothsmp.ble.PluginBleTransport;
import org.json.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.zzf.bluetoothsmp.plugin.PluginJson.object;

/** One foreground runtime. Native ownership survives configuration changes, never process death. */
public final class PluginRuntime extends AndroidViewModel implements PluginTransport.Listener {
    public interface Failure { void accept(Exception error); }
    public interface Consent { void result(boolean allowed); }
    public interface Ui {
        void capabilities(PluginStore.Installed plugin, Set<String> capabilities, Consent result);
        void device(PluginStore.Installed plugin, String transport, String address, Consent result);
        void status(String text);
    }
    private static WeakReference<PluginRuntime> active = new WeakReference<>(null);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), runnable -> new Thread(runnable, "plugin-io"));
    private final PluginStore store;
    private final PluginEventBuffer events = new PluginEventBuffer();
    private final Set<String> requests = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> knownDevices = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicBoolean overflowPosted = new AtomicBoolean();
    private final Set<String> grants = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Long> requestDeadlines = new HashMap<>();
    private final Map<String, String> requestMethods = new HashMap<>();
    private PluginClassicTransport classic;
    private PluginBleTransport ble;
    private volatile PluginStore.Installed installed;
    private volatile boolean ended;
    private volatile boolean ready;
    private String contextId = "";
    private final String runId = UUID.randomUUID().toString();
    private JavaScriptReplyProxy proxy;
    private Ui ui;
    private boolean consentPending;
    private boolean suspended;
    private volatile long authorizationEpoch;
    private final Object authorizationLock = new Object();
    private long nextDelivery;
    private long waitingDelivery;
    private int waitingCount;
    private long waitingSince;
    private WebView webView;
    private MutableContextWrapper webContext;
    private final Runnable flushTask = new Runnable() {
        @Override public void run() {
            if (ended) return;
            if (!StaticObject.foregroundGate.isActive() && ready) suspend();
            if (suspended) { main.postDelayed(this, 100); return; }
            flush();
            long now = android.os.SystemClock.elapsedRealtime();
            if (waitingDelivery != 0 && now - waitingSince > 10000) stopUnresponsive();
            for (Map.Entry<String, Long> request : new ArrayList<>(requestDeadlines.entrySet())) {
                if (now > request.getValue()) {
                    String[] parts = request.getKey().split(":", 2);
                    finish(parts[0], parts[1], null, "OUTCOME_UNKNOWN", "Operation timed out; it will not be replayed");
                    disconnectTransports();
                }
            }
            main.postDelayed(this, 100);
        }
    };
    public PluginRuntime(Application application) { super(application); store = new PluginStore(application); }
    public static synchronized boolean isRunning(String id) {
        PluginRuntime runtime = active.get();
        return runtime != null && !runtime.ended && runtime.installed != null && runtime.installed.manifest.id.equals(id);
    }
    public static void suspendAll() {
        PluginRuntime runtime;
        synchronized (PluginRuntime.class) { runtime = active.get(); }
        if (runtime != null) runtime.main.post(runtime::suspend);
    }
    public static void enforceRevocations(Context context) {
        PluginRuntime runtime;
        synchronized (PluginRuntime.class) { runtime = active.get(); }
        if (runtime != null) runtime.main.post(() -> {
            if (runtime.installed != null && new PluginStore(context).isRevoked(runtime.installed.digest)) runtime.stop();
        });
    }
    public void open(String id, Runnable onReady, Failure failure) {
        if (ended) { failure.accept(new PluginException("PLUGIN_STOPPED", "This plugin has stopped")); return; }
        if (installed != null) { onReady.run(); return; }
        io.execute(() -> {
            try {
                PluginStore.Installed plugin = store.load(id);
                Set<String> allowed = store.grants(plugin);
                main.post(() -> {
                    if (ended) return;
                    synchronized (PluginRuntime.class) {
                        PluginRuntime other = active.get();
                        if (other != null && other != this && !other.ended) {
                            failure.accept(new PluginException("PLUGIN_RUNNING", "Stop the currently running plugin first")); return;
                        }
                        installed = plugin; active = new WeakReference<>(this);
                    }
                    grants.addAll(allowed);
                    classic = new PluginClassicTransport(getApplication(), this);
                    ble = new PluginBleTransport(getApplication(), this);
                    main.post(flushTask);
                    onReady.run();
                });
            } catch (Exception error) { main.post(() -> { if (!ended) failure.accept(error); }); }
        });
    }
    public PluginStore.Installed plugin() { return installed; }
    public void attach(Ui ui, Context activity) { this.ui = ui; if (webContext != null) webContext.setBaseContext(activity); }
    public void detach() { ui = null; if (webContext != null) webContext.setBaseContext(getApplication()); }
    public WebView view(Context activity) throws Exception {
        if (webView == null) {
            webContext = new MutableContextWrapper(activity);
            webView = PluginWebView.create(webContext, this, installed);
        } else webContext.setBaseContext(activity);
        return webView;
    }
    public void revoke() {
        synchronized (authorizationLock) {
            authorizationEpoch++; grants.clear();
            // Make revocation visible before another runtime can open; serialize
            // against a grant that is already being persisted on the IO worker.
            if (installed != null) store.revokePermissions(installed);
        }
        disconnectTransports();
        rejectBluetoothRequests("PERMISSION_DENIED", "Plugin authorization was revoked");
    }
    public void stop() {
        if (ended) return;
        authorizationEpoch++; suspend(); ended = true; ready = false; proxy = null;
        main.removeCallbacksAndMessages(null); events.clear(); requests.clear(); requestDeadlines.clear(); requestMethods.clear();
        if (classic != null) classic.close();
        if (ble != null) ble.close();
        if (webView != null) {
            android.view.ViewParent parent = webView.getParent();
            if (parent instanceof android.view.ViewGroup) ((android.view.ViewGroup) parent).removeView(webView);
            webView.stopLoading(); webView.destroy(); webView = null;
        }
        synchronized (PluginRuntime.class) { if (active.get() == this) active.clear(); }
    }
    public void suspend() {
        if (ended || suspended) return;
        suspended = true;
        authorizationEpoch++;
        disconnectTransports();
        rejectBluetoothRequests("SESSION_ENDED", "Plugin foreground session ended");
        if (webView != null) { webView.onPause(); webView.pauseTimers(); }
    }
    private void rejectBluetoothRequests(String code, String message) {
        for (String key : new ArrayList<>(requests)) {
            String method = requestMethods.get(key);
            if (method != null && (method.startsWith("classic.") || method.startsWith("ble."))) {
                String[] parts = key.split(":", 2);
                finish(parts[0], parts[1], null, code, message);
            }
        }
    }
    private void disconnectTransports() {
        if (classic != null) classic.disconnect();
        if (ble != null) ble.disconnect();
    }
    public void resume() { if (!ended) { suspended = false; waitingSince = android.os.SystemClock.elapsedRealtime(); if (webView != null) { webView.onResume(); webView.resumeTimers(); } } }
    public void stopUnresponsive() { if (ui != null) ui.status(getApplication().getString(R.string.plugin_unresponsive)); stop(); }
    public void message(JSONObject message, JavaScriptReplyProxy source) {
        if (ended || installed == null) return;
        String type = message.optString("type");
        if (type.equals("ack")) {
            if (!contextId.equals(message.optString("contextId")) || !runId.equals(message.optString("runId"))) return;
            if (message.optLong("deliveryId", -1) == waitingDelivery && waitingDelivery != 0) {
                events.drain(waitingCount); waitingDelivery = 0; waitingCount = 0;
            }
            return;
        }
        String method = message.optString("method"), id = message.optString("requestId"), ctx = message.optString("contextId");
        if (!id.matches("[A-Za-z0-9-]{1,64}") || !ctx.matches("[A-Za-z0-9-]{1,64}")) return;
        if (method.equals("host.ready")) {
            if (!contextId.equals(ctx)) {
                // A manual reload establishes a fresh JS context, never replays native commands.
                if (ready) { suspend(); requests.clear(); requestDeadlines.clear(); requestMethods.clear(); events.clear(); waitingDelivery = 0; }
                contextId = ctx;
            }
            proxy = source; ready = true;
            if (StaticObject.foregroundGate.isActive()) resume();
        } else if (!contextId.equals(ctx) || !runId.equals(message.optString("runId"))) return;
        String key = ctx + ":" + id;
        if (requests.size() >= 64) {
            enqueue(object("type", "response", "contextId", ctx, "requestId", id, "error",
                    object("code", "QUEUE_FULL", "message", "Plugin request queue is full", "requestId", id)));
            return;
        }
        if (!requests.add(key)) return;
        requestDeadlines.put(key, android.os.SystemClock.elapsedRealtime() + 60000);
        requestMethods.put(key, method);
        PluginTransport.Reply reply = new PluginTransport.Reply() {
            @Override public boolean active() { return !ended && requests.contains(key) && ctx.equals(contextId); }
            @Override public void success(Object value) { onMain(() -> finish(ctx, id, value, null, null)); }
            @Override public void error(String code, String text) { onMain(() -> finish(ctx, id, null, code, text)); }
        };
        JSONObject params = message.optJSONObject("params");
        if (params == null) params = new JSONObject();
        if (method.equals("host.ready") || method.equals("host.getInfo") || method.equals("host.getState")) {
            reply.success(object("sdk", 1, "runId", runId, "pluginId", installed.manifest.id, "language", Locale.getDefault().toLanguageTag(),
                    "capabilities", new JSONArray(installed.manifest.capabilities), "developerMode", BuildConfig.PLUGIN_DEVELOPER_MODE,
                    "classic", classic.snapshot(), "ble", ble.snapshot())); return;
        }
        if (!StaticObject.foregroundGate.isActive()) { reply.error("FOREGROUND_REQUIRED", "Keep the plugin in the foreground"); return; }
        if (store.isRevoked(installed.digest)) { reply.error("VERSION_REVOKED", "This version has been withdrawn"); suspend(); return; }
        if (method.startsWith("storage.")) { storage(method, params, reply); return; }
        String[] parts = method.split("\\.", -1);
        if (parts.length != 2 || (!parts[0].equals("classic") && !parts[0].equals("ble"))) { reply.error("METHOD_NOT_FOUND", "Unknown SDK operation"); return; }
        String transport = parts[0], action = parts[1];
        String capability = capability(transport, action);
        if (capability == null) { reply.error("METHOD_NOT_FOUND", "Unknown SDK operation"); return; }
        JSONObject arguments = params;
        authorize(capability, () -> invoke(transport, action, arguments, reply), reply);
    }
    private String capability(String transport, String action) {
        if (action.equals("scan") || action.equals("stopScan") || transport.equals("classic") && action.equals("paired")) return transport + ".scan";
        if (action.equals("connect") || action.equals("disconnect")) return transport + ".connect";
        if (transport.equals("classic") && action.equals("write")) return "classic.io";
        if (transport.equals("ble") && Arrays.asList("services", "read", "write", "subscribe", "requestMtu", "readRssi").contains(action)) return "ble.gatt";
        return null;
    }
    private void authorize(String capability, Runnable operation, PluginTransport.Reply reply) {
        if (!installed.manifest.capabilities.contains(capability)) { reply.error("CAPABILITY_NOT_DECLARED", "Plugin did not declare " + capability); return; }
        if (!reply.active()) return;
        if (grants.contains(capability)) { operation.run(); return; }
        if (ui == null || consentPending) { reply.error("AUTHORIZATION_BUSY", "Complete the current authorization first"); return; }
        Set<String> missing = new TreeSet<>(installed.manifest.capabilities); missing.removeAll(grants);
        final long epoch = authorizationEpoch;
        consentPending = true;
        ui.capabilities(installed, missing, allowed -> {
            consentPending = false;
            if (!reply.active() || epoch != authorizationEpoch) return;
            if (!allowed) { reply.error("PERMISSION_DENIED", "Plugin authorization denied"); return; }
            Set<String> updated = new HashSet<>(grants); updated.addAll(missing);
            queueIo(() -> {
                boolean saved;
                synchronized (authorizationLock) { saved = reply.active() && epoch == authorizationEpoch && store.grant(installed, updated); }
                main.post(() -> { if (!reply.active() || epoch != authorizationEpoch) return; if (saved) { grants.addAll(updated); operation.run(); } else reply.error("STORAGE_FAILED", "Could not save authorization"); });
            }, reply);
        });
    }
    private void invoke(String transport, String action, JSONObject params, PluginTransport.Reply reply) {
        if (!reply.active()) return;
        if (!StaticObject.foregroundGate.isActive()) { reply.error("FOREGROUND_REQUIRED", "Keep the plugin in the foreground"); return; }
        if (!BluetoothPermissionUtils.hasConnectPermission(getApplication()) || ((action.equals("scan") || action.equals("paired"))
                && !BluetoothPermissionUtils.hasDiscoveryPermissions(getApplication()))) {
            reply.error("PERMISSION_REQUIRED", "Grant Bluetooth permissions in the app settings"); return;
        }
        PluginTransport selected = transport.equals("classic") ? classic : ble;
        PluginTransport other = transport.equals("classic") ? ble : classic;
        if (action.equals("scan")) other.stopScan();
        if (action.equals("connect")) {
            if (other.busy() || selected.busy()) { reply.error("DEVICE_BUSY", "A plugin may connect to one device at a time"); return; }
            String address = params.optString("address").toUpperCase(Locale.ROOT);
            if (!BluetoothAdapter.checkBluetoothAddress(address) || !knownDevices.contains(transport + ":" + address)) {
                reply.error("DEVICE_NOT_SELECTED", "Select a device returned by this plugin's scan or paired-device list"); return;
            }
            if (!store.deviceGranted(installed, transport, address)) {
                if (ui == null || consentPending) { reply.error("AUTHORIZATION_BUSY", "Complete the current authorization first"); return; }
                final long epoch = authorizationEpoch;
                consentPending = true;
                ui.device(installed, transport, address, allowed -> {
                    consentPending = false;
                    if (!reply.active() || epoch != authorizationEpoch) return;
                    if (!allowed) { reply.error("PERMISSION_DENIED", "Device authorization denied"); return; }
                    queueIo(() -> {
                        boolean saved;
                        synchronized (authorizationLock) { saved = reply.active() && epoch == authorizationEpoch && store.grantDevice(installed, transport, address); }
                        main.post(() -> { if (reply.active() && epoch == authorizationEpoch) { if (saved) invoke(transport, action, params, reply); else reply.error("STORAGE_FAILED", "Could not save authorization"); } });
                    }, reply);
                });
                return;
            }
            other.stopScan();
        }
        PluginTransport.Reply wrapped = reply;
        if (action.equals("paired")) wrapped = new PluginTransport.Reply() {
            @Override public void success(Object value) {
                JSONArray devices = (JSONArray) value;
                for (int i = 0; i < devices.length(); i++) rememberDevice("classic", devices.optJSONObject(i).optString("address"));
                reply.success(value);
            }
            @Override public void error(String code, String message) { reply.error(code, message); }
        };
        selected.execute(action, params, wrapped);
    }
    private void storage(String method, JSONObject params, PluginTransport.Reply reply) {
        try {
            io.execute(() -> {
                try {
                    String key = params.optString("key");
                    if (method.equals("storage.get")) reply.success(store.storage(installed).opt(key));
                    else if (method.equals("storage.set") || method.equals("storage.remove")) {
                        store.store(installed, key, params.opt("value"), method.equals("storage.remove")); reply.success(null);
                    } else reply.error("METHOD_NOT_FOUND", "Unknown storage operation");
                } catch (Exception error) { reply.error(error instanceof PluginException ? ((PluginException) error).code : "STORAGE_FAILED", "Plugin storage operation failed"); }
            });
        } catch (RejectedExecutionException full) { reply.error("QUEUE_FULL", "Plugin work queue is full"); }
    }
    private void queueIo(Runnable work, PluginTransport.Reply reply) {
        try { io.execute(work); }
        catch (RejectedExecutionException full) { reply.error("QUEUE_FULL", "Plugin work queue is full"); }
    }
    private void finish(String ctx, String id, Object value, String code, String message) {
        String key = ctx + ":" + id;
        if (ended || !requests.remove(key)) return;
        requestDeadlines.remove(key); String method = requestMethods.remove(key);
        enqueue(object("type", "response", "contextId", ctx, "requestId", id, "result", value,
                "error", code == null ? null : object("code", code, "message", message, "requestId", id)));
        if (method != null && (method.startsWith("classic.") || method.startsWith("ble."))) {
            JSONObject result = object("method", method, "requestId", id, "success", code == null);
            enqueue(object("type", "event", "name", "operationComplete", "value", result));
            if (code != null) enqueue(object("type", "event", "name", "error", "value", object("code", code, "message", message, "requestId", id)));
        }
    }
    @Override public void event(String name, JSONObject value) {
        if (ended || installed == null) return;
        String transport = value.optString("transport");
        if (name.equals("device")) rememberDevice(transport, value.optString("address"));
        if (name.equals("state")) onMain(() -> updateStatus());
        if (name.equals("data") && !grants.contains(transport.equals("classic") ? "classic.io" : "ble.gatt")) return;
        enqueue(object("type", "event", "name", name, "value", value));
    }
    private void rememberDevice(String transport, String address) {
        String key = transport + ":" + address.toUpperCase(Locale.ROOT);
        if (knownDevices.contains(key) || knownDevices.size() < 512) knownDevices.add(key);
    }
    public void updateStatus() {
        if (ui == null || installed == null || ended) return;
        JSONObject state = ble != null && ble.busy() ? ble.snapshot() : classic == null ? new JSONObject() : classic.snapshot();
        String address = state.optString("address");
        ui.status(installed.manifest.name + (state.optBoolean("connected") || state.optBoolean("connecting")
                ? " · " + state.optString("transport").toUpperCase(Locale.ROOT) + " · " + address : ""));
    }
    private void enqueue(JSONObject value) {
        if (ended) return;
        if (!events.offer(value.toString()) && overflowPosted.compareAndSet(false, true)) {
            main.post(() -> {
                if (ended) return;
                disconnectTransports(); events.clear(); waitingDelivery = 0;
                events.offer(object("type", "event", "name", "dataLoss", "value", object("reason", "BRIDGE_OVERFLOW")).toString());
                for (String key : new ArrayList<>(requests)) {
                    String[] parts = key.split(":", 2);
                    finish(parts[0], parts[1], null, "DATA_LOSS", "Plugin event buffer overflow; connection closed");
                }
                overflowPosted.set(false);
            });
        }
    }
    private void flush() {
        if (proxy == null || waitingDelivery != 0 || events.count() == 0 || ended) return;
        List<String> values = events.peek(16); JSONArray batch = new JSONArray();
        try {
            for (String value : values) batch.put(new JSONObject(value));
            waitingCount = values.size(); waitingDelivery = ++nextDelivery; waitingSince = android.os.SystemClock.elapsedRealtime();
            proxy.postMessage(object("type", "batch", "runId", runId, "deliveryId", waitingDelivery, "messages", batch).toString());
        } catch (Exception error) { stopUnresponsive(); }
    }
    private void onMain(Runnable work) { if (Looper.myLooper() == Looper.getMainLooper()) work.run(); else main.post(work); }
    @Override protected void onCleared() { stop(); io.shutdown(); }
}
