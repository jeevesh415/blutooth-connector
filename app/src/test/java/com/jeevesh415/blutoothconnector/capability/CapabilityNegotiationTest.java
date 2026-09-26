package com.jeevesh415.blutoothconnector.capability;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

public class CapabilityNegotiationTest {
    private static CapabilityManifest.Entry entry(
            String id, String version, boolean auth) {
        return new CapabilityManifest.Entry(id, version, auth);
    }

    @Test
    public void intersectsExactVersionsAndPropagatesAuthorization() {
        List<CapabilityManifest.Entry> local = Arrays.asList(
                entry("screen.view", "1", false),
                entry("device.lock", "1", true));
        List<CapabilityManifest.Entry> remote = Arrays.asList(
                entry("screen.view", "1", true),
                entry("device.lock", "2", true));

        CapabilityNegotiation.Result result =
                CapabilityNegotiation.negotiate(local, remote);

        assertTrue(result.supports("screen.view", "1"));
        assertTrue(result.authorizationRequired().contains("screen.view"));
        assertFalse(result.supports("device.lock", "1"));
        assertTrue(result.missingFromPeer().contains("device.lock"));
    }

    @Test
    public void nullAndUnknownEntriesDoNotCreateAuthority() {
        List<CapabilityManifest.Entry> local = Arrays.asList(
                entry("known", "1", false),
                null);
        List<CapabilityManifest.Entry> remote = Arrays.asList(
                entry("other", "1", false));

        CapabilityNegotiation.Result result =
                CapabilityNegotiation.negotiate(local, remote);

        assertFalse(result.supports("known", "1"));
        assertFalse(result.supports("other", "1"));
        assertTrue(result.missingFromPeer().contains("known"));
        assertTrue(result.authorizationRequired().isEmpty());
    }
}
