package com.zzf.bluetoothsmp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentValues;

import com.zzf.bluetoothsmp.entity.BluetoothDeviceProfileEntity;
import com.zzf.bluetoothsmp.utils.BluetoothAddressUtils;

import org.litepal.LitePal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Worker-only CRUD plus lock-free immutable snapshots for discovery and UI. */
public final class BluetoothDeviceProfileStore {
    private static final int MAX_LOAD_CALLBACKS = 64;
    private static final Object DATABASE_LOCK = new Object();
    private static final Object LOAD_LOCK = new Object();
    private static final ExecutorService IO = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), runnable -> {
                Thread thread = new Thread(runnable, "device-profile-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static volatile Map<String, Snapshot> cache = Collections.emptyMap();
    private static volatile boolean cacheLoaded;
    private static boolean loading;
    private static boolean indexesReady;
    private static final List<Callback<List<BluetoothDeviceProfileEntity>>> loads = new ArrayList<>();

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Throwable error);
    }

    private BluetoothDeviceProfileStore() { }

    private static final Comparator<BluetoothDeviceProfileEntity> ORDER = (left, right) -> {
        if (left.isFavorite() != right.isFavorite()) return left.isFavorite() ? -1 : 1;
        int recent = Long.compare(right.getLastConnectedAt(), left.getLastConnectedAt());
        return recent != 0 ? recent : Long.compare(right.getLastSeenAt(), left.getLastSeenAt());
    };

    public static BluetoothDeviceProfileEntity findCached(String address) {
        String normalized = BluetoothAddressUtils.normalize(address);
        Snapshot value = normalized == null ? null : cache.get(normalized);
        return value == null ? null : value.copy();
    }

    public static List<BluetoothDeviceProfileEntity> findAllCached() {
        List<BluetoothDeviceProfileEntity> values = new ArrayList<>();
        for (Snapshot snapshot : cache.values()) values.add(snapshot.copy());
        Collections.sort(values, ORDER);
        return values;
    }

    public static boolean isCacheLoaded() { return cacheLoaded; }

    public static void loadAsync(Callback<List<BluetoothDeviceProfileEntity>> callback) {
        synchronized (LOAD_LOCK) {
            if (callback != null && loads.size() >= MAX_LOAD_CALLBACKS) {
                RejectedExecutionException busy = new RejectedExecutionException("Device profile load callback limit reached");
                MainHandler.INSTANCE.post(() -> callback.onError(busy));
                return;
            }
            if (callback != null) loads.add(callback);
            if (loading) return;
            loading = true;
        }
        try { IO.execute(() -> {
            List<BluetoothDeviceProfileEntity> result = null;
            Throwable failure = null;
            try { result = findAll(); }
            catch (RuntimeException error) { failure = error; }
            List<Callback<List<BluetoothDeviceProfileEntity>>> callbacks;
            synchronized (LOAD_LOCK) {
                callbacks = new ArrayList<>(loads);
                loads.clear();
                loading = false;
            }
            final List<BluetoothDeviceProfileEntity> rows = result;
            final Throwable error = failure;
            for (Callback<List<BluetoothDeviceProfileEntity>> listener : callbacks) {
                MainHandler.INSTANCE.post(() -> {
                    if (error == null) listener.onSuccess(copyList(rows));
                    else listener.onError(error);
                });
            }
        }); } catch (RejectedExecutionException error) {
            List<Callback<List<BluetoothDeviceProfileEntity>>> callbacks;
            synchronized (LOAD_LOCK) {
                callbacks = new ArrayList<>(loads);
                loads.clear();
                loading = false;
            }
            for (Callback<List<BluetoothDeviceProfileEntity>> listener : callbacks) {
                MainHandler.INSTANCE.post(() -> listener.onError(error));
            }
        }
    }

    public static void updateAliasAsync(String address, String alias, long now,
                                         Callback<BluetoothDeviceProfileEntity> callback) {
        runAsync(callback, () -> updateAlias(address, alias, now));
    }

    public static void setFavoriteAsync(String address, boolean favorite, long now,
                                        Callback<BluetoothDeviceProfileEntity> callback) {
        runAsync(callback, () -> setFavorite(address, favorite, now));
    }

    private interface Operation { BluetoothDeviceProfileEntity run(); }

    private static void runAsync(Callback<BluetoothDeviceProfileEntity> callback, Operation operation) {
        try { IO.execute(() -> complete(callback, operation)); }
        catch (RejectedExecutionException error) {
            if (callback != null) MainHandler.INSTANCE.post(() -> callback.onError(error));
            else Log.w("BluetoothProfiles", "Metadata backlog limit reached", error);
        }
    }

    private static void complete(Callback<BluetoothDeviceProfileEntity> callback, Operation operation) {
        try {
            BluetoothDeviceProfileEntity saved = operation.run();
            if (saved == null) throw new IllegalArgumentException("Invalid Bluetooth device address");
            BluetoothDeviceProfileEntity snapshot = new Snapshot(saved).copy();
            if (callback != null) MainHandler.INSTANCE.post(() -> callback.onSuccess(snapshot));
        } catch (RuntimeException error) {
            if (callback != null) MainHandler.INSTANCE.post(() -> callback.onError(error));
            else Log.w("BluetoothProfiles", "Device metadata update failed", error);
        }
    }

    /** Legacy background call sites keep synchronous access; UI receives only a cached copy. */
    public static BluetoothDeviceProfileEntity find(String address) {
        if (Looper.myLooper() == Looper.getMainLooper()) return findCached(address);
        synchronized (DATABASE_LOCK) {
            indexes();
            return findInDatabase(address);
        }
    }

    public static List<BluetoothDeviceProfileEntity> findAll() {
        if (Looper.myLooper() == Looper.getMainLooper()) return findAllCached();
        synchronized (DATABASE_LOCK) {
            indexes();
            List<BluetoothDeviceProfileEntity> rows = LitePal.findAll(BluetoothDeviceProfileEntity.class);
            rows = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
            Map<String, Snapshot> fresh = new HashMap<>();
            for (BluetoothDeviceProfileEntity row : rows) {
                String normalized = BluetoothAddressUtils.normalize(row.getAddress());
                if (normalized != null) fresh.put(normalized, new Snapshot(row));
            }
            cache = Collections.unmodifiableMap(fresh);
            cacheLoaded = true;
            Collections.sort(rows, ORDER);
            return rows;
        }
    }

    public static BluetoothDeviceProfileEntity observe(String address, String name, long now) {
        requireWorker();
        synchronized (DATABASE_LOCK) {
            BluetoothDeviceProfileEntity profile = obtain(address, now);
            if (profile == null) return null;
            if (name != null && !name.trim().isEmpty() && now >= profile.getLastSeenAt()) profile.setDeviceName(name.trim());
            profile.setLastSeenAt(Math.max(profile.getLastSeenAt(), now));
            return save(profile);
        }
    }

    /** A metadata failure must not close an otherwise working transport. */
    public static BluetoothDeviceProfileEntity markConnected(String address, String name, long now) {
        requireWorker();
        try {
            synchronized (DATABASE_LOCK) {
                BluetoothDeviceProfileEntity profile = obtain(address, now);
                if (profile == null) return null;
                if (name != null && !name.trim().isEmpty() && now >= profile.getLastSeenAt()) profile.setDeviceName(name.trim());
                profile.setLastSeenAt(Math.max(profile.getLastSeenAt(), now));
                profile.setLastConnectedAt(Math.max(profile.getLastConnectedAt(), now));
                return save(profile);
            }
        } catch (RuntimeException error) {
            Log.w("BluetoothProfiles", "Could not save connected-device metadata", error);
            return null;
        }
    }

    public static BluetoothDeviceProfileEntity updateAlias(String address, String alias, long now) {
        requireWorker();
        synchronized (DATABASE_LOCK) {
            BluetoothDeviceProfileEntity profile = obtain(address, now);
            if (profile == null) return null;
            String text = alias == null ? "" : alias.trim();
            profile.setAlias(text.isEmpty() ? null : text);
            profile.setLastSeenAt(Math.max(profile.getLastSeenAt(), now));
            return save(profile);
        }
    }

    public static BluetoothDeviceProfileEntity setFavorite(String address, boolean favorite, long now) {
        requireWorker();
        synchronized (DATABASE_LOCK) {
            BluetoothDeviceProfileEntity profile = obtain(address, now);
            if (profile == null) return null;
            profile.setFavorite(favorite);
            profile.setLastSeenAt(Math.max(profile.getLastSeenAt(), now));
            return save(profile);
        }
    }

    private static BluetoothDeviceProfileEntity obtain(String address, long now) {
        String normalized = BluetoothAddressUtils.normalize(address);
        if (normalized == null || !BluetoothAdapter.checkBluetoothAddress(normalized)) {
            throw new IllegalArgumentException("Invalid Bluetooth device address");
        }
        indexes();
        BluetoothDeviceProfileEntity row = findInDatabase(normalized);
        if (row == null) {
            row = new BluetoothDeviceProfileEntity();
            row.setAddress(normalized);
            row.setCreatedAt(now);
        }
        return row;
    }

    private static BluetoothDeviceProfileEntity findInDatabase(String address) {
        String normalized = BluetoothAddressUtils.normalize(address);
        return normalized == null ? null : LitePal.where("address = ?", normalized)
                .findFirst(BluetoothDeviceProfileEntity.class);
    }

    private static BluetoothDeviceProfileEntity save(BluetoothDeviceProfileEntity profile) {
        if (profile.isSaved()) {
            // LitePal save() ignores null/false on existing rows; explicit values
            // persist cleared aliases and favorites in a single database update.
            ContentValues values = new ContentValues();
            values.put("devicename", profile.getDeviceName());
            values.put("alias", profile.getAlias());
            values.put("favorite", profile.isFavorite());
            values.put("createdat", profile.getCreatedAt());
            values.put("lastseenat", profile.getLastSeenAt());
            values.put("lastconnectedat", profile.getLastConnectedAt());
            if (LitePal.updateAll(BluetoothDeviceProfileEntity.class, values,
                    "address = ?", profile.getAddress()) != 1) {
                throw new IllegalStateException("Device metadata could not be updated");
            }
        } else if (!profile.save()) {
            throw new IllegalStateException("Device metadata could not be saved");
        }
        Map<String, Snapshot> updated = new HashMap<>(cache);
        updated.put(profile.getAddress(), new Snapshot(profile));
        cache = Collections.unmodifiableMap(updated);
        return profile;
    }

    private static void indexes() {
        if (!indexesReady) {
            LitePal.getDatabase().execSQL("CREATE INDEX IF NOT EXISTS idx_device_profile_address ON bluetoothdeviceprofileentity(address)");
            indexesReady = true;
        }
    }

    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Device metadata writes require a worker");
    }

    private static List<BluetoothDeviceProfileEntity> copyList(List<BluetoothDeviceProfileEntity> rows) {
        List<BluetoothDeviceProfileEntity> result = new ArrayList<>();
        if (rows != null) for (BluetoothDeviceProfileEntity row : rows) result.add(new Snapshot(row).copy());
        return result;
    }

    private static class MainHandler {
        static final Handler INSTANCE = new Handler(Looper.getMainLooper());
    }

    private static final class Snapshot {
        final String address, name, alias;
        final boolean favorite;
        final long created, seen, connected;
        Snapshot(BluetoothDeviceProfileEntity source) {
            address = source.getAddress(); name = source.getDeviceName(); alias = source.getAlias();
            favorite = source.isFavorite(); created = source.getCreatedAt();
            seen = source.getLastSeenAt(); connected = source.getLastConnectedAt();
        }
        BluetoothDeviceProfileEntity copy() {
            BluetoothDeviceProfileEntity result = new BluetoothDeviceProfileEntity();
            result.setAddress(address); result.setDeviceName(name); result.setAlias(alias);
            result.setFavorite(favorite); result.setCreatedAt(created);
            result.setLastSeenAt(seen); result.setLastConnectedAt(connected);
            return result;
        }
    }
}
