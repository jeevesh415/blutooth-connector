package com.jeevesh415.blutoothconnector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.os.IBinder;
import android.content.Intent;
import android.content.pm.ServiceInfo;

import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import com.jeevesh415.blutoothconnector.transport.BulkTransferProtocol;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;
import com.jeevesh415.blutoothconnector.transport.MultiDeviceManager;
import com.jeevesh415.blutoothconnector.transport.TcpBulkEndpoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

public final class ConnectionService extends Service {
    private static final String CHANNEL = "connection";
    private static final int NOTIFICATION_ID = 7;

    private MultiDeviceManager peers;
    private TcpBulkEndpoint bulk;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification());
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;

        bulk = new TcpBulkEndpoint();
        try {
            bulk.start(
                    new File(getFilesDir(), "transfers"),
                    new TcpBulkEndpoint.Listener() {
                        @Override public void onTransferComplete(File file) {
                            // Future: publish an EVENT to all authenticated controllers.
                        }

                        @Override public void onError(Exception error) {
                            // Future: structured diagnostic event.
                        }
                    });
        } catch (Exception ignored) {
            bulk = null;
        }

        peers = new MultiDeviceManager(adapter, new MultiDeviceManager.Listener() {
            @Override public void onConnected(DeviceSession session) {
                sendCapabilities(session);
            }

            @Override public void onFrame(DeviceSession session, Frame frame) {
                handleFrame(session, frame);
            }

            @Override public void onDisconnected(DeviceSession session, Exception error) {
                // The manager performs bounded exponential reconnect.
            }

            @Override public void onConnectError(android.bluetooth.BluetoothDevice device,
                                                 Exception error) {
                // Connection diagnostics will be surfaced by the controller UI.
            }
        });

        try { peers.startReceiver(); } catch (Exception ignored) {}
    }

    private void handleFrame(DeviceSession session, Frame frame) {
        try {
            if (Protocol.HELLO.equals(frame.type)) {
                sendCapabilities(session);
            } else if (Protocol.PING.equals(frame.type)) {
                JSONObject payload = new JSONObject()
                        .put("t0", frame.payload.optLong("t0", 0));
                session.connection.send(new Frame(
                        Protocol.VERSION,
                        Protocol.PONG,
                        frame.sequence,
                        System.currentTimeMillis(),
                        payload));
            }
        } catch (Exception ignored) {}
    }

    private void sendCapabilities(DeviceSession session) {
        try {
            JSONObject payload = new JSONObject()
                    .put("protocol", Protocol.VERSION)
                    .put("maxBluetoothPeers", MultiDeviceManager.MAX_CLASSIC_PEERS)
                    .put("transports", new JSONArray()
                            .put("bluetooth-rfcomm")
                            .put("tcp-local"));

            JSONArray endpoints = new JSONArray();
            if (bulk != null) {
                for (TcpBulkEndpoint.Endpoint endpoint : bulk.endpoints()) {
                    endpoints.put(new JSONObject()
                            .put("host", endpoint.host)
                            .put("port", endpoint.port)
                            .put("token", endpoint.tokenBase64));
                }
            }

            payload.put("bulkEndpoints", endpoints);
            payload.put("capabilities", new JSONArray()
                    .put("transport.ping")
                    .put("device.info")
                    .put("bulk.file-transfer"));

            session.connection.send(new Frame(
                    Protocol.VERSION,
                    Protocol.CAPABILITIES,
                    System.nanoTime(),
                    System.currentTimeMillis(),
                    payload));
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL,
                "Blutooth connection",
                NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification() {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Blutooth Connector")
                .setContentText("Multi-device connection service is active")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (peers != null) peers.close();
        if (bulk != null) bulk.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
