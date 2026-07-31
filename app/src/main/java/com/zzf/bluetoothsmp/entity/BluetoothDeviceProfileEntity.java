package com.zzf.bluetoothsmp.entity;

import org.litepal.crud.LitePalSupport;

/** User-owned device metadata kept separate from message history. */
public class BluetoothDeviceProfileEntity extends LitePalSupport {
    private String address;
    private String deviceName;
    private String alias;
    private boolean favorite;
    private long createdAt;
    private long lastSeenAt;
    private long lastConnectedAt;

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(long lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public long getLastConnectedAt() {
        return lastConnectedAt;
    }

    public void setLastConnectedAt(long lastConnectedAt) {
        this.lastConnectedAt = lastConnectedAt;
    }
}
