package com.jeevesh415.blutoothconnector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.IBinder;
import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import com.jeevesh415.blutoothconnector.transport.BluetoothTransport;
import com.jeevesh415.blutoothconnector.transport.FramedConnection;
import java.util.concurrent.atomic.AtomicLong;

public final class ConnectionService extends Service {
    private static final String CHANNEL = "connection";
    private static final int NOTIFICATION_ID = 7;
    private final AtomicLong sequence = new AtomicLong();
    private BluetoothTransport transport;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification());

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;

        transport = new BluetoothTransport(adapter, new BluetoothTransport.Listener() {
            @Override public void onConnected(android.bluetooth.BluetoothSocket socket) {
                try {
                    FramedConnection connection = new FramedConnection(
                            socket.getInputStream(), socket.getOutputStream(),
                            new FramedConnection.Listener() {
                                @Override public void onFrame(Frame frame) {
                                    handleFrame(connection, frame);
                                }
                                @Override public void onClosed(Exception error) {}
                            });
                    connection.startReader();
                    connection.send(new Frame(
                            Protocol.VERSION, Protocol.HELLO,
                            sequence.incrementAndGet(), System.currentTimeMillis(),
                            new org.json.JSONObject().put("role", "receiver")));
                } catch (Exception ignored) {}
            }

            @Override public void onError(Exception error) {}
        });

        try { transport.listen(); } catch (Exception ignored) {}
    }

    private void handleFrame(FramedConnection connection, Frame frame) {
        try {
            if (Protocol.HELLO.equals(frame.type)) {
                connection.send(new Frame(
                        Protocol.VERSION, Protocol.CAPABILITIES,
                        sequence.incrementAndGet(), System.currentTimeMillis(),
                        new org.json.JSONObject()
                                .put("protocol", Protocol.VERSION)
                                .put("capabilities", new org.json.JSONArray()
                                        .put("transport.ping")
                                        .put("device.info"))));
            } else if (Protocol.PING.equals(frame.type)) {
                connection.send(new Frame(
                        Protocol.VERSION, Protocol.PONG,
                        sequence.incrementAndGet(), System.currentTimeMillis(),
                        new org.json.JSONObject()
                                .put("echo", frame.payload.optLong("t0", 0))));
            }
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Blutooth connection", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification() {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Blutooth Connector")
                .setContentText("Bluetooth receiver is active")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        if (transport != null) transport.close();
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
