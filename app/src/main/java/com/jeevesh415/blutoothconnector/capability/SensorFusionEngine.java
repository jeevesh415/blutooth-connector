package com.jeevesh415.blutoothconnector.capability;

import android.hardware.Sensor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * Lightweight inertial/magnetic fusion for remote sensor telemetry.
 *
 * The estimator propagates attitude with gyroscope angular velocity and
 * adaptively corrects drift with accelerometer gravity and, when present,
 * magnetometer heading. It is deliberately self-contained so it can run on
 * Phone B without adding a native dependency.
 */
public final class SensorFusionEngine {
    private static final double GRAVITY = 9.80665;
    private static final double MIN_DT = 1.0e-5;
    private static final double MAX_DT = 0.05;

    // Quaternion q = [w, x, y, z].
    private final double[] q = {1.0, 0.0, 0.0, 0.0};
    private final float[] accel = new float[3];
    private final float[] magnet = new float[3];
    private final float[] gyro = new float[3];

    private boolean haveAccel;
    private boolean haveMagnet;
    private boolean haveGyro;
    private long lastGyroTimestampNs;
    private long lastAccelTimestampNs;
    private long lastMagTimestampNs;

    public synchronized void reset() {
        Arrays.fill(q, 0.0);
        q[0] = 1.0;
        Arrays.fill(accel, 0.0f);
        Arrays.fill(magnet, 0.0f);
        Arrays.fill(gyro, 0.0f);
        haveAccel = false;
        haveMagnet = false;
        haveGyro = false;
        lastGyroTimestampNs = 0L;
        lastAccelTimestampNs = 0L;
        lastMagTimestampNs = 0L;
    }

    public synchronized void onSample(
            int sensorType,
            long timestampNs,
            float[] values) {
        if (values == null || values.length < 3) return;

        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(values, 0, accel, 0, 3);
            haveAccel = true;
            lastAccelTimestampNs = timestampNs;
            correctAttitude();
            return;
        }

        if (sensorType == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(values, 0, magnet, 0, 3);
            haveMagnet = true;
            lastMagTimestampNs = timestampNs;
            correctAttitude();
            return;
        }

