package com.jeevesh415.blutoothconnector.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ControlReplayGuardTest {
    @Test public void acceptsOnlyStrictlyIncreasingSequences() {
        ControlReplayGuard guard = new ControlReplayGuard();

        assertFalse(guard.accept(0));
        assertTrue(guard.accept(1));
        assertFalse(guard.accept(1));
        assertFalse(guard.accept(0));
        assertTrue(guard.accept(3));
        assertFalse(guard.accept(2));
        assertEquals(3L, guard.highest());
    }
}
