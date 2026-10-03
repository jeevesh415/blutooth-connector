package com.jeevesh415.blutoothconnector.control;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class BulkTransferAuthorizationTest {
    @Test
    public void nullContextFailsClosed() {
        assertFalse(BulkTransferAuthorization.isAuthorized(
                null, "00:11:22:33:44:55"));
    }

    @Test
    public void invalidPeerAddressFailsClosed() {
        assertFalse(BulkTransferAuthorization.isAuthorized(
                null, "not-a-bluetooth-address"));
    }
}
