package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BluetoothCapabilityProfileTest {
    @Test public void classifiesBle5Capabilities() {
        assertEquals("BLE-5+", BluetoothCapabilityProfile.classifyGeneration(true,false,false,false));
        assertEquals("BLE-5+", BluetoothCapabilityProfile.classifyGeneration(false,false,true,false));
    }
    @Test public void fallsBackToClassic() {
        BluetoothCapabilityProfile a=BluetoothCapabilityProfile.forTesting(true,true,false,false,false,false,false);
        BluetoothCapabilityProfile b=BluetoothCapabilityProfile.forTesting(true,true,false,false,false,false,false);
        assertEquals("bluetooth-rfcomm",a.negotiateBulk(b));
        assertEquals("bluetooth-rfcomm",a.preferredBulkTransport());
    }
    @Test public void prefersL2cap() {
        BluetoothCapabilityProfile a=BluetoothCapabilityProfile.forTesting(true,true,true,true,true,true,true);
        BluetoothCapabilityProfile b=BluetoothCapabilityProfile.forTesting(true,true,true,false,true,true,false);
        assertEquals("bluetooth-le-l2cap",a.negotiateBulk(b));
        assertTrue(a.le2mPhy); assertFalse(b.le2mPhy);
    }
    @Test public void rejectsNoCommonPlane() {
        BluetoothCapabilityProfile a=BluetoothCapabilityProfile.forTesting(false,true,true,false,false,false,false);
        BluetoothCapabilityProfile b=BluetoothCapabilityProfile.forTesting(true,false,false,false,false,false,false);
        assertEquals("none",a.negotiateBulk(b));
    }
}