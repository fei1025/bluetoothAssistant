package com.zzf.bluetoothsmp.event;

import java.util.*;

public class EventDispatcher {
    private Map<EventType, Map<String, EventListener>> listenersMap;

    public EventDispatcher() {
        this.listenersMap = new HashMap();
    }

    public synchronized void addEventListener(EventType eventType, EventListener listener, String uuid) {
        if (listener == null) return;

        Map<String, EventListener> maps = listenersMap.get(eventType);

        if (maps == null) {
            maps = new HashMap<>();
            listenersMap.put(eventType, maps);
        }
        maps.put(uuid, listener);
    }

    public synchronized void deleteAllEventByUuid(String uuid) {
        ArrayList<Map<String, EventListener>> maps = new ArrayList<>(listenersMap.values());
        for (Map<String, EventListener> next : maps) {
            next.remove(uuid);
        }
    }

    public synchronized void deleteAllEventByUuidAndEventType(EventType eventType, String uuid) {
        Map<String, EventListener> stringEventListenerMap = listenersMap.get(eventType);
        if (stringEventListenerMap != null) {
            stringEventListenerMap.remove(uuid);
        }
    }


    protected void dispatchEvent(EventType eventType, Object... eventData) {
        Map<String, EventListener> stringEventListenerMap = listenersMap.get(eventType);
        if (stringEventListenerMap == null || stringEventListenerMap.isEmpty()) {
            return;
        }
        Collection<EventListener> values = stringEventListenerMap.values();
        ArrayList<EventListener> eventListeners = new ArrayList<>(values);
        for (int i = 0; i < eventListeners.size(); i++) {
            EventListener eventListener = eventListeners.get(i);
            eventListener.onEvent(buildEvent(eventType, eventData));
        }
    }

    protected Event buildEvent(EventType eventType, Object... eventData) {
        return new Event(eventType, this, eventData);
    }
}
