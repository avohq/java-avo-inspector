package is.avo.inspector;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;

public class SamplingRateTests {

    AvoNetworkCallsBodyFactory avoNetworkCallsBodyFactory = new AvoNetworkCallsBodyFactory("envName", "libVersion");

    @Test
    public void doesNotSendDataWithSamplingRate0() throws InterruptedException {
        // When
        AvoNetworkCallsHandler sut = new AvoNetworkCallsHandler(
                "testEnvName"
        );
        sut.samplingRate = 0;
        final int[] hasRan = {0};
        sut.reportToInspector = (data) -> hasRan[0] += 1;

        // Then
        for (int i = 0; i < 1000; i++) {
            final Map<String, Object> body = avoNetworkCallsBodyFactory.bodyForSessionStartedCall(new AvoInspectorTarget(
                    "apiKey", "appName", "appVersion"
            ));
            sut.reportInspectorWithBatchBody(new ArrayList<Map<String, Object>>() {{
                                                 add(body);
                                             }}
            );
        }
        Thread.sleep(1000);

        // Then
        assertEquals(0, hasRan[0]);
    }

    @Test
    public void alwaysSendsDataWithSamplingRate1() throws InterruptedException {
        // Given
        AvoNetworkCallsHandler sut = new AvoNetworkCallsHandler(
                "testEnvName"
        );
        sut.samplingRate = 1.0;
        final int[] hasRan = {0};
        sut.reportToInspector = (data) -> hasRan[0] += 1;

        // When
        for (int i = 0; i < 1000; i++) {
            final Map<String, Object> body = avoNetworkCallsBodyFactory.bodyForSessionStartedCall(new AvoInspectorTarget(
                    "apiKey", "appName", "appVersion"
            ));
            sut.reportInspectorWithBatchBody(new ArrayList<Map<String, Object>>() {{
                add(body);
            }});
        }
        Thread.sleep(1000);

        // Then
        assertEquals(1000, hasRan[0]);
    }

    @Test
    public void setsSamplingRate1() throws InterruptedException {
        // Given
        AvoNetworkCallsHandler sut = new AvoNetworkCallsHandler(
                "testEnvName"
        );
        sut.samplingRate = 1.0;
        final List<Map> hasRanWithBody = new ArrayList<>();
        sut.reportToInspector = maps -> {
            synchronized (hasRanWithBody) {
                hasRanWithBody.add(maps.get(0));
            }
        };

        final Map<String, Object> body = avoNetworkCallsBodyFactory.bodyForSessionStartedCall(new AvoInspectorTarget(
                "apiKey", "appName", "appVersion"
        ));

        // When
        sut.reportInspectorWithBatchBody(new ArrayList<Map<String, Object>>() {{
            add(body);
        }});
        Thread.sleep(100);

        // Then
        Map<String, Object> expectedBody = body;
        expectedBody.put("samplingRate", 1.0);
        synchronized (hasRanWithBody) {
            assertEquals(expectedBody, hasRanWithBody.get(0));
        }
    }
}
