package com.jeevesh415.blutoothconnector.transport;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort Bluetooth LE link tuner.
 *
 * The Android Bluetooth stack and controller remain authoritative: the app
 * cannot force a PHY or bitrate that either side does not support. When the
 * platform exposes the knobs, this controller asks for 2M LE PHY and high
 * connection priority for bulk transfers, then returns the link to balanced
 * priority when the transfer ends.
 */
public final class BluetoothPerformanceController implements AutoCloseable {
    private final Context context;
    private final Map<String, BluetoothGatt> gatts =
            new ConcurrentHashMap<>();

    public BluetoothPerformanceController(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        this.context = context.getApplicationContext();
    }

    @SuppressLint("MissingPermission")
    public void prepare(BluetoothDevice device) {
        if (device == null || Build.VERSION.SDK_INT < 26) return;
        String address = device.getAddress();
        if (address == null || gatts.containsKey(address)) return;

        try {
            BluetoothGatt gatt = device.connectGatt(
                    context,
                    false,
                    new BluetoothGattCallback() {
                        @Override
                        public void onConnectionStateChange(
                                BluetoothGatt gatt,
                                int status,
                                int newState) {
                            if (newState == BluetoothProfile.STATE_CONNECTED
                                    && status == BluetoothGatt.GATT_SUCCESS) {
                                tune(gatt, true);
                            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                gatts.remove(address, gatt);
                                try {
                                    gatt.close();
                                } catch (Exception ignored) {}
                            }
                        }

                        @Override
                        public void onPhyUpdate(
                                BluetoothGatt gatt,
                                int txPhy,
                                int rxPhy,
                                int status) {
                            // The controller reports the actual PHY. No retry
                            // loop is used because the controller may reject
                            // the request or the peer may prefer another PHY.
                        }
                    });

            if (gatt != null) {
                BluetoothGatt previous = gatts.putIfAbsent(address, gatt);
                if (previous != null) {
                    try {
                        gatt.close();
                    } catch (Exception ignored) {}
                }
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // BLE GATT is an optimization path. RFCOMM/L2CAP remain usable.
        }
    }

    @SuppressLint("MissingPermission")
    public void beginHighThroughput(BluetoothDevice device) {
        if (device == null) return;
        prepare(device);
        BluetoothGatt gatt = gatts.get(device.getAddress());
        if (gatt != null) tune(gatt, true);
    }

    @SuppressLint("MissingPermission")
    public void endHighThroughput(BluetoothDevice device) {
        if (device == null) return;
        BluetoothGatt gatt = gatts.get(device.getAddress());
        if (gatt == null) return;

        try {
            if (Build.VERSION.SDK_INT >= 21) {
                gatt.requestConnectionPriority(
                        BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
            }
        } catch (SecurityException | IllegalStateException ignored) {}
    }

    @SuppressLint("MissingPermission")
    private void tune(BluetoothGatt gatt, boolean highThroughput) {
        try {
            if (highThroughput && Build.VERSION.SDK_INT >= 21) {
                gatt.requestConnectionPriority(
                        BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            }
            if (highThroughput && Build.VERSION.SDK_INT >= 26) {
                gatt.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED);
                gatt.readPhy();
            }
        } catch (SecurityException | IllegalStateException ignored) {}
    }

    @SuppressLint("MissingPermission")
    public void closeDevice(BluetoothDevice device) {
        if (device == null) return;
        BluetoothGatt gatt = gatts.remove(device.getAddress());
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {}
            try {
                gatt.close();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void close() {
        for (BluetoothGatt gatt : gatts.values()) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {}
            try {
                gatt.close();
            } catch (Exception ignored) {}
        }
        gatts.clear();
    }
}
