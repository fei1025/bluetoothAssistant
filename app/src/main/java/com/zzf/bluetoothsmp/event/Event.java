package com.zzf.bluetoothsmp.event;

public class Event {
    private EventType type;
    private EventDispatcher source;
    private Object[] eventData;

    public Event(EventType type, EventDispatcher source, Object... eventData) {
        this.type = type;
        this.source = source;
        this.eventData = eventData;
    }

    public Object[] getEventData() {
        return eventData;
    }

    public void setEventData(Object[] eventData) {
        this.eventData = eventData;
    }
}
