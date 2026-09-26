package com.jeevesh415.blutoothconnector.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stable, typed view of the capabilities exposed by a receiver.
 *
 * The legacy capability-id array remains wire-compatible, while this manifest
 * gives future controllers enough metadata to negotiate individual features.
 */
public final class CapabilityManifest {
    public static final class Entry {
        public final String id;
        public final String version;
        public final boolean requiresExplicitAuthorization;

        public Entry(
                String id,
                String version,
                boolean requiresExplicitAuthorization) {
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("id");
            if (version == null || version.isEmpty()) {
                throw new IllegalArgumentException("version");
            }
            this.id = id;
            this.version = version;
            this.requiresExplicitAuthorization =
                    requiresExplicitAuthorization;
        }
    }

    private CapabilityManifest() {}

    public static List<Entry> from(CapabilityRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry");

        List<Entry> result = new ArrayList<>();
        for (Capability capability : registry.all()) {
            result.add(new Entry(
                    capability.id(),
                    capability.version(),
                    capability.requiresExplicitAuthorization()));
        }
        return Collections.unmodifiableList(result);
    }
}
