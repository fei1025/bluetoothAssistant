package com.zzf.bluetoothsmp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class KeyboardSlotResolverTest {
    private static final String DEVICE = "AA:BB:CC:DD:EE:01";
    private static final String OTHER_DEVICE = "AA:BB:CC:DD:EE:02";

    @Test
    public void mapsEveryButtonFromTheActualInstalledVersion23Apk() {
        int[] resourceIdsBySlot = {
                0x7f08006f, 0x7f080073, 0x7f080074, 0x7f080075,
                0x7f080076, 0x7f080077, 0x7f080078, 0x7f080079,
                0x7f08007a, 0x7f080070, 0x7f080071, 0x7f080072
        };
        for (int i = 0; i < resourceIdsBySlot.length; i++) {
            assertEquals("v23 button" + (i + 1), i + 1,
                    KeyboardSlotResolver.slotForStoredId(resourceIdsBySlot[i]));
        }
    }

    @Test
    public void mapsEveryButtonFromTheOriginalVersion28Apk() {
        int[] resourceIdsBySlot = {
                0x7f090082, 0x7f090086, 0x7f090087, 0x7f090088,
                0x7f090089, 0x7f09008a, 0x7f09008b, 0x7f09008c,
                0x7f09008d, 0x7f090083, 0x7f090084, 0x7f090085
        };
        for (int i = 0; i < resourceIdsBySlot.length; i++) {
            assertEquals("v28 button" + (i + 1), i + 1,
                    KeyboardSlotResolver.slotForStoredId(resourceIdsBySlot[i]));
        }
    }

    @Test
    public void stableSlotsSurviveFutureResourceChangesAndUnknownIdsAreNotGuessed() {
        for (int slot = 1; slot <= KeyboardSlotResolver.MAX_SLOT; slot++) {
            assertTrue(KeyboardSlotResolver.isStableSlot(slot));
            assertEquals(slot, KeyboardSlotResolver.slotForStoredId(slot));
        }
        for (int unknown : new int[]{0, -1, KeyboardSlotResolver.MAX_SLOT + 1, 0x7f08006e, 0x7f08007b,
                0x7f090081, 0x7f09008e, 0x7f0a0082}) {
            assertFalse(KeyboardSlotResolver.isStableSlot(unknown));
            assertEquals(0, KeyboardSlotResolver.slotForStoredId(unknown));
        }
    }

    @Test
    public void selectionKeepsDevicesSeparateAndLeavesUnknownRowsUntouched() {
        Row own = row(1, 0x7f08006f, DEVICE, "own", "A", "B");
        Row other = row(99, 0x7f090082, OTHER_DEVICE, "other", "X", "Y");
        Row unknown = row(100, 0x7f070001, DEVICE, "recover later", "Z", "");
        List<Row> rows = Arrays.asList(other, unknown, own);
        Map<Integer, Row> selected = KeyboardSlotResolver.selectForAddress(rows, DEVICE);
        assertEquals(1, selected.size());
        assertSame(own, selected.get(1));
        assertEquals(0x7f08006f, own.buttonId);
        assertEquals(0x7f070001, unknown.buttonId);
        assertEquals(3, rows.size());
        assertSame(other, KeyboardSlotResolver.selectForAddress(rows, OTHER_DEVICE).get(1));
    }

    @Test
    public void configuredLegacyRowWinsOverNewerBlankLegacyDuplicate() {
        Row configured23 = row(1, 0x7f08006f, DEVICE, "启动", "ON\r\n", "OFF\r\n");
        Row empty28 = row(90, 0x7f090082, DEVICE, null, "", null);
        assertSame(configured23, select(configured23, empty28).get(1));
    }

    @Test
    public void whitespaceCommandIsNotMistakenForAnEmptyLegacyRow() {
        Row space23 = row(1, 0x7f08006f, DEVICE, null, " ", "");
        Row empty28 = row(2, 0x7f090082, DEVICE, null, "", "");
        assertSame(space23, select(empty28, space23).get(1));
    }

    @Test
    public void stableEmptyEditWinsSoOldActionsCannotReappear() {
        Row old23 = row(500, 0x7f08006f, DEVICE, "old", "OLD", "UP");
        Row stableEmpty = row(2, 1, DEVICE, "", "", "");
        assertSame(stableEmpty, select(old23, stableEmpty).get(1));
    }

    @Test
    public void configuredV28WinsOverConfiguredV23EvenWhenInsertedEarlier() {
        Row old23 = row(90, 0x7f08006f, DEVICE, "old", "V23", "");
        Row old28 = row(10, 0x7f090082, DEVICE, "new version", "V28", "");
        assertSame(old28, select(old23, old28).get(1));
    }

    @Test
    public void duplicateTieBreakUsesRowIdAndNotQueryOrder() {
        Row first = row(4, 0x7f08006f, DEVICE, "first", "A", "");
        Row last = row(8, 0x7f08006f, DEVICE, "last inserted", "B", "");
        List<Row> rows = new ArrayList<>(Arrays.asList(first, last));
        assertSame(last, KeyboardSlotResolver.selectForAddress(rows, DEVICE).get(1));
        Collections.reverse(rows);
        assertSame(last, KeyboardSlotResolver.selectForAddress(rows, DEVICE).get(1));
    }

    @Test
    public void duplicateStableRowsAlsoHaveDeterministicPriority() {
        Row first = row(4, 1, DEVICE, "configured", "A", "");
        Row cleared = row(8, 1, DEVICE, "", "", "");
        assertSame(cleared, select(cleared, first).get(1));
    }

    @Test
    public void defaultsOnlyFillMissingSlotsAndNeverReplaceAnEmptyDeviceOverride() {
        Row deviceEmpty = row(1, 1, DEVICE, "", "", "");
        Row defaultOne = row(2, 1, KeyboardSlotResolver.DEFAULT_ADDRESS, "default1", "A", "");
        Row defaultTwo = row(3, 2, KeyboardSlotResolver.DEFAULT_ADDRESS, "default2", "B", "");
        List<Row> rows = Arrays.asList(defaultOne, deviceEmpty, defaultTwo);
        Map<Integer, Row> own = KeyboardSlotResolver.selectForAddress(rows, DEVICE);
        Map<Integer, Row> defaults = KeyboardSlotResolver.selectForAddress(
                rows, KeyboardSlotResolver.DEFAULT_ADDRESS);
        assertSame(deviceEmpty, KeyboardSlotResolver.effectiveForSlot(1, own, defaults));
        assertSame(defaultTwo, KeyboardSlotResolver.effectiveForSlot(2, own, defaults));
        assertNull(KeyboardSlotResolver.effectiveForSlot(3, own, defaults));
        assertEquals(3, rows.size());
    }

    @Test
    public void winnerOnlyMigrationIsIdempotentAndPreservesAllPayloadsAndLosers() {
        Row selected23 = row(4, 0x7f080070, DEVICE, "第十键", "  down\r\n", "up\n");
        Row empty28 = row(9, 0x7f090083, DEVICE, null, "", "");
        Row unknown = row(10, 0x7f060099, DEVICE, "unknown", "untouched", "");
        List<Row> rows = Arrays.asList(empty28, unknown, selected23);
        Map<Integer, Row> first = KeyboardSlotResolver.selectForAddress(rows, DEVICE);
        assertSame(selected23, first.get(10));
        for (Map.Entry<Integer, Row> entry : first.entrySet()) {
            entry.getValue().buttonId = entry.getKey(); // The selected-row write in the UI.
        }
        Map<Integer, Row> second = KeyboardSlotResolver.selectForAddress(rows, DEVICE);
        assertSame(selected23, second.get(10));
        assertEquals(10, selected23.buttonId);
        assertEquals("第十键", selected23.name);
        assertEquals("  down\r\n", selected23.down);
        assertEquals("up\n", selected23.up);
        assertEquals(0x7f090083, empty28.buttonId);
        assertEquals(0x7f060099, unknown.buttonId);
        assertEquals(3, rows.size());
    }

    @Test
    public void aNewDeviceOverrideDoesNotChangeDefaultOrOtherDeviceSelection() {
        Row fallback = row(1, 0x7f08006f, KeyboardSlotResolver.DEFAULT_ADDRESS,
                "default", "DOWN", "UP");
        Row other = row(2, 1, OTHER_DEVICE, "other", "OTHER", "");
        List<Row> rows = new ArrayList<>(Arrays.asList(fallback, other));
        Map<Integer, Row> defaults = KeyboardSlotResolver.selectForAddress(
                rows, KeyboardSlotResolver.DEFAULT_ADDRESS);
        assertSame(fallback, KeyboardSlotResolver.effectiveForSlot(1,
                KeyboardSlotResolver.selectForAddress(rows, DEVICE), defaults));
        Row override = row(3, 1, DEVICE, "edited", fallback.down, fallback.up);
        rows.add(override);
        assertSame(override, KeyboardSlotResolver.effectiveForSlot(1,
                KeyboardSlotResolver.selectForAddress(rows, DEVICE), defaults));
        assertSame(other, KeyboardSlotResolver.selectForAddress(rows, OTHER_DEVICE).get(1));
        assertEquals(KeyboardSlotResolver.DEFAULT_ADDRESS, fallback.address);
        assertEquals("default", fallback.name);
        assertEquals(0x7f08006f, fallback.buttonId);
    }

    private static Map<Integer, Row> select(Row... rows) {
        return KeyboardSlotResolver.selectForAddress(Arrays.asList(rows), DEVICE);
    }

    private static Row row(long id, int buttonId, String address, String name,
                           String down, String up) {
        return new Row(id, buttonId, address, name, down, up);
    }

    private static final class Row implements KeyboardSlotResolver.Entry {
        private final long id;
        private int buttonId;
        private final String address;
        private final String name;
        private final String down;
        private final String up;

        Row(long id, int buttonId, String address, String name, String down, String up) {
            this.id = id;
            this.buttonId = buttonId;
            this.address = address;
            this.name = name;
            this.down = down;
            this.up = up;
        }

        @Override public long getKeyboardRecordId() { return id; }
        @Override public int getButtonId() { return buttonId; }
        @Override public String getDriveAdd() { return address; }
        @Override public String getButtonName() { return name; }
        @Override public String getActionDown() { return down; }
        @Override public String getActionUp() { return up; }
    }
}

