package com.jeevesh415.blutoothconnector.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic capability negotiation between two peers.
 *
 * Negotiation is intentionally intersection-based: a controller may only use
 * capabilities both peers advertise, and a capability requiring explicit
 * authorization is never implicitly upgraded to an authorized state.
 */
public final class CapabilityNegotiation {
    public static final class Result {
        private final List<CapabilityManifest.Entry> shared;
        private final Set<String> missingFromPeer;
        private final Set<String> authorizationRequired;

        Result(
                List<CapabilityManifest.Entry> shared,
                Set<String> missingFromPeer,
                Set<String> authorizationRequired) {
            this.shared = Collections.unmodifiableList(
                    new ArrayList<>(shared));
            this.missingFromPeer = Collections.unmodifiableSet(
                    new HashSet<>(missingFromPeer));
            this.authorizationRequired = Collections.unmodifiableSet(
                    new HashSet<>(authorizationRequired));
        }

        public List<CapabilityManifest.Entry> shared() {
            return shared;
        }

        public Set<String> missingFromPeer() {
            return missingFromPeer;
        }

        public Set<String> authorizationRequired() {
            return authorizationRequired;
        }

        public boolean supports(String id, String version) {
            for (CapabilityManifest.Entry entry : shared) {
                if (entry.id.equals(id) && entry.version.equals(version)) {
                    return true;
                }
            }
            return false;
        }
    }

    private CapabilityNegotiation() {}

    public static Result negotiate(
            List<CapabilityManifest.Entry> local,
            List<CapabilityManifest.Entry> remote) {
        if (local == null || remote == null) {
            throw new IllegalArgumentException("manifests");
        }

        Map<String, CapabilityManifest.Entry> remoteById =
                new HashMap<>();
        for (CapabilityManifest.Entry entry : remote) {
            if (entry != null) {
                remoteById.put(entry.id, entry);
            }
        }

        List<CapabilityManifest.Entry> shared = new ArrayList<>();
        Set<String> missing = new HashSet<>();
        Set<String> auth = new HashSet<>();

        for (CapabilityManifest.Entry entry : local) {
            if (entry == null) continue;

            CapabilityManifest.Entry peer = remoteById.get(entry.id);
            if (peer == null) {
                missing.add(entry.id);
                continue;
            }

            if (!entry.version.equals(peer.version)) {
                missing.add(entry.id);
                continue;
            }

            shared.add(new CapabilityManifest.Entry(
                    entry.id,
                    entry.version,
                    entry.requiresExplicitAuthorization
                            || peer.requiresExplicitAuthorization));

            if (entry.requiresExplicitAuthorization
                    || peer.requiresExplicitAuthorization) {
                auth.add(entry.id);
            }
        }

        return new Result(shared, missing, auth);
    }
}
