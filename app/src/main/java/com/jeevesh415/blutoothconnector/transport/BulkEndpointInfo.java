package com.jeevesh415.blutoothconnector.transport;

import org.json.JSONObject;

public final class BulkEndpointInfo {
    public final String host;
    public final int port;
    public final String tokenBase64;

    public BulkEndpointInfo(String host, int port, String tokenBase64) {
        this.host = host;
        this.port = port;
        this.tokenBase64 = tokenBase64;
    }

    public static BulkEndpointInfo fromJson(JSONObject json) {
        return new BulkEndpointInfo(
                json.optString("host", ""),
                json.optInt("port", -1),
                json.optString("token", ""));
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("host", host)
                .put("port", port)
                .put("token", tokenBase64);
    }
}
