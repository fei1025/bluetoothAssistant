package com.zzf.bluetoothsmp;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class KeyboardLayoutStateTest {
    @Test public void freshDeviceHasOneButtonAndAddedUnconfiguredButtonsSurviveReopen() {
        KeyboardLayoutState state = KeyboardLayoutState.restore(null, Collections.emptySet());
        assertEquals(Collections.singleton(1), state.buttons().keySet());
        assertEquals(2, state.add(Collections.emptySet()));
        assertEquals(3, state.add(Collections.emptySet()));
        KeyboardLayoutState reopened = KeyboardLayoutState.restore(state.encode(), Collections.emptySet());
        assertEquals(state.buttons().keySet(), reopened.buttons().keySet());
    }

    @Test public void migrationKeepsExistingDeviceAndFallbackSlotsInLogicalOrder() {
        KeyboardLayoutState state = KeyboardLayoutState.restore(null, Arrays.asList(12, 3, 1, 3));
        assertEquals(Arrays.asList(1, 3, 12), Arrays.asList(state.buttons().keySet().toArray()));
        assertEquals(0, state.buttons().get(1).xFraction, 0);
        assertEquals(0.5f, state.buttons().get(3).xFraction, 0);
        assertEquals(1, state.buttons().get(12).xFraction, 0);
    }

    @Test public void addingBeyondOriginalTwelveKeepsEveryCommandIdentity() {
        KeyboardLayoutState state = KeyboardLayoutState.restore(null, Collections.emptySet());
        for (int slot = 2; slot <= 15; slot++) assertEquals(slot, state.add(Collections.emptySet()));
        KeyboardLayoutState reopened = KeyboardLayoutState.restore(state.encode(), Collections.emptySet());
        assertEquals(15, reopened.buttons().size());
        assertTrue(reopened.buttons().containsKey(13));
        assertEquals(15, KeyboardSlotResolver.slotForStoredId(15));
    }

    @Test public void movedPositionPersistsWithoutMutatingThePreviousSnapshot() {
        KeyboardLayoutState previous = KeyboardLayoutState.restore(null, Collections.emptySet());
        KeyboardLayoutState next = previous.copy();
        next.move(1, new KeyboardLayoutState.Position(0.7f, 246));
        KeyboardLayoutState reopened = KeyboardLayoutState.restore(next.encode(), Collections.emptySet());
        assertEquals(0.7f, reopened.buttons().get(1).xFraction, 0.0001f);
        assertEquals(246, reopened.buttons().get(1).yDp, 0);
        assertEquals(0, previous.buttons().get(1).yDp, 0);
    }

    @Test public void newButtonsAvoidMovedButtonsAndHiddenConfiguredCommands() {
        KeyboardLayoutState state = KeyboardLayoutState.restore(null, Collections.emptySet());
        state.move(1, new KeyboardLayoutState.Position(0.5f, 0));
        assertEquals(4, state.add(Arrays.asList(2, 3)));
        assertEquals(0, state.buttons().get(4).xFraction, 0);
    }

    @Test public void corruptLayoutsRecoverConfiguredButtonsInsteadOfHidingThem() {
        for (String corrupt : Arrays.asList("v1;1,NaN,1;", "v1;1,0,Infinity;", "v1;1,0,0;1,1,1;",
                "v1;2131230001,0,0;", "v1;", "invalid")) {
            KeyboardLayoutState state = KeyboardLayoutState.restore(corrupt, Arrays.asList(2, 10));
            assertEquals(Arrays.asList(2, 10), Arrays.asList(state.buttons().keySet().toArray()));
        }
    }

    @Test public void savedLayoutRecoversMissingOldButtonsAndKeepsUserPositions() {
        KeyboardLayoutState state = KeyboardLayoutState.restore("v1;1,0.7,246;",
                Arrays.asList(1, 3, 12));
        assertEquals(Arrays.asList(1, 3, 12), Arrays.asList(state.buttons().keySet().toArray()));
        assertEquals(0.7f, state.buttons().get(1).xFraction, 0.0001f);
        assertEquals(246, state.buttons().get(1).yDp, 0);
        assertEquals(state.encode(), KeyboardLayoutState.restore(state.encode(),
                Arrays.asList(12, 3, 1)).encode());
    }

    @Test public void additionLimitDoesNotHidePreviouslyConfiguredButtons() {
        java.util.List<Integer> existing = new java.util.ArrayList<>();
        for (int slot = 1; slot <= KeyboardLayoutState.MAX_BUTTONS + 2; slot++) existing.add(slot);
        KeyboardLayoutState state = KeyboardLayoutState.restore("v1;1,0.8,200;", existing);
        assertEquals(existing.size(), state.buttons().size());
        assertEquals(0, state.add(Collections.emptySet()));
        assertEquals(state.encode(), KeyboardLayoutState.restore(state.encode(), existing).encode());
    }

    @Test public void additionsAreBoundedAndPositionsStayInsideTheSurface() {
        KeyboardLayoutState state = KeyboardLayoutState.restore(null, Collections.emptySet());
        while (state.buttons().size() < KeyboardLayoutState.MAX_BUTTONS) assertTrue(state.add(Collections.emptySet()) > 0);
        assertEquals(0, state.add(Collections.emptySet()));
        assertEquals(KeyboardLayoutState.MAX_BUTTONS,
                KeyboardLayoutState.restore(state.encode(), Collections.emptySet()).buttons().size());
        KeyboardLayoutState.Position position = new KeyboardLayoutState.Position(4, -10);
        assertEquals(1, position.xFraction, 0);
        assertEquals(0, position.yDp, 0);
    }
}
