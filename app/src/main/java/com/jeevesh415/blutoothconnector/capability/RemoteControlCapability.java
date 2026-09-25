package com.jeevesh415.blutoothconnector.capability;

import android.content.Context;

import com.jeevesh415.blutoothconnector.control.RemoteInputAccessibilityService;
import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;

import org.json.JSONObject;

public final class RemoteControlCapability implements Capability {
    private final Context context;

    public RemoteControlCapability(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public String id() { return "remote.control"; }
    @Override public String version() { return "2.0"; }
    @Override public boolean requiresExplicitAuthorization() { return true; }

    @Override public boolean canHandle(Frame command) {
        if (!Protocol.COMMAND.equals(command.type)) return false;
        String operation = command.payload.optString("operation", "");
        return "input".equals(operation) || "node".equals(operation);
    }

    @Override public Frame handle(Frame command) throws Exception {
        RemoteInputAccessibilityService service =
                RemoteInputAccessibilityService.instance();
        if (service == null) {
            throw new IllegalStateException("Accessibility control service is not enabled");
        }

        String operation = command.payload.optString("operation", "");
        boolean ok;
        if ("input".equals(operation)) {
            JSONObject input = command.payload.optJSONObject("input");
            if (input == null) throw new IllegalArgumentException("input object is required");
            ok = service.execute(input);
        } else {
            JSONObject node = command.payload.optJSONObject("node");
            if (node == null) throw new IllegalArgumentException("node object is required");
            ok = service.executeNode(node);
        }

        return new Frame(
                Protocol.VERSION,
                Protocol.RESULT,
                command.sequence,
                System.currentTimeMillis(),
                new JSONObject().put("accepted", ok));
    }
}