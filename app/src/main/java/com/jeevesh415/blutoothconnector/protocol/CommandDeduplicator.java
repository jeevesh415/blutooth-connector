package com.jeevesh415.blutoothconnector.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CommandDeduplicator {
    private static final int MAX_ENTRIES = 256;
    private static final long TTL_MS = 5 * 60 * 1000L;

    private final LinkedHashMap<String, Entry> cache =
            new LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true);

    private static final class Entry {
        final Frame result;
        final long storedAt;

        Entry(Frame result) {
            this.result = result;
            this.storedAt = System.currentTimeMillis();
        }
    }

    public synchronized Frame get(String requestId) {
        purge();
        Entry entry = cache.get(requestId);
        return entry == null ? null : entry.result;
    }

    public synchronized void put(String requestId, Frame result) {
        purge();
        cache.put(requestId, new Entry(result));
        while (cache.size() > MAX_ENTRIES) {
            String first = cache.keySet().iterator().next();
            cache.remove(first);
        }
    }

    private void purge() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().storedAt > TTL_MS);
    }
}
