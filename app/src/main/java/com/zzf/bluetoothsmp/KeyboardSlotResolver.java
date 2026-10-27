package com.zzf.bluetoothsmp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves persisted keyboard rows without depending on Android resource IDs. */
public final class KeyboardSlotResolver {
    public static final String DEFAULT_ADDRESS = "defaultAdd";
    public static final int SLOT_COUNT = 12;
    public static final int MAX_SLOT = 10000;

    // Indexed by logical button number, verified from the installed 23/2.3 APK
    // and the original 28/2.8 APK. Do not infer aliases for unverified builds.
    private static final int[] V23_IDS = {
            0x7f08006f, 0x7f080073, 0x7f080074, 0x7f080075,
            0x7f080076, 0x7f080077, 0x7f080078, 0x7f080079,
            0x7f08007a, 0x7f080070, 0x7f080071, 0x7f080072
    };
    private static final int[] V28_IDS = {
            0x7f090082, 0x7f090086, 0x7f090087, 0x7f090088,
            0x7f090089, 0x7f09008a, 0x7f09008b, 0x7f09008c,
            0x7f09008d, 0x7f090083, 0x7f090084, 0x7f090085
    };

    private KeyboardSlotResolver() {
    }

    public interface Entry {
        long getKeyboardRecordId();
        int getButtonId();
        String getDriveAdd();
        String getButtonName();
        String getActionDown();
        String getActionUp();
    }

    /** Returns a stable logical slot, or 0 for an unverified old resource ID. */
    public static int slotForStoredId(int storedId) {
        if (isStableSlot(storedId)) {
            return storedId;
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (storedId == V23_IDS[i] || storedId == V28_IDS[i]) {
                return i + 1;
            }
        }
        return 0;
    }

    public static boolean isStableSlot(int value) {
        return value >= 1 && value <= MAX_SLOT;
    }

    /**
     * Selects rows for one address without changing or deleting any input row.
     * A stable-slot row is authoritative, including an intentionally blank edit.
     * For legacy duplicates, configured rows beat blank rows, then v28 beats v23.
     * Equal-priority duplicates use the highest database row ID, independent of
     * query order. Losers and unknown IDs remain available for recovery.
     */
    public static <T extends Entry> Map<Integer, T> selectForAddress(
            Iterable<T> entries, String address) {
        Map<Integer, T> result = new LinkedHashMap<>();
        for (T candidate : entries) {
            if (!Objects.equals(address, candidate.getDriveAdd())) {
                continue;
            }
            int slot = slotForStoredId(candidate.getButtonId());
            if (slot == 0) {
                continue;
            }
            T current = result.get(slot);
            if (current == null || preferred(candidate, current)) {
                result.put(slot, candidate);
            }
        }
        return result;
    }

    /** A device row, even a blank one, overrides the default for that slot. */
    public static <T extends Entry> T effectiveForSlot(
            int slot, Map<Integer, T> deviceRows, Map<Integer, T> defaultRows) {
        T own = deviceRows.get(slot);
        return own != null ? own : defaultRows.get(slot);
    }

    private static boolean preferred(Entry candidate, Entry current) {
        int priority = priority(candidate);
        int currentPriority = priority(current);
        return priority > currentPriority
                || (priority == currentPriority
                && candidate.getKeyboardRecordId() > current.getKeyboardRecordId());
    }

    private static int priority(Entry entry) {
        if (isStableSlot(entry.getButtonId())) {
            return 100;
        }
        boolean configured = hasText(entry.getButtonName())
                || hasText(entry.getActionDown()) || hasText(entry.getActionUp());
        int legacyVersion = 1;
        for (int id : V28_IDS) {
            if (id == entry.getButtonId()) {
                legacyVersion = 2;
                break;
            }
        }
        return (configured ? 10 : 0) + legacyVersion;
    }

    private static boolean hasText(String value) {
        // Whitespace is meaningful in commands, so do not trim configuration.
        return value != null && !value.isEmpty();
    }
}
