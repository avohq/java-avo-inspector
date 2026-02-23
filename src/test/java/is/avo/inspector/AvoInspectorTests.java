package is.avo.inspector;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import org.mockito.MockitoAnnotations;

class TestObjectEventParam {
    private final String filed1;
    private final int field2;

    TestObjectEventParam(String filed1, int field2) {
        this.filed1 = filed1;
        this.field2 = field2;
    }

    public String getFiled1() {
        return filed1;
    }

    public int getField2() {
        return field2;
    }
}

public class AvoInspectorTests {

    private AvoInspector sut;

    @Mock
    AvoNetworkCallsHandler mockNetworkCallsHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        sut = new AvoInspector(
                "apiKey",
                "appVersion",
                "appName",
                AvoInspectorEnv.Dev
        );
        sut.networkCallsHandler = mockNetworkCallsHandler;
    }

    @Test
    public void trackSchemaFromEventJsonWithDefaultTarget() {
        // Given
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        JSONObject eventProperties = new JSONObject();
        eventProperties.put("key1", "value1");
        eventProperties.put("key2", true);
        eventProperties.put("key3", 1.1);
        eventProperties.put("key4", 6);
        eventProperties.put("key5", JSONObject.NULL);

        JSONObject nestedObj = new JSONObject();
        nestedObj.put("key61", "value61");
        nestedObj.put("key62", 62);
        eventProperties.put("key6", nestedObj);

        JSONArray array = new JSONArray();
        array.put(1).put(2).put(3);
        eventProperties.put("key7", array);

        TestObjectEventParam testObj = new TestObjectEventParam("example", 42);
        eventProperties.put("key8", testObj);

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        verifyBodies(captor.getValue(), "apiKey", "appName", "appVersion", eventProperties);
    }

    @Test
    public void trackSchemaFromEventJsonWithOverrideTarget() {
        // Given
        AvoInspectorTarget overrideTarget = new AvoInspectorTarget("overrideKey", "overrideName", "overrideVersion");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        JSONObject eventProperties = new JSONObject();
        eventProperties.put("key1", "value1");
        eventProperties.put("key2", true);
        eventProperties.put("key3", 1.1);
        eventProperties.put("key4", 6);
        eventProperties.put("key5", JSONObject.NULL);

        JSONObject nestedObj = new JSONObject();
        nestedObj.put("key61", "value61");
        nestedObj.put("key62", 62);
        eventProperties.put("key6", nestedObj);

        JSONArray array = new JSONArray();
        array.put(1).put(2).put(3);
        eventProperties.put("key7", array);

        TestObjectEventParam testObj = new TestObjectEventParam("example", 42);
        eventProperties.put("key8", testObj);

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties, overrideTarget);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        verifyBodies(captor.getValue(), overrideTarget.getApiKey(), overrideTarget.getAppName(), overrideTarget.getAppVersion(), eventProperties);
    }

    @Test
    public void trackSchemaFromEventMapWithDefaultTarget() {
        // Given
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        Map<String, Object> eventProperties = new HashMap<>();
        eventProperties.put("key1", "value1");
        eventProperties.put("key2", true);
        eventProperties.put("key3", 1.1);
        eventProperties.put("key4", 6);
        eventProperties.put("key5", null);

        Map<String, Object> nestedObj = new HashMap<>();
        nestedObj.put("key61", "value61");
        nestedObj.put("key62", 62);
        eventProperties.put("key6", nestedObj);

        List<Integer> array = Arrays.asList(1, 2, 3);
        eventProperties.put("key7", array);

        TestObjectEventParam testObj = new TestObjectEventParam("example", 42);
        eventProperties.put("key8", testObj);

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        verifyBodies(captor.getValue(), "apiKey", "appName", "appVersion", eventProperties);
    }

    @Test
    public void trackSchemaFromEventMapWithOverrideTarget() {
        // Given
        AvoInspectorTarget overrideTarget = new AvoInspectorTarget("overrideKey", "overrideName", "overrideVersion");
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);

        Map<String, Object> eventProperties = new HashMap<>();
        eventProperties.put("key1", "value1");
        eventProperties.put("key2", 2);
        eventProperties.put("key3", 3.0);
        eventProperties.put("key4", true);
        eventProperties.put("key5", null);

        Map<String, Object> nestedObj = new HashMap<>();
        nestedObj.put("key61", "value61");
        nestedObj.put("key62", 62);
        eventProperties.put("key6", nestedObj);

        List<Integer> array = Arrays.asList(1, 2, 3);
        eventProperties.put("key7", array);

        TestObjectEventParam testObj = new TestObjectEventParam("example", 42);
        eventProperties.put("key8", testObj);

        // When
        sut.trackSchemaFromEvent("My Event", eventProperties, overrideTarget);

        // Then
        verify(mockNetworkCallsHandler).reportInspectorWithBatchBody(captor.capture());
        verifyBodies(captor.getValue(), overrideTarget.getApiKey(), overrideTarget.getAppName(), overrideTarget.getAppVersion(), eventProperties);
    }

    private void verifyBodies(List<Map<String, Object>> bodies, String apiKey, String appName, String appVersion, Object eventProperties) {
        assertEquals("Batch should contain exactly 1 body (no session started)", 1, bodies.size());
        Map<String, Object> actualTrackingBody = bodies.get(0);

        assertEquals(apiKey, actualTrackingBody.get("apiKey"));
        assertEquals(appName, actualTrackingBody.get("appName"));
        assertEquals(appVersion, actualTrackingBody.get("appVersion"));
        assertEquals("-", actualTrackingBody.get("libVersion"));
        assertEquals("dev", actualTrackingBody.get("env"));
        assertEquals("java-jvm", actualTrackingBody.get("libPlatform"));
        assertNotNull(actualTrackingBody.get("messageId"));
        assertNotNull(actualTrackingBody.get("createdAt"));
        assertNotNull(actualTrackingBody.get("anonymousId"));
        assertNull("sessionId should NOT be present", actualTrackingBody.get("sessionId"));
        assertEquals("event", actualTrackingBody.get("type"));
        assertEquals("My Event", actualTrackingBody.get("eventName"));
        assertEquals(Util.remapProperties(new AvoSchemaExtractor().extractSchema(eventProperties, false)).toString(), actualTrackingBody.get("eventProperties").toString());
        assertEquals(false, actualTrackingBody.get("avoFunction"));
    }
}
