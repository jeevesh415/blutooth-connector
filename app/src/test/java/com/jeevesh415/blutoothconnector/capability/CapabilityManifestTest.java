package com.jeevesh415.blutoothconnector.capability;

import com.jeevesh415.blutoothconnector.protocol.Frame;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CapabilityManifestTest {
    @Test
    public void manifestCarriesVersionAndAuthorization() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(new Capability() {
            @Override public String id() { return "test.capability"; }
            @Override public String version() { return "2.1"; }
            @Override public boolean canHandle(Frame command) { return false; }
            @Override public Frame handle(Frame command) { return null; }
            @Override public boolean requiresExplicitAuthorization() {
                return true;
            }
        });

        List<CapabilityManifest.Entry> manifest =
                CapabilityManifest.from(registry);

        assertEquals(1, manifest.size());
        assertEquals("test.capability", manifest.get(0).id);
        assertEquals("2.1", manifest.get(0).version);
        assertTrue(manifest.get(0).requiresExplicitAuthorization);
    }

    @Test
    public void registryReplacementProducesOneManifestEntry() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(new TestCapability("test.capability", "1"));
        registry.register(new TestCapability("test.capability", "2"));

        List<CapabilityManifest.Entry> manifest =
                CapabilityManifest.from(registry);

        assertEquals(1, manifest.size());
        assertEquals("2", manifest.get(0).version);
    }

    private static final class TestCapability implements Capability {
        private final String id;
        private final String version;

        TestCapability(String id, String version) {
            this.id = id;
            this.version = version;
        }

        @Override public String id() { return id; }
        @Override public String version() { return version; }
        @Override public boolean canHandle(Frame command) { return false; }
        @Override public Frame handle(Frame command) { return null; }
    }
}
