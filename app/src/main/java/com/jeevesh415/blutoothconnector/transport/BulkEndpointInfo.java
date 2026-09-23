package com.jeevesh415.blutoothconnector.transport;

import org.json.JSONObject;

public final class BulkEndpointInfo {
    public final String host;
    public final int port;
    public final String tokenBase64;
    public final String transport;

    public BulkEndpointInfo(String host, int port, String tokenBase64) {
        this(host, port, tokenBase64, "tcp-local");
    }

    public BulkEndpointInfo(String host, int port, String tokenBase64, String transport) {
        this.host = host;
        this.port = port;
        this.tokenBase64 = tokenBase64;
        this.transport = transport == null ? "tcp-local" : transport;
    }

    public static BulkEndpointInfo fromJson(JSONObject json) {
        return new BulkEndpointInfo(
                json.optString("host", ""),
                json.optInt("port", -1),
                json.optString("token", ""),
                json.optString("transport", "tcp-local"));
    }

    public JSONObject toJson() throws org.json.JSONException {
        return new JSONObject()
                .put("host", host)
                .put("port", port)
                .put("token", tokenBase64)
                .put("transport", transport);
    }
}
