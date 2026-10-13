package com.jeevesh415.blutoothconnector.transport;

/**
 * Shared admission lock for the app's TCP-multipath and Bluetooth-L2CAP
 * temporary-file budget. Both transports must check existing reservations and
 * reserve their candidate partial while holding this same monitor.
 */
final class BulkPartialStorageLock {
    static final Object INSTANCE = new Object();

    private BulkPartialStorageLock() {}
}
