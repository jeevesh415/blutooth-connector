package com.jeevesh415.blutoothconnector.capability;

import com.jeevesh415.blutoothconnector.protocol.Frame;

public interface Capability {
    String id();
    String version();
    boolean canHandle(Frame command);
    Frame handle(Frame command) throws Exception;
}
