package com.zzf.bluetoothsmp.liaoTian;

import java.util.Objects;

/** A screen owns one transport generation, rather than whichever transport later uses its MAC. */
public final class ChatSessionIdentity {
    private final String address;
    private final String serviceUuid;
    private final String sessionId;

    public ChatSessionIdentity(String address, String serviceUuid, String sessionId) {
        this.address = address;
        this.serviceUuid = serviceUuid;
        this.sessionId = sessionId;
    }

    public boolean accepts(String eventAddress, String eventUuid, String eventSessionId) {
        return sessionId != null && sessionId.equals(eventSessionId)
                && Objects.equals(address, eventAddress)
                && Objects.equals(serviceUuid, eventUuid);
    }

    public boolean acceptsDisconnect(String eventAddress, String eventSessionId) {
        return sessionId != null && sessionId.equals(eventSessionId)
                && Objects.equals(address, eventAddress);
    }
}
