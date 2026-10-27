package com.zzf.bluetoothsmp;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Device-local button membership and positions, independent of Android view IDs. */
public final class KeyboardLayoutState {
    public static final int MAX_BUTTONS = 128;
    public static final float ROW_HEIGHT_DP = 108;
    private final LinkedHashMap<Integer, Position> buttons = new LinkedHashMap<>();

    public static final class Position {
        public final float xFraction;
        public final float yDp;

        public Position(float xFraction, float yDp) {
            this.xFraction = Math.max(0, Math.min(1, xFraction));
            this.yDp = Math.max(0, Math.min(KeyboardSlotResolver.MAX_SLOT * ROW_HEIGHT_DP, yDp));
        }
    }

    public static KeyboardLayoutState restore(String stored, Collection<Integer> existingSlots) {
        if (stored != null && stored.startsWith("v1;")) {
            try {
                KeyboardLayoutState restored = new KeyboardLayoutState();
                for (String part : stored.substring(3).split(";")) {
                    if (part.isEmpty()) continue;
                    String[] values = part.split(",");
                    if (values.length != 3) throw new IllegalArgumentException();
                    int slot = Integer.parseInt(values[0]);
                    float x = Float.parseFloat(values[1]);
                    float y = Float.parseFloat(values[2]);
                    if (!KeyboardSlotResolver.isStableSlot(slot) || Float.isNaN(x)
                            || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y)
                            || restored.buttons.containsKey(slot)
                            || restored.buttons.size() >= KeyboardSlotResolver.MAX_SLOT) throw new IllegalArgumentException();
                    restored.buttons.put(slot, new Position(x, y));
                }
                if (!restored.buttons.isEmpty()) {
                    restored.includeExisting(existingSlots);
                    return restored;
                }
            } catch (IllegalArgumentException ignored) {
                // Recover from command rows rather than hiding existing configuration.
            }
        }
        KeyboardLayoutState initial = new KeyboardLayoutState();
        initial.includeExisting(existingSlots);
        if (initial.buttons.isEmpty()) initial.append(1);
        return initial;
    }

    private void includeExisting(Collection<Integer> existingSlots) {
        // A layout is never authority to discard a persisted command. Retain all
        // saved positions and append any missing device/default buttons, even when
        // recovering more controls than the limit for user-initiated additions.
        for (int slot : new TreeSet<>(existingSlots)) {
            if (KeyboardSlotResolver.isStableSlot(slot) && !buttons.containsKey(slot)) append(slot);
        }
    }

    public Map<Integer, Position> buttons() {
        return Collections.unmodifiableMap(buttons);
    }

    public KeyboardLayoutState copy() {
        KeyboardLayoutState copy = new KeyboardLayoutState();
        copy.buttons.putAll(buttons);
        return copy;
    }

    public int add(Collection<Integer> configuredSlots) {
        if (buttons.size() >= MAX_BUTTONS) return 0;
        for (int slot = 1; slot <= KeyboardSlotResolver.MAX_SLOT; slot++) {
            if (!buttons.containsKey(slot) && !configuredSlots.contains(slot)) {
                append(slot);
                return slot;
            }
        }
        return 0;
    }

    private void append(int slot) {
        // Search for a free grid cell even when older buttons were freely moved.
        for (int row = 0; ; row++) {
            for (int column = 0; column < 3; column++) {
                Position candidate = new Position(column / 2f, row * ROW_HEIGHT_DP);
                boolean occupied = false;
                for (Position position : buttons.values()) {
                    if (Math.abs(position.xFraction - candidate.xFraction) < 0.49f
                            && Math.abs(position.yDp - candidate.yDp) < ROW_HEIGHT_DP) {
                        occupied = true;
                        break;
                    }
                }
                if (!occupied) {
                    buttons.put(slot, candidate);
                    return;
                }
            }
        }
    }

    public void move(int slot, Position position) {
        if (buttons.containsKey(slot)) buttons.put(slot, position);
    }

    public String encode() {
        StringBuilder stored = new StringBuilder("v1;");
        for (Map.Entry<Integer, Position> button : buttons.entrySet()) {
            stored.append(button.getKey()).append(',').append(button.getValue().xFraction)
                    .append(',').append(button.getValue().yDp).append(';');
        }
        return stored.toString();
    }
}
