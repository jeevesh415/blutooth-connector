package com.jeevesh415.blutoothconnector;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 100;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        requestBluetoothPermissions();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Blutooth Connector");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("Bluetooth control substrate");
        status.setTextSize(16);
        status.setPadding(0, 32, 0, 32);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        Button inspect = new Button(this);
        inspect.setText("Inspect paired devices");
        inspect.setOnClickListener(v -> inspectBluetooth());
        root.addView(inspect, new LinearLayout.LayoutParams(-1, -2));

        Button listen = new Button(this);
        listen.setText("Start receiver");
        listen.setOnClickListener(v -> startReceiver());
        root.addView(listen, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, REQUEST_BLUETOOTH);
        } else inspectBluetooth();
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void inspectBluetooth() {
        if (!hasConnectPermission()) {
            status.setText("Bluetooth permission is required.");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            status.setText("Bluetooth is unavailable.");
            return;
        }
        if (!adapter.isEnabled()) {
            status.setText("Bluetooth is disabled. Enable it and try again.");
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        StringBuilder out = new StringBuilder("Bluetooth ready.\n\nPaired devices:\n");
        Set<BluetoothDevice> devices = adapter.getBondedDevices();
        if (devices.isEmpty()) out.append("None");
        else for (BluetoothDevice d : devices) {
            out.append("• ").append(d.getName()).append("\n")
               .append("  ").append(d.getAddress()).append("\n");
        }
        status.setText(out);
    }

    private void startReceiver() {
        if (!hasConnectPermission()) {
            status.setText("Bluetooth permission is required.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(new Intent(this, ConnectionService.class));
        } else {
            startService(new Intent(this, ConnectionService.class));
        }
        status.setText("Receiver service started. Keep this device paired and ready.");
    }
}
