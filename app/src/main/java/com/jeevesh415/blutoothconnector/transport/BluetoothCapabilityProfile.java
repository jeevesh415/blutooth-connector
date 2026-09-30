package com.jeevesh415.blutoothconnector.transport;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.os.Build;
import org.json.JSONException;
import org.json.JSONObject;

/** Runtime Bluetooth capability negotiation; Android does not expose a reliable version number. */
public final class BluetoothCapabilityProfile {
    public final int apiLevel;
    public final boolean classicRfcomm, le, leL2capCoc, le2mPhy, leCodedPhy;
    public final boolean leExtendedAdvertising, lePeriodicAdvertising;
    public final String generation;

    private BluetoothCapabilityProfile(int apiLevel, boolean classicRfcomm, boolean le,
            boolean leL2capCoc, boolean le2mPhy, boolean leCodedPhy,
            boolean leExtendedAdvertising, boolean lePeriodicAdvertising) {
        this.apiLevel=apiLevel; this.classicRfcomm=classicRfcomm; this.le=le;
        this.leL2capCoc=leL2capCoc; this.le2mPhy=le2mPhy; this.leCodedPhy=leCodedPhy;
        this.leExtendedAdvertising=leExtendedAdvertising;
        this.lePeriodicAdvertising=lePeriodicAdvertising;
        this.generation=classifyGeneration(le2mPhy,leCodedPhy,leExtendedAdvertising,lePeriodicAdvertising);
    }

    @SuppressLint("MissingPermission")
    public static BluetoothCapabilityProfile fromAdapter(BluetoothAdapter a) {
        if (a==null) throw new IllegalArgumentException("Bluetooth adapter");
        boolean classic = Build.VERSION.SDK_INT >= 5;
        boolean le = Build.VERSION.SDK_INT >= 21 && a.getBluetoothLeScanner() != null;
        boolean l2cap = Build.VERSION.SDK_INT >= 29 && le;
        boolean twoM=Build.VERSION.SDK_INT>=26 && a.isLe2MPhySupported();
        boolean coded=Build.VERSION.SDK_INT>=26 && a.isLeCodedPhySupported();
        boolean extended=Build.VERSION.SDK_INT>=26 && a.isLeExtendedAdvertisingSupported();
        boolean periodic=Build.VERSION.SDK_INT>=26 && a.isLePeriodicAdvertisingSupported();
        return new BluetoothCapabilityProfile(Build.VERSION.SDK_INT,classic,le,l2cap,twoM,coded,extended,periodic);
    }

    public static BluetoothCapabilityProfile forTesting(boolean classic, boolean le, boolean l2cap,
            boolean twoM, boolean coded, boolean extended, boolean periodic) {
        return new BluetoothCapabilityProfile(0,classic,le,l2cap,twoM,coded,extended,periodic);
    }

    public static String classifyGeneration(boolean twoM, boolean coded, boolean extended, boolean periodic) {
        return (twoM||coded||extended||periodic) ? "BLE-5+" : "legacy-or-unknown";
    }

    public String preferredBulkTransport() {
        return leL2capCoc ? "bluetooth-le-l2cap" : classicRfcomm ? "bluetooth-rfcomm" : "none";
    }

    public String negotiateBulk(BluetoothCapabilityProfile peer) {
        if (peer==null) throw new IllegalArgumentException("peer");
        if (leL2capCoc && peer.leL2capCoc) return "bluetooth-le-l2cap";
        if (classicRfcomm && peer.classicRfcomm) return "bluetooth-rfcomm";
        return "none";
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("apiLevel",apiLevel).put("generation",generation)
                .put("classicRfcomm",classicRfcomm).put("le",le).put("leL2capCoc",leL2capCoc)
                .put("le2mPhy",le2mPhy).put("leCodedPhy",leCodedPhy)
                .put("leExtendedAdvertising",leExtendedAdvertising)
                .put("lePeriodicAdvertising",lePeriodicAdvertising);
    }
}