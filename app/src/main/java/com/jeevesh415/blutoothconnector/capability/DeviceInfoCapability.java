package com.jeevesh415.blutoothconnector.capability;

import android.os.Build;

import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;

import org.json.JSONObject;

public final class DeviceInfoCapability implements Capability {
    @Override public String id() { return "device.info"; }
    @Override public String version() { return "1.0"; }

    @Override public boolean canHandle(Frame command) {
        return Protocol.COMMAND.equals(command.type)
                && "get".equals(command.payload.optString("operation", ""));
    }

    @Override public Frame handle(Frame command) throws Exception {
        JSONObject result = new JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("androidApi", Build.VERSION.SDK_INT)
                .put("product", Build.PRODUCT);
        return new Frame(
                Protocol.VERSION,
                Protocol.RESULT,
                command.sequence,
                System.currentTimeMillis(),
                result);
    }
}
