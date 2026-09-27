package com.jeevesh415.blutoothconnector.capability;

import com.jeevesh415.blutoothconnector.control.RemoteInputAccessibilityService;
import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;

public final class UiInspectCapability implements Capability {
    @Override public String id() { return "ui.inspect"; }
    @Override public String version() { return "1.0"; }
    @Override public boolean requiresExplicitAuthorization() { return true; }

    @Override public boolean canHandle(Frame command) {
        return Protocol.COMMAND.equals(command.type)
                && "inspect".equals(command.payload.optString("operation", ""));
    }

    @Override public Frame handle(Frame command) throws Exception {
        RemoteInputAccessibilityService service =
                RemoteInputAccessibilityService.instance();
        if (service == null) {
            throw new IllegalStateException("Accessibility service is not enabled");
        }

        int maxNodes = Math.max(
                1,
                Math.min(100, command.payload.optInt("maxNodes", 60)));

        return new Frame(
                Protocol.VERSION,
                Protocol.RESULT,
                command.sequence,
                System.currentTimeMillis(),
                service.inspectTree(maxNodes));
    }
}
