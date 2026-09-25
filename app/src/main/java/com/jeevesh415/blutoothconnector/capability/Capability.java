package com.jeevesh415.blutoothconnector.capability;

import com.jeevesh415.blutoothconnector.protocol.Frame;

public interface Capability {
    String id();
    String version();
    boolean canHandle(Frame command);
    Frame handle(Frame command) throws Exception;

    /**
     * Capabilities that can mutate or expose sensitive device state must require
     * a separate user-granted peer authorization in addition to transport auth.
     */
    default boolean requiresExplicitAuthorization() {
        return false;
    }
}
