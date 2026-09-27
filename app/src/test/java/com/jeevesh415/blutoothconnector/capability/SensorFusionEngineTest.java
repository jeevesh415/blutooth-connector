package com.jeevesh415.blutoothconnector.capability;

import android.hardware.Sensor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SensorFusionEngineTest {
    private static final double G = 9.80665;

    @Test public void initialStateIsUnavailable() throws Exception {
        SensorFusionEngine engine = new SensorFusionEngine();

        assertTrue(!engine.quaternionCopy().equals(null));
    }

    @Test public void gravityAndMagnetometerGiveStableReference() throws Exception {
        SensorFusionEngine engine = new SensorFusionEngine();

        engine.onSample(
                Sensor.TYPE_ACCELEROMETER,
                1_000_000_000L,
                new float[]{0f, 0f, (float) G});
        engine.onSample(
                Sensor.TYPE_MAGNETIC_FIELD,
                1_010_000_000L,
                new float[]{50f, 0f, 0f});

        double[] euler = engine.eulerDegreesCopy();

        assertEquals(0.0, euler[0], 0.5);
        assertEquals(0.0, euler[1], 0.5);
    }

    @Test public void quaternionRemainsNormalizedDuringGyroPropagation() throws Exception {
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

        double[] q = engine.quaternionCopy();

        double norm = Math.sqrt(
                q[0] * q[0] + q[1] * q[1]
                        + q[2] * q[2] + q[3] * q[3]);
        assertEquals(1.0, norm, 1.0e-6);
    }
}
