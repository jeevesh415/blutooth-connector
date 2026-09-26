package com.jeevesh415.blutoothconnector.capability;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remote sensor inspection/control plane.
 *
 * "Control" here means sensor acquisition policy: registration period and
 * max report latency. The app cannot rewrite a physical sensor's hardware
 * sampling limit or turn arbitrary sensors into actuators.
 */
public final class SensorControlCapability implements Capability {
    private final SensorManager manager;
    private final Map<Integer, SensorEvent> latest = new ConcurrentHashMap<>();
    private final Map<Integer, SensorEventListener> listeners = new ConcurrentHashMap<>();

    public SensorControlCapability(Context context) {
        manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) throw new IllegalStateException("Sensor service unavailable");
    }

    @Override public String id() { return "sensor.control"; }
    @Override public String version() { return "1.0"; }
    @Override public boolean requiresExplicitAuthorization() { return true; }

    @Override public boolean canHandle(Frame command) {
        if (!Protocol.COMMAND.equals(command.type)
                || !id().equals(command.payload.optString("capability", ""))) {
            return false;
        }
        String op = command.payload.optString("operation", "");
        return "list".equals(op)
                || "snapshot".equals(op)
                || "configure".equals(op)
                || "stop".equals(op);
    }

    @Override public synchronized Frame handle(Frame command) throws Exception {
        String operation = command.payload.optString("operation", "");
        JSONObject result;
        if ("list".equals(operation)) {
            result = listSensors();
        } else if ("snapshot".equals(operation)) {
            result = snapshot();
        } else if ("configure".equals(operation)) {
            result = configure(command.payload);
        } else {
            result = stop(command.payload);
        }
        return new Frame(
                Protocol.VERSION,
                Protocol.RESULT,
                command.sequence,
                System.currentTimeMillis(),
                result);
    }

    private JSONObject listSensors() throws Exception {
        JSONArray sensors = new JSONArray();
        List<Sensor> all = manager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : all) {
            sensors.put(new JSONObject()
                    .put("handle", s.getId())
                    .put("type", s.getType())
                    .put("name", s.getName())
                    .put("vendor", s.getVendor())
                    .put("version", s.getVersion())
                    .put("resolution", s.getResolution())
                    .put("maxRange", s.getMaximumRange())
                    .put("minDelayUs", Math.max(0, s.getMinDelay()))
                    .put("powerMa", s.getPower())
                    .put("wakeUp", s.isWakeUpSensor())
                    .put("reportingMode", s.getReportingMode()));
        }
        return new JSONObject()
                .put("count", sensors.length())
                .put("sensors", sensors);
    }

    private JSONObject configure(JSONObject p) throws Exception {
        Sensor sensor = find(p.optInt("handle", -1));
        if (sensor == null) throw new IllegalArgumentException("Unknown sensor handle");

        int requestedUs = p.optInt("periodUs", sensor.getMinDelay());
        int minUs = Math.max(1, sensor.getMinDelay());
        int periodUs = Math.max(minUs, requestedUs);

        int maxLatencyUs = Math.max(0, p.optInt("maxReportLatencyUs", 0));
        stopSensor(sensor.getId());

        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                latest.put(event.sensor.getId(), copy(event));
            }

            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };

        boolean registered = manager.registerListener(
                listener, sensor, periodUs, maxLatencyUs);
        if (!registered) throw new IllegalStateException("Sensor registration rejected");

        listeners.put(sensor.getId(), listener);

        return new JSONObject()
                .put("handle", sensor.getId())
                .put("name", sensor.getName())
                .put("requestedPeriodUs", requestedUs)
                .put("effectivePeriodUs", periodUs)
                .put("maxReportLatencyUs", maxLatencyUs)
                .put("registered", true);
    }

    private JSONObject snapshot() throws Exception {
        JSONArray values = new JSONArray();
        for (SensorEvent event : latest.values()) {
            JSONObject sample = new JSONObject()
                    .put("handle", event.sensor.getId())
                    .put("type", event.sensor.getType())
                    .put("name", event.sensor.getName())
                    .put("timestampNs", event.timestamp)
                    .put("ageMs", Math.max(0,
                            (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L));
            JSONArray v = new JSONArray();
            for (float x : event.values) v.put(x);
            sample.put("values", v);
            values.put(sample);
        }
        return new JSONObject().put("count", values.length()).put("samples", values);
    }

    private JSONObject stop(JSONObject p) {
        int handle = p.optInt("handle", -1);
        if (handle < 0) {
            for (Integer id : listeners.keySet()) stopSensor(id);
            return new JSONObject().put("stopped", "all");
        }
        stopSensor(handle);
        return new JSONObject().put("stopped", handle);
    }

    private void stopSensor(int id) {
        SensorEventListener listener = listeners.remove(id);
        if (listener != null) manager.unregisterListener(listener);
        latest.remove(id);
    }

    private Sensor find(int id) {
        for (Sensor sensor : manager.getSensorList(Sensor.TYPE_ALL)) {
            if (sensor.getId() == id) return sensor;
        }
        return null;
    }

    private static SensorEvent copy(SensorEvent source) {
        SensorEvent copy = new SensorEvent(source.values.length);
        copy.sensor = source.sensor;
        copy.timestamp = source.timestamp;
        copy.accuracy = source.accuracy;
        System.arraycopy(source.values, 0, copy.values, 0, source.values.length);
        return copy;
    }
}
