package com.zzf.bluetoothsmp;

import com.zzf.bluetoothsmp.entity.BluetoothDeviceProfileEntity;
import com.zzf.bluetoothsmp.utils.BluetoothAddressUtils;

import org.litepal.LitePal;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** CRUD for user-facing device metadata, independent from conversation history. */
public final class BluetoothDeviceProfileStore {
    private BluetoothDeviceProfileStore() {
    }

    public static synchronized BluetoothDeviceProfileEntity find(String address) {
        String normalized = BluetoothAddressUtils.normalize(address);
        if (normalized == null) {
            return null;
        }
        return LitePal.where("address = ?", normalized)
                .findFirst(BluetoothDeviceProfileEntity.class);
    }

    public static synchronized List<BluetoothDeviceProfileEntity> findAll() {
        List<BluetoothDeviceProfileEntity> profiles = LitePal.findAll(BluetoothDeviceProfileEntity.class);
        if (profiles == null || profiles.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.sort(profiles, new Comparator<BluetoothDeviceProfileEntity>() {
            @Override
            public int compare(BluetoothDeviceProfileEntity left,
                               BluetoothDeviceProfileEntity right) {
                if (left.isFavorite() != right.isFavorite()) {
                    return left.isFavorite() ? -1 : 1;
                }
                int connected = Long.compare(right.getLastConnectedAt(), left.getLastConnectedAt());
                if (connected != 0) {
                    return connected;
                }
                return Long.compare(right.getLastSeenAt(), left.getLastSeenAt());
            }
        });
        return profiles;
    }

    public static synchronized BluetoothDeviceProfileEntity observe(
            String address, String deviceName, long now) {
        String normalized = BluetoothAddressUtils.normalize(address);
        if (normalized == null) {
            return null;
        }
        BluetoothDeviceProfileEntity profile = find(normalized);
        if (profile == null) {
            profile = new BluetoothDeviceProfileEntity();
            profile.setAddress(normalized);
            profile.setCreatedAt(now);
        }
        if (deviceName != null && !deviceName.trim().isEmpty()) {
            profile.setDeviceName(deviceName);
        }
        profile.setLastSeenAt(now);
        profile.save();
        return profile;
    }

    public static synchronized BluetoothDeviceProfileEntity markConnected(
            String address, String deviceName, long now) {
        BluetoothDeviceProfileEntity profile = observe(address, deviceName, now);
        if (profile == null) {
            return null;
        }
        profile.setLastConnectedAt(now);
        profile.save();
        return profile;
    }

    public static synchronized BluetoothDeviceProfileEntity updateAlias(
            String address, String alias, long now) {
        BluetoothDeviceProfileEntity profile = observe(address, null, now);
        if (profile == null) {
            return null;
        }
        String normalizedAlias = alias == null ? "" : alias.trim();
        profile.setAlias(normalizedAlias.isEmpty() ? null : normalizedAlias);
        profile.save();
        return profile;
    }

    public static synchronized BluetoothDeviceProfileEntity setFavorite(
            String address, boolean favorite, long now) {
        BluetoothDeviceProfileEntity profile = observe(address, null, now);
        if (profile == null) {
            return null;
        }
        profile.setFavorite(favorite);
        profile.save();
        return profile;
    }
}
