package com.zzf.bluetoothsmp.event;

public interface EventListener {
    public  String uuid = null;
    void onEvent(Event event);

}