        if (sensorType == Sensor.TYPE_GYROSCOPE) {
            System.arraycopy(values, 0, gyro, 0, 3);
            haveGyro = true;
            if (lastGyroTimestampNs > 0L) {
                double dt = (timestampNs - lastGyroTimestampNs) / 1_000_000_000.0;
                dt = Math.max(MIN_DT, Math.min(MAX_DT, dt));
                propagate(values, dt);
            }
            lastGyroTimestampNs = timestampNs;
        }
    }

    synchronized double[] quaternionCopy() {
        return q.clone();
    }

    synchronized double[] eulerDegreesCopy() {
        return toEuler(q);
    }

    public synchronized JSONObject snapshot() throws JSONException {
        double[] euler = toEuler(q);
        double accelNorm = haveAccel
                ? Math.sqrt(accel[0] * accel[0]
                        + accel[1] * accel[1]
                        + accel[2] * accel[2])
                : Double.NaN;

        JSONObject out = new JSONObject();
        out.put("available", haveGyro || haveAccel || haveMagnet);
        out.put("gyro", array(gyro));
        out.put("quaternion", array(q));
        out.put("eulerDeg", array(euler));
        out.put("rollDeg", euler[0]);
        out.put("pitchDeg", euler[1]);
        out.put("yawDeg", euler[2]);
        out.put("accelerationNormMps2",
                Double.isNaN(accelNorm) ? JSONObject.NULL : accelNorm);
        out.put("gravityErrorMps2",
                Double.isNaN(accelNorm) ? JSONObject.NULL : accelNorm - GRAVITY);
        out.put("usedAccelerometer", haveAccel);
        out.put("usedMagnetometer", haveMagnet);
        out.put("lastGyroTimestampNs", lastGyroTimestampNs);
        out.put("lastAccelTimestampNs", lastAccelTimestampNs);
        out.put("lastMagnetometerTimestampNs", lastMagTimestampNs);
        return out;
    }

    private void propagate(float[] omega, double dt) {
        double wx = omega[0];
        double wy = omega[1];
        double wz = omega[2];

        double magnitude = Math.sqrt(wx * wx + wy * wy + wz * wz);
        double halfTheta = 0.5 * magnitude * dt;

        double dw;
        double scale;
        if (magnitude < 1.0e-9) {
            dw = 1.0;
            scale = 0.5 * dt;
        } else {
            dw = Math.cos(halfTheta);
            scale = Math.sin(halfTheta) / magnitude;
        }

        double[] dq = {
                dw,
                wx * scale,
                wy * scale,
                wz * scale
        };
        double[] next = multiply(q, dq);
        System.arraycopy(next, 0, q, 0, 4);
        normalize(q);
    }

    /**
     * Adaptive complementary correction. During near-static conditions
     * (|a| ~= g), gravity is trusted more. During strong acceleration, the
     * correction weight is reduced so translational dynamics do not become a
     * false attitude signal.
     */
    private void correctAttitude() {
        if (!haveAccel) return;

        double ax = accel[0];
        double ay = accel[1];
        double az = accel[2];
        double norm = Math.sqrt(ax * ax + ay * ay + az * az);
        if (norm < 1.0e-6) return;

        double trust = 1.0
                - Math.min(1.0, Math.abs(norm - GRAVITY) / (0.35 * GRAVITY));
        trust = Math.max(0.0, trust);

        double currentYaw = toEuler(q)[2] * Math.PI / 180.0;
        double roll = Math.atan2(ay, az);
        double pitch = Math.atan2(-ax, Math.sqrt(ay * ay + az * az));
        double yaw = currentYaw;

        if (haveMagnet) {
            double mx = magnet[0];
            double my = magnet[1];
            double mz = magnet[2];
            double mxh = mx * Math.cos(pitch) + mz * Math.sin(pitch);
            double myh = mx * Math.sin(roll) * Math.sin(pitch)
                    + my * Math.cos(roll)
                    - mz * Math.sin(roll) * Math.cos(pitch);
            if (Math.hypot(mxh, myh) > 1.0e-6) {
                yaw = Math.atan2(-myh, mxh);
            }
        }

        double[] reference = fromEuler(roll, pitch, yaw);
        // Base correction 2%; increase to 12% when gravity is trustworthy.
        double alpha = 0.02 + 0.10 * trust;
        slerpInto(q, reference, alpha);
    }

    private static double[] fromEuler(
            double roll,
            double pitch,
            double yaw) {
        double cr = Math.cos(roll * 0.5);
        double sr = Math.sin(roll * 0.5);
        double cp = Math.cos(pitch * 0.5);
        double sp = Math.sin(pitch * 0.5);
        double cy = Math.cos(yaw * 0.5);
        double sy = Math.sin(yaw * 0.5);

        return new double[] {
                cr * cp * cy + sr * sp * sy,
                sr * cp * cy - cr * sp * sy,
                cr * sp * cy + sr * cp * sy,
                cr * cp * sy - sr * sp * cy
        };
    }

    private static double[] toEuler(double[] quaternion) {
        double w = quaternion[0];
        double x = quaternion[1];
        double y = quaternion[2];
        double z = quaternion[3];

        double sinRoll = 2.0 * (w * x + y * z);
        double cosRoll = 1.0 - 2.0 * (x * x + y * y);
        double roll = Math.atan2(sinRoll, cosRoll);

        double sinPitch = 2.0 * (w * y - z * x);
        sinPitch = Math.max(-1.0, Math.min(1.0, sinPitch));
        double pitch = Math.asin(sinPitch);

        double sinYaw = 2.0 * (w * z + x * y);
        double cosYaw = 1.0 - 2.0 * (y * y + z * z);
        double yaw = Math.atan2(sinYaw, cosYaw);

        return new double[] {
                Math.toDegrees(roll),
                Math.toDegrees(pitch),
                Math.toDegrees(yaw)
        };
    }

    private static double[] multiply(double[] a, double[] b) {
        return new double[] {
                a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
                a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
                a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
                a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
        };
    }

    private static void normalize(double[] value) {
        double norm = Math.sqrt(
                value[0] * value[0] + value[1] * value[1]
                        + value[2] * value[2] + value[3] * value[3]);
        if (norm < 1.0e-12) {
            value[0] = 1.0;
            value[1] = value[2] = value[3] = 0.0;
            return;
        }
        for (int i = 0; i < 4; i++) value[i] /= norm;
    }

    private static void slerpInto(
            double[] target,
            double[] reference,
            double alpha) {
        double dot = target[0] * reference[0]
                + target[1] * reference[1]
                + target[2] * reference[2]
                + target[3] * reference[3];

        if (dot < 0.0) {
            dot = -dot;
            reference = new double[] {
                    -reference[0], -reference[1], -reference[2], -reference[3]
            };
        }

        dot = Math.max(-1.0, Math.min(1.0, dot));
        if (dot > 0.9995) {
            for (int i = 0; i < 4; i++) {
                target[i] += alpha * (reference[i] - target[i]);
            }
            normalize(target);
            return;
        }

        double theta = Math.acos(dot);
        double sinTheta = Math.sin(theta);
        double a = Math.sin((1.0 - alpha) * theta) / sinTheta;
        double b = Math.sin(alpha * theta) / sinTheta;
        for (int i = 0; i < 4; i++) target[i] = a * target[i] + b * reference[i];
        normalize(target);
    }

    private static JSONArray array(float[] values) throws JSONException {
        JSONArray out = new JSONArray();
        for (float value : values) out.put(value);
        return out;
    }

    private static JSONArray array(double[] values) throws JSONException {
        JSONArray out = new JSONArray();
        for (double value : values) out.put(value);
        return out;
    }
}
