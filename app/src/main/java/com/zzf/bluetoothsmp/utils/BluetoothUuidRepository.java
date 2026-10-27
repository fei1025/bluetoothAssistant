package com.zzf.bluetoothsmp.utils;

import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import com.zzf.bluetoothsmp.BluetoothObject;
import com.zzf.bluetoothsmp.entity.SystemInfoMapper;
import org.litepal.LitePal;
import org.litepal.crud.LitePalSupport;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** UUID settings are read and saved off the main thread; transport reads share the cache. */
public final class BluetoothUuidRepository {
    public interface Callback<T> {
        void onSuccess(T value);
        void onError(Throwable error);
    }
    public static final class Config {
        private final String serverUuid, clientUuid;
        private Config(String serverUuid, String clientUuid) {
            this.serverUuid = effectiveUuid(serverUuid);
            this.clientUuid = effectiveUuid(clientUuid);
        }
        public String getServerUuid() { return serverUuid; }
        public String getClientUuid() { return clientUuid; }
    }
    private static final Object IO_LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), task -> {
        Thread thread = new Thread(task, "bluetooth-uuid-settings");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Config cached;
    private static volatile Runnable transactionFaultForTests;
    private BluetoothUuidRepository() { }

    public static void load(Callback<Config> callback) { execute(() -> read(false), callback); }
    public static void updateServer(String uuid, Callback<Config> callback) { enqueueUpdate(uuid, true, callback); }
    public static void updateClient(String uuid, Callback<Config> callback) { enqueueUpdate(uuid, false, callback); }

    private static void enqueueUpdate(String uuid, boolean server, Callback<Config> callback) {
        try {
            if (uuid == null || uuid.length() > 36) throw new IllegalArgumentException("Invalid Bluetooth UUID");
            UUID.fromString(uuid);
            execute(() -> update(uuid, server), callback);
        } catch (IllegalArgumentException error) { fail(callback, error); }
    }

    /** Only background socket workers may use this synchronous, cached accessor. */
    public static Config loadForTransport() {
        requireBackgroundThread();
        return read(true);
    }
    private static Config read(boolean allowCache) {
        requireBackgroundThread();
        synchronized (IO_LOCK) {
            if (allowCache && cached != null) return cached;
            SystemInfoMapper row = LitePal.findFirst(SystemInfoMapper.class);
            Config result = config(row);
            cached = result;
            return result;
        }
    }
    private static Config update(String uuid, boolean server) {
        requireBackgroundThread();
        UUID.fromString(uuid); // Reject malformed settings before any database mutation.
        synchronized (IO_LOCK) {
            Config result;
            // LitePal saves already use this monitor before acquiring a database
            // transaction. Preserve that lock order for the read-modify-write pair.
            synchronized (LitePalSupport.class) {
                SQLiteDatabase db = LitePal.getDatabase();
                db.beginTransaction();
                try {
                    SystemInfoMapper row = LitePal.findFirst(SystemInfoMapper.class);
                    if (row == null) row = new SystemInfoMapper();
                    if (server) row.setServiceSpp(uuid); else row.setClientSpp(uuid);
                    if (!row.save()) throw new IllegalStateException("Unable to save Bluetooth UUID settings");
                    result = config(row);
                    Runnable fault = transactionFaultForTests;
                    if (fault != null) fault.run();
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
            // Publish only after the entire transaction has committed successfully.
            cached = result;
            return result;
        }
    }
    private static Config config(SystemInfoMapper row) {
        return new Config(row == null ? null : row.getServiceSpp(), row == null ? null : row.getClientSpp());
    }
    private static String effectiveUuid(String value) {
        if (value == null || value.isEmpty()) return BluetoothObject.SPP_UUID;
        return value;
    }
    private static void requireBackgroundThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Bluetooth UUID IO requires a background thread");
    }
    private interface Operation { Config run(); }
    private static void execute(Operation operation, Callback<Config> callback) {
        try { IO.execute(() -> {
            try {
                Config value = operation.run();
                if (callback != null) MAIN.post(() -> callback.onSuccess(value));
            } catch (RuntimeException error) {
                fail(callback, error);
            }
        }); } catch (RejectedExecutionException full) { fail(callback, new RejectedExecutionException("Bluetooth UUID settings queue is full", full)); }
    }
    private static void fail(Callback<Config> callback, Throwable error) { if (callback != null) MAIN.post(() -> callback.onError(error)); }
}
