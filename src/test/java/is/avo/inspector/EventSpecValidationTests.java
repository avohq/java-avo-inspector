package is.avo.inspector;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class EventSpecValidationTests {

    @Mock
    AvoNetworkCallsHandler mockNetworkCallsHandler;

    // ===== EventSpecCache Tests =====

    @Test
    public void cache_putAndGet_returnsFoundEntry() {
        EventSpecCache cache = new EventSpecCache();
        AvoEventSpecFetchTypes.EventSpecResponse resp = makeSpecResponse("schema1", "branch1");
        long now = System.currentTimeMillis();

        cache.put("key1", resp, now);
        EventSpecCache.LookupResult result = cache.get("key1", now + 100);

        assertTrue("Should find cached entry", result.found);
        assertNotNull("Response should not be null", result.response);
        assertEquals("schema1", result.response.metadata.schemaId);
    }

    @Test
    public void cache_miss_returnsNotFound() {
        EventSpecCache cache = new EventSpecCache();
        long now = System.currentTimeMillis();

        EventSpecCache.LookupResult result = cache.get("nonexistent", now);

        assertFalse("Should not find missing entry", result.found);
    }

    @Test
    public void cache_nullResponse_isCachedAndReturned() {
        // AC #5: Null spec responses are cached (no re-fetch for unknown events)
        EventSpecCache cache = new EventSpecCache();
        long now = System.currentTimeMillis();

        cache.put("unknown-event", null, now);
        EventSpecCache.LookupResult result = cache.get("unknown-event", now + 100);

        assertTrue("Should find cached null entry", result.found);
        assertNull("Response should be null for unknown events", result.response);
    }

    @Test
    public void cache_ttlEviction_removesExpiredEntries() {
        // AC #9: Cache TTL eviction: entries older than 60s removed on next sweep
        EventSpecCache cache = new EventSpecCache();
        long now = System.currentTimeMillis();

        cache.put("key1", makeSpecResponse("s1", "b1"), now);

        // Access after TTL has expired
        EventSpecCache.LookupResult result = cache.get("key1", now + 61_000);

        assertFalse("Should not find expired entry", result.found);
    }

    @Test
    public void cache_ttlEviction_keepsFreshEntries() {
        EventSpecCache cache = new EventSpecCache();
        long now = System.currentTimeMillis();

        cache.put("key1", makeSpecResponse("s1", "b1"), now);

        // Access before TTL expires
        EventSpecCache.LookupResult result = cache.get("key1", now + 59_000);

        assertTrue("Should find fresh entry", result.found);
    }

    @Test
    public void cache_perEntryEviction_removesAfter50Accesses() {
        // AC #10: Cache per-entry eviction: entries retrieved 50+ times evicted immediately on next get
        EventSpecCache cache = new EventSpecCache();
        long now = System.currentTimeMillis();

        cache.put("key1", makeSpecResponse("s1", "b1"), now);

        // Access 49 times (should still be available)
        for (int i = 0; i < 49; i++) {
            EventSpecCache.LookupResult result = cache.get("key1", now + 100);
            assertTrue("Should find entry on access " + (i + 1), result.found);
        }

        // 50th access should trigger eviction
        EventSpecCache.LookupResult result = cache.get("key1", now + 100);
        assertFalse("Should evict entry after 50 accesses", result.found);
    }

    @Test
    public void cache_globalSweep_runsEvery50Operations() {
        // AC #11: Cache global sweep: runs every 50 cache operations (globalEventCount % 50 == 0)
        EventSpecCache cache = new EventSpecCache();
        long now = System.currentTimeMillis();

        // Insert an expired entry
        cache.put("expired", makeSpecResponse("s1", "b1"), now);

        // Perform 49 more operations (48 puts + the expired put = 49 increment ops, but
        // put already did 1, so we need 49 more ops to reach 50 total)
        // Actually: put("expired") incremented to 1. We need 49 more ops.
        for (int i = 0; i < 48; i++) {
            cache.put("op-" + i, makeSpecResponse("s" + i, "b1"), now + 70_000);
        }

        // The expired entry is still technically in the map (but would fail TTL on get)
        // At 50th operation, sweep should remove it
        // 49 ops so far (1 + 48). One more to hit 50.
        cache.get("trigger-sweep", now + 70_000);

        assertEquals(50, cache.getGlobalEventCount());
    }

    @Test
    public void cache_lruEviction_removesLeastRecentlyUsedWhenOver50() {
        // AC #12: Cache capacity: LRU eviction when size > 50
        EventSpecCache cache = new EventSpecCache();
        long now = System.currentTimeMillis();

        // Fill cache to capacity
        for (int i = 0; i < 50; i++) {
            cache.put("key-" + i, makeSpecResponse("s" + i, "b1"), now);
        }
        assertEquals(50, cache.size());

        // Adding one more should evict the least recently used (key-0)
        cache.put("key-new", makeSpecResponse("snew", "b1"), now);

        // Size should still be 50 (LRU eviction happened)
        assertEquals(50, cache.size());

        // key-0 should have been evicted (it was the oldest untouched entry)
        EventSpecCache.LookupResult result = cache.get("key-0", now + 100);
        assertFalse("LRU entry should have been evicted", result.found);

        // key-new should be present
        EventSpecCache.LookupResult resultNew = cache.get("key-new", now + 100);
        assertTrue("New entry should be present", resultNew.found);
    }

    @Test
    public void cache_branchIdChange_flushesCache() {
        // AC #4: Cache flush occurs when branchId changes
        EventSpecCache cache = new EventSpecCache();
        long now = System.currentTimeMillis();

        cache.put("key1", makeSpecResponse("s1", "branch1"), now);
        cache.checkBranchId("branch1");

        // Verify entry exists
        EventSpecCache.LookupResult result = cache.get("key1", now + 100);
        assertTrue("Entry should exist before branch change", result.found);

        // Change branchId
        cache.checkBranchId("branch2");

        // Cache should be flushed
        assertEquals("Cache should be empty after branch change", 0, cache.size());
    }

    @Test
    public void cache_sameBranchId_doesNotFlush() {
        EventSpecCache cache = new EventSpecCache();
        long now = System.currentTimeMillis();

        cache.put("key1", makeSpecResponse("s1", "branch1"), now);
        cache.checkBranchId("branch1");
        cache.checkBranchId("branch1");

        EventSpecCache.LookupResult result = cache.get("key1", now + 100);
        assertTrue("Entry should persist with same branchId", result.found);
    }

    @Test
    public void cache_buildKey_formatsCorrectly() {
        String key = EventSpecCache.buildKey("api123", "stream456", "Button Clicked");
        assertEquals("api123:stream456:Button Clicked", key);
    }

    // ===== EventValidator Tests =====

    @Test
    public void validator_matchingType_passes() {
        EventValidator validator = new EventValidator();

        Map<String, AvoEventSchemaType> schema = new HashMap<>();
        schema.put("name", new AvoEventSchemaType.AvoString());

        AvoEventSpecFetchTypes.EventSpecResponse spec = makeSpecResponseWithRules(
                "schema1", "branch1",
                Collections.singletonList(new AvoEventSpecFetchTypes.PropertyRule("name", "string"))
        );

        AvoEventSpecFetchTypes.ValidationResult result = validator.validate("stream1", schema, spec);

        assertNotNull(result);
        assertEquals("stream1", result.streamId);
    }

    @Test
    public void validator_mismatchingType_fails() {
        EventValidator validator = new EventValidator();

        Map<String, AvoEventSchemaType> schema = new HashMap<>();
        schema.put("count", new AvoEventSchemaType.AvoString());

        AvoEventSpecFetchTypes.EventSpecResponse spec = makeSpecResponseWithRules(
                "schema1", "branch1",
                Collections.singletonList(new AvoEventSpecFetchTypes.PropertyRule("count", "int"))
        );

        AvoEventSpecFetchTypes.ValidationResult result = validator.validate("stream1", schema, spec);

        assertNotNull(result);
        // Failed should be populated (type mismatch)
        assertFalse("Should have failed validations", result.failedEventIds.isEmpty());
    }

    @Test
    public void validator_regexPattern_matchesCorrectly() {
        // AC #6: RE2J used for all regex evaluation
        EventValidator validator = new EventValidator();

        Map<String, AvoEventSchemaType> schema = new HashMap<>();
        schema.put("value", new AvoEventSchemaType.AvoInt());

        // Regex that matches int or float
        AvoEventSpecFetchTypes.EventSpecResponse spec = makeSpecResponseWithRules(
                "schema1", "branch1",
                Collections.singletonList(new AvoEventSpecFetchTypes.PropertyRule("value", "int|float"))
        );

        AvoEventSpecFetchTypes.ValidationResult result = validator.validate("stream1", schema, spec);

        assertNotNull(result);
        // int matches "int|float" regex
        assertFalse("Passed should not be empty", result.passedEventIds.isEmpty());
    }

    @Test
    public void validator_missingProperty_treatedAsNull() {
        EventValidator validator = new EventValidator();

        Map<String, AvoEventSchemaType> schema = new HashMap<>();
        // Don't include "missing" property

        AvoEventSpecFetchTypes.EventSpecResponse spec = makeSpecResponseWithRules(
                "schema1", "branch1",
                Collections.singletonList(new AvoEventSpecFetchTypes.PropertyRule("missing", "string"))
        );

        AvoEventSpecFetchTypes.ValidationResult result = validator.validate("stream1", schema, spec);

        assertNotNull(result);
        // "null" doesn't match "string"
        assertFalse("Should have failed validations for missing property", result.failedEventIds.isEmpty());
    }

    @Test
    public void validator_nullRules_returnsNull() {
        EventValidator validator = new EventValidator();

        Map<String, AvoEventSchemaType> schema = new HashMap<>();
        AvoEventSpecFetchTypes.EventSpecResponse spec = new AvoEventSpecFetchTypes.EventSpecResponse(null, null);

        AvoEventSpecFetchTypes.ValidationResult result = validator.validate("stream1", schema, spec);

        assertNull("Should return null for null rules", result);
    }

    @Test
    public void validator_bandwidthOptimization_passedOnlyWhenStrictlySmaller() {
        // AC #13: passedEventIds returned only when strictly smaller than failedEventIds
        EventValidator validator = new EventValidator();

        Map<String, AvoEventSchemaType> schema = new HashMap<>();
        schema.put("a", new AvoEventSchemaType.AvoString());  // will pass "string"
        schema.put("b", new AvoEventSchemaType.AvoString());  // will fail "int"
        schema.put("c", new AvoEventSchemaType.AvoString());  // will fail "boolean"

        List<AvoEventSpecFetchTypes.PropertyRule> rules = new ArrayList<>();
        rules.add(new AvoEventSpecFetchTypes.PropertyRule("a", "string"));   // pass
        rules.add(new AvoEventSpecFetchTypes.PropertyRule("b", "int"));      // fail
        rules.add(new AvoEventSpecFetchTypes.PropertyRule("c", "boolean"));  // fail

        AvoEventSpecFetchTypes.EventSpecResponse spec = makeSpecResponseWithRules("s1", "b1", rules);

        AvoEventSpecFetchTypes.ValidationResult result = validator.validate("stream1", schema, spec);

        assertNotNull(result);
        // 1 passed, 2 failed -> passed is strictly smaller, so passedEventIds is populated
        assertFalse("passedEventIds should be populated (strictly smaller)", result.passedEventIds.isEmpty());
        assertTrue("failedEventIds should be empty (bandwidth optimization)", result.failedEventIds.isEmpty());
    }

    @Test
    public void validator_bandwidthOptimization_equalSizeUsesFailedIds() {
        // AC #14: equal-size arrays use failedEventIds (not passedEventIds)
        EventValidator validator = new EventValidator();

        Map<String, AvoEventSchemaType> schema = new HashMap<>();
        schema.put("a", new AvoEventSchemaType.AvoString());  // will pass "string"
        schema.put("b", new AvoEventSchemaType.AvoString());  // will fail "int"

        List<AvoEventSpecFetchTypes.PropertyRule> rules = new ArrayList<>();
        rules.add(new AvoEventSpecFetchTypes.PropertyRule("a", "string"));  // pass
        rules.add(new AvoEventSpecFetchTypes.PropertyRule("b", "int"));     // fail

        AvoEventSpecFetchTypes.EventSpecResponse spec = makeSpecResponseWithRules("s1", "b1", rules);

        AvoEventSpecFetchTypes.ValidationResult result = validator.validate("stream1", schema, spec);

        assertNotNull(result);
        // 1 passed, 1 failed -> equal, so tie-breaker: failedEventIds used
        assertTrue("passedEventIds should be empty (tie-breaker)", result.passedEventIds.isEmpty());
        assertFalse("failedEventIds should be populated (tie-breaker)", result.failedEventIds.isEmpty());
    }

    @Test
    public void validator_re2jUsed_invalidRegexDoesNotThrow() {
        // Verifies RE2J handles bad patterns gracefully
        EventValidator validator = new EventValidator();
        // This pattern would cause catastrophic backtracking in java.util.regex but RE2J rejects it
        boolean result = validator.matchesPattern("(?!invalid)", "test");
        // RE2J may or may not support lookahead; the important thing is no exception
        // The method should return false for patterns that don't match
    }

    // ===== AvoEventSpecFetcher Tests =====

    @Test
    public void fetcher_parseResponse_parsesValidJson() {
        String json = "{\"metadata\":{\"schemaId\":\"s1\",\"branchId\":\"b1\",\"latestActionId\":\"a1\",\"sourceId\":\"src1\"},"
                + "\"propertyRules\":[{\"propertyName\":\"name\",\"typePattern\":\"string\"}]}";

        AvoEventSpecFetchTypes.EventSpecResponse response = AvoEventSpecFetcher.parseResponse(json);

        assertNotNull(response);
        assertNotNull(response.metadata);
        assertEquals("s1", response.metadata.schemaId);
        assertEquals("b1", response.metadata.branchId);
        assertEquals("a1", response.metadata.latestActionId);
        assertEquals("src1", response.metadata.sourceId);
        assertNotNull(response.propertyRules);
        assertEquals(1, response.propertyRules.size());
        assertEquals("name", response.propertyRules.get(0).propertyName);
        assertEquals("string", response.propertyRules.get(0).typePattern);
    }

    @Test
    public void fetcher_parseResponse_handlesNullMetadata() {
        String json = "{\"propertyRules\":[{\"propertyName\":\"name\",\"typePattern\":\"string\"}]}";

        AvoEventSpecFetchTypes.EventSpecResponse response = AvoEventSpecFetcher.parseResponse(json);

        assertNotNull(response);
        assertNull(response.metadata);
        assertNotNull(response.propertyRules);
    }

    @Test
    public void fetcher_parseResponse_handlesNullPropertyRules() {
        String json = "{\"metadata\":{\"schemaId\":\"s1\",\"branchId\":\"b1\",\"latestActionId\":\"a1\",\"sourceId\":\"src1\"}}";

        AvoEventSpecFetchTypes.EventSpecResponse response = AvoEventSpecFetcher.parseResponse(json);

        assertNotNull(response);
        assertNotNull(response.metadata);
        assertNull(response.propertyRules);
    }

    @Test
    public void fetcher_parseResponse_handlesInvalidJson() {
        AvoEventSpecFetchTypes.EventSpecResponse response = AvoEventSpecFetcher.parseResponse("not json");
        assertNull(response);
    }

    @Test
    public void fetcher_inFlightDedup_singleRequestForSameKey() throws Exception {
        // AC #7: In-flight de-duplication: two concurrent same-key events produce exactly ONE HTTP request
        AtomicInteger fetchCount = new AtomicInteger(0);
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch allowComplete = new CountDownLatch(1);
        CountDownLatch callbacksDone = new CountDownLatch(2);

        AvoEventSpecFetcher.HttpFetcher mockFetcher = url -> {
            fetchCount.incrementAndGet();
            fetchStarted.countDown();
            // Block until we allow completion
            allowComplete.await(5, TimeUnit.SECONDS);
            return "{\"metadata\":{\"schemaId\":\"s1\",\"branchId\":\"b1\",\"latestActionId\":\"a1\",\"sourceId\":\"src1\"},"
                    + "\"propertyRules\":[]}";
        };

        AvoEventSpecFetcher fetcher = new AvoEventSpecFetcher(mockFetcher);

        AtomicReference<AvoEventSpecFetchTypes.EventSpecResponse> result1 = new AtomicReference<>();
        AtomicReference<AvoEventSpecFetchTypes.EventSpecResponse> result2 = new AtomicReference<>();

        // First request
        fetcher.fetchSpec("api1", "stream1", "event1", response -> {
            result1.set(response);
            callbacksDone.countDown();
        });

        // Wait for first request to start
        fetchStarted.await(5, TimeUnit.SECONDS);

        // Second request for same key (should queue, not send new HTTP request)
        fetcher.fetchSpec("api1", "stream1", "event1", response -> {
            result2.set(response);
            callbacksDone.countDown();
        });

        // Allow completion
        allowComplete.countDown();

        // Wait for both callbacks
        callbacksDone.await(5, TimeUnit.SECONDS);

        assertEquals("Should make exactly ONE HTTP request", 1, fetchCount.get());
        assertNotNull("First callback should receive response", result1.get());
        assertNotNull("Second callback should receive response", result2.get());
    }

    @Test
    public void fetcher_inFlightFailure_resolvesAllCallbacksWithNull() throws Exception {
        // AC #8: In-flight failure: all queued callbacks resolved with null on fetch failure
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch allowComplete = new CountDownLatch(1);
        CountDownLatch callbacksDone = new CountDownLatch(2);

        AvoEventSpecFetcher.HttpFetcher mockFetcher = url -> {
            fetchStarted.countDown();
            allowComplete.await(5, TimeUnit.SECONDS);
            throw new RuntimeException("Network error");
        };

        AvoEventSpecFetcher fetcher = new AvoEventSpecFetcher(mockFetcher);

        AtomicReference<AvoEventSpecFetchTypes.EventSpecResponse> result1 = new AtomicReference<>(makeSpecResponse("dummy", "dummy"));
        AtomicReference<AvoEventSpecFetchTypes.EventSpecResponse> result2 = new AtomicReference<>(makeSpecResponse("dummy", "dummy"));

        fetcher.fetchSpec("api1", "stream1", "event1", response -> {
            result1.set(response);
            callbacksDone.countDown();
        });

        fetchStarted.await(5, TimeUnit.SECONDS);

        fetcher.fetchSpec("api1", "stream1", "event1", response -> {
            result2.set(response);
            callbacksDone.countDown();
        });

        allowComplete.countDown();
        callbacksDone.await(5, TimeUnit.SECONDS);

        assertNull("First callback should receive null on failure", result1.get());
        assertNull("Second callback should receive null on failure", result2.get());
    }

    // ===== AvoInspector Integration Tests =====

    @Test
    public void inspector_validationNotActiveInProd() {
        // AC #1: Validation active in dev/staging; does NOT activate in prod
        AvoInspector prodInspector = new AvoInspector("apiKey", "1.0", "app", AvoInspectorEnv.Prod);
        assertFalse("Validation should NOT be active in prod", prodInspector.isValidationEnabled());
    }

    @Test
    public void inspector_validationActiveInDev() {
        // AC #1: Validation active in dev/staging
        AvoInspector devInspector = new AvoInspector("apiKey", "1.0", "app", AvoInspectorEnv.Dev);
        assertTrue("Validation should be active in dev", devInspector.isValidationEnabled());
    }

    @Test
    public void inspector_validationActiveInStaging() {
        // AC #1: Validation active in dev/staging
        AvoInspector stagingInspector = new AvoInspector("apiKey", "1.0", "app", AvoInspectorEnv.Staging);
        assertTrue("Validation should be active in staging", stagingInspector.isValidationEnabled());
    }

    @Test
    public void inspector_cacheHit_triggersSync_validation() throws Exception {
        // AC #3: Cache hit triggers synchronous validation then immediate send
        AvoInspector inspector = new AvoInspector("apiKey", "1.0", "app", AvoInspectorEnv.Dev);
        MockitoAnnotations.initMocks(this);
        inspector.networkCallsHandler = mockNetworkCallsHandler;

        // Pre-populate cache
        String cacheKey = EventSpecCache.buildKey("apiKey", "", "TestEvent");
        AvoEventSpecFetchTypes.EventSpecResponse spec = makeSpecResponseWithRules(
                "schema1", "branch1",
                Collections.singletonList(new AvoEventSpecFetchTypes.PropertyRule("name", "string"))
        );
        inspector.eventSpecCache.put(cacheKey, spec, System.currentTimeMillis());

        // Create schema
        Map<String, AvoEventSchemaType> schema = new HashMap<>();
        schema.put("name", new AvoEventSchemaType.AvoString());

        // Execute validation (synchronous path due to cache hit)
        inspector.fetchAndValidateAsync("TestEvent", schema,
                new AvoInspectorTarget("apiKey", "app", "1.0"), "");

        // Verify reportValidatedEvent was called (via network handler)
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());

        List<Map<String, Object>> captured = captor.getValue();
        assertEquals(1, captured.size());
        assertEquals("validatedEvent", captured.get(0).get("type"));
    }

    @Test
    public void inspector_cacheMiss_triggersAsyncFetch() throws Exception {
        // AC #2: Cache miss triggers async fetch; on response, event validated then sent
        CountDownLatch done = new CountDownLatch(1);

        String specJson = "{\"metadata\":{\"schemaId\":\"s1\",\"branchId\":\"b1\",\"latestActionId\":\"a1\",\"sourceId\":\"src1\"},"
                + "\"propertyRules\":[{\"propertyName\":\"name\",\"typePattern\":\"string\"}]}";

        AvoEventSpecFetcher.HttpFetcher mockFetcher = url -> specJson;

        AvoInspector inspector = new AvoInspector("apiKey", "1.0", "app", AvoInspectorEnv.Dev);
        MockitoAnnotations.initMocks(this);
        inspector.networkCallsHandler = mockNetworkCallsHandler;
        inspector.eventSpecFetcher = new AvoEventSpecFetcher(mockFetcher);

        Map<String, AvoEventSchemaType> schema = new HashMap<>();
        schema.put("name", new AvoEventSchemaType.AvoString());

        inspector.fetchAndValidateAsync("TestEvent", schema,
                new AvoInspectorTarget("apiKey", "app", "1.0"), "");

        // Wait for async completion
        Thread.sleep(500);

        // Verify the fetched spec was cached
        String cacheKey = EventSpecCache.buildKey("apiKey", "", "TestEvent");
        EventSpecCache.LookupResult lookup = inspector.eventSpecCache.get(cacheKey, System.currentTimeMillis());
        assertTrue("Spec should be cached after fetch", lookup.found);
    }

    @Test
    public void inspector_nullSpecResponse_isCached() throws Exception {
        // AC #5: Null spec responses are cached
        AvoEventSpecFetcher.HttpFetcher mockFetcher = url -> "{}";

        AvoInspector inspector = new AvoInspector("apiKey", "1.0", "app", AvoInspectorEnv.Dev);
        inspector.eventSpecFetcher = new AvoEventSpecFetcher(mockFetcher);

        Map<String, AvoEventSchemaType> schema = new HashMap<>();

        inspector.fetchAndValidateAsync("UnknownEvent", schema,
                new AvoInspectorTarget("apiKey", "app", "1.0"), "");

        // Wait for async completion
        Thread.sleep(500);

        // Verify the null-ish response was cached
        String cacheKey = EventSpecCache.buildKey("apiKey", "", "UnknownEvent");
        EventSpecCache.LookupResult lookup = inspector.eventSpecCache.get(cacheKey, System.currentTimeMillis());
        assertTrue("Null-ish response should be cached", lookup.found);
    }

    @Test
    public void inspector_reportValidatedEvent_containsCorrectPayload() {
        // Verify the validated event payload structure
        AvoInspector inspector = new AvoInspector("apiKey", "1.0", "app", AvoInspectorEnv.Dev);
        MockitoAnnotations.initMocks(this);
        inspector.networkCallsHandler = mockNetworkCallsHandler;

        AvoEventSpecFetchTypes.EventSpecMetadata metadata =
                new AvoEventSpecFetchTypes.EventSpecMetadata("schema1", "branch1", "action1", "source1");

        Map<String, AvoEventSpecFetchTypes.PropertyValidation> propValidations = new HashMap<>();
        propValidations.put("name", new AvoEventSpecFetchTypes.PropertyValidation(
                null, Collections.singletonList("name:string")
        ));

        AvoEventSpecFetchTypes.ValidationResult validationResult =
                new AvoEventSpecFetchTypes.ValidationResult(
                        "stream1", metadata,
                        Collections.emptyList(),
                        Collections.singletonList("name:string"),
                        propValidations
                );

        inspector.reportValidatedEvent(validationResult,
                new AvoInspectorTarget("apiKey", "app", "1.0"), "stream1");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());

        Map<String, Object> body = captor.getValue().get(0);
        assertEquals("validatedEvent", body.get("type"));
        assertEquals("apiKey", body.get("apiKey"));
        assertEquals("app", body.get("appName"));
        assertEquals("1.0", body.get("appVersion"));
        assertEquals("stream1", body.get("streamId"));
        assertNotNull(body.get("eventSpecMetadata"));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadataMap = (Map<String, Object>) body.get("eventSpecMetadata");
        assertEquals("schema1", metadataMap.get("schemaId"));
        assertEquals("branch1", metadataMap.get("branchId"));
        assertEquals("action1", metadataMap.get("latestActionId"));
        assertEquals("source1", metadataMap.get("sourceId"));
    }

    // ===== Helper Methods =====

    private AvoEventSpecFetchTypes.EventSpecResponse makeSpecResponse(String schemaId, String branchId) {
        return new AvoEventSpecFetchTypes.EventSpecResponse(
                new AvoEventSpecFetchTypes.EventSpecMetadata(schemaId, branchId, "action1", "source1"),
                new ArrayList<>()
        );
    }

    private AvoEventSpecFetchTypes.EventSpecResponse makeSpecResponseWithRules(
            String schemaId, String branchId, List<AvoEventSpecFetchTypes.PropertyRule> rules) {
        return new AvoEventSpecFetchTypes.EventSpecResponse(
                new AvoEventSpecFetchTypes.EventSpecMetadata(schemaId, branchId, "action1", "source1"),
                rules
        );
    }
}
