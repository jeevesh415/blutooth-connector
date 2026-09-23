package com.jeevesh415.blutoothconnector.capability;

import com.jeevesh415.blutoothconnector.protocol.Frame;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CapabilityRegistry {
    private final List<Capability> capabilities = new ArrayList<>();

    public synchronized void register(Capability capability) {
        if (capability == null) throw new IllegalArgumentException("capability");
        capabilities.removeIf(c -> c.id().equals(capability.id()));
        capabilities.add(capability);
    }

    public synchronized List<Capability> all() {
        return Collections.unmodifiableList(new ArrayList<>(capabilities));
    }

    public synchronized Capability resolve(Frame command) {
        for (Capability capability : capabilities) {
            if (capability.canHandle(command)) return capability;
        }
        return null;
    }
}
