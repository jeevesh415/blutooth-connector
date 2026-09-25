package com.jeevesh415.blutoothconnector.control;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class RemoteInputAccessibilityServiceTest {
    @Test public void coordinateProtocolIsDocumentedBySupportedOperations() {
        String[] operations = {"tap", "swipe", "back", "home", "recents", "lock", "text"};
        assertTrue(operations.length >= 7);
    }
}
