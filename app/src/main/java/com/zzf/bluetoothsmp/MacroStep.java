package com.zzf.bluetoothsmp;

public final class MacroStep {
    public enum Type {
        TEXT,
        HEX,
        DELAY
    }

    private final Type type;
    private final String text;
    private final byte[] bytes;
    private final long delayMillis;

    private MacroStep(Type type, String text, byte[] bytes, long delayMillis) {
        this.type = type;
        this.text = text;
        this.bytes = bytes == null ? null : bytes.clone();
        this.delayMillis = delayMillis;
    }

    public static MacroStep text(String value) {
        return new MacroStep(Type.TEXT, value, null, 0L);
    }

    public static MacroStep hex(byte[] value) {
        return new MacroStep(Type.HEX, null, value, 0L);
    }

    public static MacroStep delay(long millis) {
        return new MacroStep(Type.DELAY, null, null, millis);
    }

    public Type getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public byte[] getBytes() {
        return bytes == null ? null : bytes.clone();
    }

    public long getDelayMillis() {
        return delayMillis;
    }
}
