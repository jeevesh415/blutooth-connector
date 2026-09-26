package com.jeevesh415.blutoothconnector.protocol;

import org.json.JSONException;
import org.json.JSONObject;

public final class Frame {
    public final int version;
    public final String type;
    public final long sequence;
    public final long timestampMs;
    public final JSONObject payload;

    public Frame(int version, String type, long sequence, long timestampMs, JSONObject payload) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("Frame type is required");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("Frame sequence must be non-negative");
        }
        this.version = version;
        this.type = type;
        this.sequence = sequence;
        this.timestampMs = timestampMs;
        this.payload = payload == null ? new JSONObject() : payload;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("v", version);
        root.put("type", type);
        root.put("seq", sequence);
        root.put("ts", timestampMs);
        root.put("payload", payload);
        return root;
    }

    public byte[] toBytes() throws JSONException {
        return toJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Frame fromBytes(byte[] bytes) throws JSONException {
        if (bytes == null || bytes.length == 0) {
            throw new JSONException("Empty frame");
        }
        JSONObject root = new JSONObject(
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        int version = root.getInt("v");
        String type = root.getString("type");
        long seq = root.getLong("seq");
        long ts = root.getLong("ts");
        JSONObject payload = root.optJSONObject("payload");
        return new Frame(version, type, seq, ts, payload);
    }
}
