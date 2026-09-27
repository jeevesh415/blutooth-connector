package com.jeevesh415.blutoothconnector.capability;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;

import org.json.JSONObject;

public final class AppControlCapability implements Capability {
    private final Context context;

    public AppControlCapability(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public String id() { return "app.control"; }
    @Override public String version() { return "1.0"; }
    @Override public boolean requiresExplicitAuthorization() { return true; }

    @Override public boolean canHandle(Frame command) {
        return Protocol.COMMAND.equals(command.type)
                && "launch".equals(command.payload.optString("operation", ""));
    }

    @Override public Frame handle(Frame command) throws Exception {
        String packageName = command.payload.optString("packageName", "");
        if (!packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
                || packageName.length() > 255) {
            throw new IllegalArgumentException("Invalid packageName");
        }

        PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(packageName);
        if (launch == null) {
            throw new IllegalArgumentException("No launchable activity for package");
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);

        return new Frame(
                Protocol.VERSION,
                Protocol.RESULT,
                command.sequence,
                System.currentTimeMillis(),
                new JSONObject()
                        .put("launched", true)
                        .put("packageName", packageName));
    }
}