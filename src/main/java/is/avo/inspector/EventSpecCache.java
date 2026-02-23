package is.avo.inspector;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU cache with TTL for event spec responses.
 *
 * Key format: apiKey:streamId:eventName
 * TTL: 60 seconds per entry
 * Max entries: 50 (LRU eviction when over)
 * Per-entry eviction: after 50 accesses
 * Global sweep: every 50 operations
 * Stores null responses (prevent re-fetch for unknown events)
 * Flushes all entries when branchId changes
 */
class EventSpecCache {

    static final int MAX_ENTRIES = 50;
    static final long TTL_MS = 60_000L;
    static final int PER_ENTRY_ACCESS_LIMIT = 50;
    static final int GLOBAL_SWEEP_INTERVAL = 50;

    private int globalEventCount = 0;
    @Nullable
    private String currentBranchId = null;

    // sentinel value to distinguish "cached null" from "not in cache"
    private static final CacheEntry NULL_SENTINEL = new CacheEntry(null, 0L);

    static class CacheEntry {
        @Nullable final AvoEventSpecFetchTypes.EventSpecResponse response;
        final long insertedAtMs;
        int accessCount;

        CacheEntry(@Nullable AvoEventSpecFetchTypes.EventSpecResponse response, long insertedAtMs) {
            this.response = response;
            this.insertedAtMs = insertedAtMs;
            this.accessCount = 0;
        }
    }

    // LRU map: accessOrder=true means iteration order is access order
    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<String, CacheEntry>(
            MAX_ENTRIES + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /**
     * Represents a cache lookup result. Contains whether the key was found
     * and optionally the cached response (which may be null for unknown events).
     */
    static class LookupResult {
        final boolean found;
        @Nullable final AvoEventSpecFetchTypes.EventSpecResponse response;

        LookupResult(boolean found, @Nullable AvoEventSpecFetchTypes.EventSpecResponse response) {
            this.found = found;
            this.response = response;
        }
    }

    /**
     * Get a cached entry. Returns a LookupResult indicating whether the key was found.
     * A found result with null response means the event is known to be unknown (null was cached).
     */
    LookupResult get(String key, long nowMs) {
        incrementGlobalCount(nowMs);

        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return new LookupResult(false, null);
        }

        // TTL check
        if (nowMs - entry.insertedAtMs > TTL_MS) {
            cache.remove(key);
            return new LookupResult(false, null);
        }

        // Per-entry access limit check
        entry.accessCount++;
        if (entry.accessCount >= PER_ENTRY_ACCESS_LIMIT) {
            cache.remove(key);
            return new LookupResult(false, null);
        }

        return new LookupResult(true, entry.response);
    }

    /**
     * Put an entry into the cache. Response may be null (for unknown events).
     */
    void put(String key, @Nullable AvoEventSpecFetchTypes.EventSpecResponse response, long nowMs) {
        incrementGlobalCount(nowMs);
        cache.put(key, new CacheEntry(response, nowMs));
    }

    /**
     * Check and update branchId. If branchId changes, flush the cache.
     */
    void checkBranchId(@Nullable String branchId) {
        if (currentBranchId != null && branchId != null && !currentBranchId.equals(branchId)) {
            cache.clear();
        }
        currentBranchId = branchId;
    }

    /**
     * Flush all entries from the cache.
     */
    void flush() {
        cache.clear();
    }

    /**
     * Returns the number of entries in the cache.
     */
    int size() {
        return cache.size();
    }

    /**
     * Returns the current global event count (for testing).
     */
    int getGlobalEventCount() {
        return globalEventCount;
    }

    private void incrementGlobalCount(long nowMs) {
        globalEventCount++;
        if (globalEventCount % GLOBAL_SWEEP_INTERVAL == 0) {
            sweep(nowMs);
        }
    }

    /**
     * Remove entries older than TTL or with too many accesses.
     */
    private void sweep(long nowMs) {
        cache.entrySet().removeIf(entry -> {
            CacheEntry ce = entry.getValue();
            return (nowMs - ce.insertedAtMs > TTL_MS) || (ce.accessCount >= PER_ENTRY_ACCESS_LIMIT);
        });
    }

    /**
     * Build a cache key from components.
     */
    static String buildKey(String apiKey, String streamId, String eventName) {
        return apiKey + ":" + streamId + ":" + eventName;
    }
}
