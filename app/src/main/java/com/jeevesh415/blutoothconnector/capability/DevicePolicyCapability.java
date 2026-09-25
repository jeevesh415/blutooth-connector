package com.jeevesh415.blutoothconnector.capability;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

import com.jeevesh415.blutoothconnector.admin.BlutoothDeviceAdminReceiver;
import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;

import org.json.JSONObject;

public final class DevicePolicyCapability implements Capability {
    private final Context context;

    public DevicePolicyCapability(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public String id() { return "device.policy"; }
    @Override public String version() { return "1.0"; }
    @Override public boolean requiresExplicitAuthorization() { return true; }

    @Override public boolean canHandle(Frame command) {
        if (!Protocol.COMMAND.equals(command.type)) return false;
        String operation = command.payload.optString("operation", "");
        return "status".equals(operation)
                || "lock".equals(operation)
                || "reboot".equals(operation);
    }

    @Override public Frame handle(Frame command) throws Exception {
        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) throw new IllegalStateException("Device policy manager unavailable");

        ComponentName admin =
                new ComponentName(context, BlutoothDeviceAdminReceiver.class);
        boolean active = dpm.isAdminActive(admin);
        boolean owner = dpm.isDeviceOwnerApp(context.getPackageName());

        String operation = command.payload.optString("operation", "");
        if ("status".equals(operation)) {
            return result(command, new JSONObject()
                    .put("adminActive", active)
                    .put("deviceOwner", owner));
        }

        if ("lock".equals(operation)) {
            if (!active) {
                throw new SecurityException("Device admin is not active");
            }
            dpm.lockNow();
            return result(command, new JSONObject().put("locked", true));
        }

        if (!owner) {
            throw new SecurityException("Reboot requires device-owner provisioning");
        }
        dpm.reboot(admin);
        return result(command, new JSONObject().put("rebootRequested", true));
    }

    private Frame result(Frame command, JSONObject payload) {
        return new Frame(
                Protocol.VERSION,
                Protocol.RESULT,
                command.sequence,
                System.currentTimeMillis(),
                payload);
    }
}