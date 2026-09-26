package com.jeevesh415.blutoothconnector.capability;

import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import org.json.JSONObject;

public final class PingCapability implements Capability {
    @Override public String id() { return "transport.ping"; }
    @Override public String version() { return "1.0"; }

    @Override public boolean canHandle(Frame command) {
        return Protocol.PING.equals(command.type);
    }

    @Override public Frame handle(Frame command) throws Exception {
        return new Frame(
                Protocol.VERSION,
                Protocol.PONG,
                command.sequence,
                System.currentTimeMillis(),
                new JSONObject().put("echo", command.payload.optLong("t0", 0)));
    }
}
