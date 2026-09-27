package com.jeevesh415.blutoothconnector.capability;

import android.hardware.Sensor;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SensorFusionEngineTest {
    private static final double G = 9.80665;

    @Test public void initialStateIsUnavailable() {
        SensorFusionEngine engine = new SensorFusionEngine();

        JSONObject snapshot = engine.snapshot();

        assertTrue(!snapshot.optBoolean("available", true));
    }

    @Test public void gravityAndMagnetometerGiveStableReference() {
        SensorFusionEngine engine = new SensorFusionEngine();

        engine.onSample(
                Sensor.TYPE_ACCELEROMETER,
                1_000_000_000L,
                new float[]{0f, 0f, (float) G});
        engine.onSample(
                Sensor.TYPE_MAGNETIC_FIELD,
                1_010_000_000L,
                new float[]{50f, 0f, 0f});

        JSONObject snapshot = engine.snapshot();

        assertTrue(snapshot.optBoolean("available", false));
        assertEquals(0.0, snapshot.optDouble("rollDeg"), 0.5);
        assertEquals(0.0, snapshot.optDouble("pitchDeg"), 0.5);
    }

    @Test public void quaternionRemainsNormalizedDuringGyroPropagation() {
        SensorFusionEngine engine = new SensorFusionEngine();

        engine.onSample(
                Sensor.TYPE_GYROSCOPE,
                1_000_000_000L,
                new float[]{0f, 0f, 1f});

        for (int i = 1; i <= 20; i++) {
            engine.onSample(
                    Sensor.TYPE_GYROSCOPE,
                    1_000_000_000L + i * 10_000_000L,
                    new float[]{0f, 0f, 1f});
        }

        JSONObject snapshot = engine.snapshot();
        double[] q = new double[4];
        org.json.JSONArray values = snapshot.optJSONArray("quaternion");
        for (int i = 0; i < 4; i++) q[i] = values.optDouble(i);

        double norm = Math.sqrt(
                q[0] * q[0] + q[1] * q[1]
                        + q[2] * q[2] + q[3] * q[3]);
        assertEquals(1.0, norm, 1.0e-6);
    }
}
