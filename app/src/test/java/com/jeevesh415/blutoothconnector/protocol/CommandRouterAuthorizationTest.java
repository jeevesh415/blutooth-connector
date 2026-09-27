package com.jeevesh415.blutoothconnector.protocol;

import com.jeevesh415.blutoothconnector.capability.Capability;
import com.jeevesh415.blutoothconnector.control.RemoteControlAuthorization;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CommandRouterAuthorizationTest {
    @Test public void protectedCapabilityDeclaresExplicitAuthorization() {
        Capability capability = new Capability() {
            @Override public String id() { return "protected.test"; }
            @Override public String version() { return "1.0"; }
            @Override public boolean requiresExplicitAuthorization() { return true; }
            @Override public boolean canHandle(Frame command) { return true; }
            @Override public Frame handle(Frame command) { return command; }
        };

        assertTrue(capability.requiresExplicitAuthorization());
    }

    @Test public void authorizationFailsClosedWithoutContext() {
        assertFalse(RemoteControlAuthorization.isAuthorized(
                null,
                "AA:BB:CC:DD:EE:FF"));
    }
}
