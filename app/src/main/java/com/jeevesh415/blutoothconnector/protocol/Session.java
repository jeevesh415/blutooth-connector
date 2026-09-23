package com.jeevesh415.blutoothconnector.protocol;

import org.json.JSONObject;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Session {
    public enum State { NEW, CHALLENGING, AUTHENTICATED, CLOSED }

    private final SecureRandom random = new SecureRandom();
    private State state = State.NEW;
    private byte[] localNonce;
    private byte[] remoteNonce;

    public State state() { return state; }

    public Frame hello(long seq, String deviceName) throws Exception {
        JSONObject p = new JSONObject();
        p.put("name", deviceName);
        p.put("nonce", randomNonce());
        state = State.CHALLENGING;
        return new Frame(Protocol.VERSION, Protocol.HELLO, seq,
                System.currentTimeMillis(), p);
    }

    public void acceptRemoteNonce(Frame hello) {
        remoteNonce = hello.payload.optString("nonce", "").getBytes();
    }

    public Frame auth(long seq, String pairingSecret) throws Exception {
        if (remoteNonce == null) throw new IllegalStateException("No remote nonce");
        String material = pairingSecret + ":" + new String(remoteNonce);
        JSONObject p = new JSONObject();
        p.put("proof", hmac(pairingSecret, material));
        state = State.AUTHENTICATED;
        return new Frame(Protocol.VERSION, Protocol.AUTH, seq,
                System.currentTimeMillis(), p);
    }

    public boolean verifyAuth(Frame frame, String pairingSecret) throws Exception {
        String expected = hmac(pairingSecret,
                pairingSecret + ":" + new String(localNonce == null ? new byte[0] : localNonce));
        boolean ok = expected.equals(frame.payload.optString("proof", ""));
        if (ok) state = State.AUTHENTICATED;
        return ok;
    }

    private String randomNonce() {
        localNonce = new byte[32];
        random.nextBytes(localNonce);
        return java.util.Base64.getEncoder().encodeToString(localNonce);
    }

    private static String hmac(String secret, String material) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
        byte[] out = mac.doFinal(material.getBytes());
        return java.util.Base64.getEncoder().encodeToString(out);
    }
}
