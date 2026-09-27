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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remote sensor control plane.
 *
 * The remote peer can inspect every SensorManager sensor and control the
 * acquisition policy exposed by Android: sampling period and batching latency.
 * A max-performance command requests each stream at its fastest hardware-
 * reported period; Android and the sensor HAL remain authoritative.
 */
public final class SensorControlCapability implements Capability {
    private final SensorManager manager;
    private final Map<Integer, SensorSample> latest = new ConcurrentHashMap<>();
    private final Map<Integer, SensorEventListener> listeners = new ConcurrentHashMap<>();
    private final Map<Integer, android.hardware.TriggerEventListener> triggerListeners =
            new ConcurrentHashMap<>();
    private final SensorFusionEngine fusion = new SensorFusionEngine();
    private final Map<String, Integer> fallbackHandles = new ConcurrentHashMap<>();
    private final AtomicInteger nextFallbackHandle = new AtomicInteger(0x40000000);

    public SensorControlCapability(Context context) {
        manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) throw new IllegalStateException("Sensor service unavailable");
    }

    @Override public String id() { return "sensor.control"; }
    @Override public String version() { return "2.1"; }
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
                || "optimize".equals(op)
                || "fusion".equals(op)
                || "trigger".equals(op)
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
        } else if ("optimize".equals(operation)) {
            result = optimize();
        } else if ("fusion".equals(operation)) {
            result = fusion.snapshot();
        } else if ("trigger".equals(operation)) {
            result = trigger(command.payload);
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
                    .put("handle", handleFor(s))
                    .put("androidId", s.getId())
                    .put("type", s.getType())
                    .put("name", s.getName())
                    .put("vendor", s.getVendor())
                    .put("version", s.getVersion())
                    .put("resolution", s.getResolution())
                    .put("maxRange", s.getMaximumRange())
                    .put("minDelayUs", Math.max(0, s.getMinDelay()))
                    .put("powerMa", s.getPower())
                    .put("wakeUp", s.isWakeUpSensor())
                    .put("reportingMode", s.getReportingMode())
                    .put("dynamic", s.isDynamicSensor())
                    .put("stringType", s.getStringType())
                    .put("maxDelayUs", Math.max(0, s.getMaxDelay()))
                    .put("fifoMaxEventCount", s.getFifoMaxEventCount())
                    .put("fifoReservedEventCount", s.getFifoReservedEventCount())
                    .put("directChannelTypes", directChannelTypes(s))
                    .put("streamable",
                            s.getReportingMode() == Sensor.REPORTING_MODE_CONTINUOUS
                                    || s.getReportingMode() == Sensor.REPORTING_MODE_ON_CHANGE));
        }
        return new JSONObject()
                .put("count", sensors.length())
                .put("activeCount", listeners.size())
                .put("sensors", sensors);
    }

    private JSONArray directChannelTypes(Sensor sensor) {
        JSONArray out = new JSONArray();
        int maxRate = sensor.getHighestDirectReportRateLevel();
        if (maxRate >= SensorDirectRate.NORMAL) out.put("normal");
        if (maxRate >= SensorDirectRate.FAST) out.put("fast");
        if (maxRate >= SensorDirectRate.VERY_FAST) out.put("very_fast");
        if (sensor.isDirectChannelTypeSupported(
                android.hardware.SensorDirectChannel.TYPE_MEMORY_FILE)) {
            out.put("memory_file");
        }
        if (sensor.isDirectChannelTypeSupported(
                android.hardware.SensorDirectChannel.TYPE_HARDWARE_BUFFER)) {
            out.put("hardware_buffer");
        }
        return out;
    }

    private JSONObject configure(JSONObject p) throws Exception {
        Sensor sensor = findByHandle(p.optInt("handle", -1));
        if (sensor == null) throw new IllegalArgumentException("Unknown sensor handle");

        int minUs = Math.max(1, sensor.getMinDelay());
        int requestedUs = p.optInt("periodUs", minUs);
        int periodUs = Math.max(minUs, requestedUs);
        int maxLatencyUs = Math.max(0, p.optInt("maxReportLatencyUs", 0));

        if (sensor.getReportingMode() != Sensor.REPORTING_MODE_CONTINUOUS
                && sensor.getReportingMode() != Sensor.REPORTING_MODE_ON_CHANGE) {
            throw new IllegalArgumentException(
                    "This sensor is trigger-only; use its Android-supported trigger API");
        }

        register(sensor, periodUs, maxLatencyUs);

        return new JSONObject()
                .put("handle", handleFor(sensor))
                .put("name", sensor.getName())
                .put("requestedPeriodUs", requestedUs)
                .put("effectivePeriodUs", periodUs)
                .put("effectiveRateHz", 1_000_000.0 / periodUs)
                .put("maxReportLatencyUs", maxLatencyUs)
                .put("reportingMode", sensor.getReportingMode())
                .put("registered", true);
    }

    /**
     * Request the lowest Android-reported period for every streamable sensor.
     * The sensor HAL is still free to clamp/coalesce deliveries.
     */
    private JSONObject trigger(JSONObject p) throws Exception {
        int handle = p.optInt("handle", -1);
        Sensor sensor = findByHandle(handle);
        if (sensor == null) throw new IllegalArgumentException("Unknown sensor handle");

        int mode = sensor.getReportingMode();
        if (mode != Sensor.REPORTING_MODE_ONE_SHOT
                && mode != Sensor.REPORTING_MODE_SPECIAL_TRIGGER) {
            throw new IllegalArgumentException("Sensor is not a trigger-only sensor");
        }

        cancelTrigger(handle);
        android.hardware.TriggerEventListener listener =
                new android.hardware.TriggerEventListener() {
                    @Override public void onTrigger(android.hardware.TriggerEvent event) {
                        float[] values = new float[event.values.length];
                        System.arraycopy(event.values, 0, values, 0, event.values.length);
                        SensorSample sample = new SensorSample(
                                handleFor(event.sensor),
                                event.sensor.getType(),
                                event.timestamp,
                                -1,
                                values);
                        latest.put(handleFor(event.sensor), sample);
                        if (values.length >= 3) {
                            fusion.onSample(event.sensor.getType(), event.timestamp, values);
                        }
                        triggerListeners.remove(handleFor(event.sensor));
                    }
                };

        if (!manager.requestTriggerSensor(listener, sensor)) {
            throw new IllegalStateException("Trigger registration rejected");
        }
        triggerListeners.put(handle, listener);

        return new JSONObject()
                .put("handle", handle)
                .put("name", sensor.getName())
                .put("triggered", false)
                .put("armed", true);
    }

    private JSONObject optimize() throws Exception {
        int started = 0;
        int skipped = 0;
        for (Sensor sensor : manager.getSensorList(Sensor.TYPE_ALL)) {
            int mode = sensor.getReportingMode();
            if (mode != Sensor.REPORTING_MODE_CONTINUOUS
                    && mode != Sensor.REPORTING_MODE_ON_CHANGE) {
                skipped++;
                continue;
            }

            int periodUs = Math.max(1, sensor.getMinDelay());
            try {
                register(sensor, periodUs, 0);
                started++;
            } catch (Exception ignored) {
                skipped++;
            }
        }
        return new JSONObject()
                .put("mode", "max-performance")
                .put("requested", started)
                .put("skipped", skipped)
                .put("activeCount", listeners.size());
    }

    private void register(Sensor sensor, int periodUs, int maxLatencyUs) {
        int handle = handleFor(sensor);
        stopSensor(handle);

        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                float[] values = new float[event.values.length];
                System.arraycopy(event.values, 0, values, 0, event.values.length);
                SensorSample sample = new SensorSample(
                        handleFor(event.sensor),
                        event.sensor.getType(),
                        event.timestamp,
                        event.accuracy,
                        values);
                latest.put(handleFor(event.sensor), sample);
                if (event.values.length >= 3) {
                    fusion.onSample(
                            event.sensor.getType(),
                            event.timestamp,
                            values);
                }
            }

            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };

        boolean registered = manager.registerListener(
                listener, sensor, periodUs, maxLatencyUs);
        if (!registered) throw new IllegalStateException("Sensor registration rejected");
        listeners.put(handle, listener);
    }

    private JSONObject snapshot() throws Exception {
        JSONArray values = new JSONArray();
        long nowNs = SystemClock.elapsedRealtimeNanos();
        for (SensorSample sample : latest.values()) {
            JSONObject item = new JSONObject()
                    .put("handle", sample.id)
                    .put("type", sample.type)
                    .put("timestampNs", sample.timestampNs)
                    .put("accuracy", sample.accuracy)
                    .put("ageMs", Math.max(0,
                            (nowNs - sample.timestampNs) / 1_000_000L));
            JSONArray v = new JSONArray();
            for (float x : sample.values) v.put(x);
            item.put("values", v);
            values.put(item);
        }
        return new JSONObject()
                .put("count", values.length())
                .put("activeCount", listeners.size())
                .put("samples", values);
    }

    private JSONObject stop(JSONObject p) throws Exception {
        int handle = p.optInt("handle", -1);
        if (handle < 0) {
            java.util.HashSet<Integer> ids = new java.util.HashSet<>(listeners.keySet());
            ids.addAll(triggerListeners.keySet());
            for (Integer id : ids) stopSensor(id);
            fusion.reset();
            return new JSONObject()
                    .put("stopped", "all")
                    .put("activeCount", 0);
        }

        stopSensor(handle);
        return new JSONObject()
                .put("stopped", handle)
                .put("activeCount", listeners.size());
    }

    private void stopSensor(int id) {
        SensorEventListener listener = listeners.remove(id);
        if (listener != null) manager.unregisterListener(listener);
        cancelTrigger(id);
        latest.remove(id);
    }

    private void cancelTrigger(int id) {
        android.hardware.TriggerEventListener listener = triggerListeners.remove(id);
        if (listener == null) return;
        Sensor sensor = findByHandle(id);
        if (sensor != null) {
            manager.cancelTriggerSensor(listener, sensor);
        }
    }

    private Sensor findByHandle(int handle) {
        if (handle <= 0) return null;
        for (Sensor sensor : manager.getSensorList(Sensor.TYPE_ALL)) {
            if (handleFor(sensor) == handle) return sensor;
        }
        return null;
    }

    private int handleFor(Sensor sensor) {
        int androidId = sensor.getId();
        if (androidId > 0) return androidId;

        String key = sensor.getType() + "|" + sensor.getStringType() + "|"
                + sensor.getName() + "|" + sensor.getVendor() + "|" + sensor.getVersion();
        Integer existing = fallbackHandles.get(key);
        if (existing != null) return existing;

        int candidate = nextFallbackHandle.getAndIncrement();
        if (candidate <= 0) {
            throw new IllegalStateException("Sensor handle space exhausted");
        }
        Integer raced = fallbackHandles.putIfAbsent(key, candidate);
        return raced == null ? candidate : raced;
    }

    private static final class SensorSample {
        final int id;
        final int type;
        final long timestampNs;
        final int accuracy;
        final float[] values;

        SensorSample(int id, int type, long timestampNs, int accuracy, float[] values) {
            this.id = id;
            this.type = type;
            this.timestampNs = timestampNs;
            this.accuracy = accuracy;
            this.values = values;
        }
    }

    /**
     * Sensor#getHighestDirectReportRateLevel() returns a rate-level enum,
     * while the direct-channel type mask is exposed through the sensor API.
     * We keep the UI field conservative and only expose known rate levels.
     */
    private static final class SensorDirectRate {
        // Sensor#getHighestDirectReportRateLevel() is an ordered level:
        // STOP=0, NORMAL=1, FAST=2, VERY_FAST=3.
        static final int UNKNOWN = 0;
        static final int NORMAL = 1;
        static final int FAST = 2;
        static final int VERY_FAST = 3;

        private SensorDirectRate() {}
    }
}
