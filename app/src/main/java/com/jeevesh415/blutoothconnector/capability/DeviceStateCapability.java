package com.jeevesh415.blutoothconnector.capability;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import com.jeevesh415.blutoothconnector.control.RemoteInputAccessibilityService;
import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;

import org.json.JSONArray;
import org.json.JSONObject;

public final class DeviceStateCapability implements Capability {
    private final Context context;

    public DeviceStateCapability(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public String id() { return "device.state"; }
    @Override public String version() { return "1.0"; }

    @Override public boolean canHandle(Frame command) {
        return Protocol.COMMAND.equals(command.type)
                && "get".equals(command.payload.optString("operation", ""));
    }

    @Override public Frame handle(Frame command) throws Exception {
        BatteryManager battery = (BatteryManager)
                context.getSystemService(Context.BATTERY_SERVICE);
        PowerManager power = (PowerManager)
                context.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager)
                context.getSystemService(Context.KEYGUARD_SERVICE);

        JSONObject result = new JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("androidApi", Build.VERSION.SDK_INT)
                .put("batteryPercent", batteryPercent(battery))
                .put("charging", batteryCharging())
                .put("interactive", power != null && power.isInteractive())
                .put("keyguardLocked", keyguard != null && keyguard.isKeyguardLocked())
                .put("accessibilityControlReady",
                        RemoteInputAccessibilityService.instance() != null);

        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        JSONArray transports = new JSONArray();
        if (cm != null) {
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps =
                    active == null ? null : cm.getNetworkCapabilities(active);
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.put("wifi");
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.put("bluetooth");
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.put("cellular");
                if (Build.VERSION.SDK_INT >= 29
                        && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) {
                    transports.put("wifi-aware");
                }
                result.put("networkValidated",
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            }
        }
        result.put("activeTransports", transports);

        return new Frame(
                Protocol.VERSION,
                Protocol.RESULT,
                command.sequence,
                System.currentTimeMillis(),
                result);
    }

    private int batteryPercent(BatteryManager battery) {
        if (battery == null || Build.VERSION.SDK_INT < 21) return -1;
        int value = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return value < 0 ? -1 : Math.min(100, value);
    }

    private boolean batteryCharging() {
        Intent battery = context.registerReceiver(
                null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int state = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return state == BatteryManager.BATTERY_STATUS_CHARGING
                || state == BatteryManager.BATTERY_STATUS_FULL;
    }
}
