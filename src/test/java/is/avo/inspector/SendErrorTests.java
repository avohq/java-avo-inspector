package is.avo.inspector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// What happens to a send that fails with an Error, or that the pool cannot start: never silent.
public class SendErrorTests {

    private PrintStream originalErr;
    private ByteArrayOutputStream captured;

    @Before
    public void setUp() throws Exception {
        originalErr = System.err;
        captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, "UTF-8"));
        AvoLog.resetForTesting();
    }

    @After
    public void tearDown() {
        System.setErr(originalErr);
        AvoBatcher.sendExecutorForTesting = null;
        AvoLog.resetForTesting();
    }

    private String stderr() {
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> event(String name) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("apiKey", "key");
        event.put("appName", "app");
        event.put("eventName", name);
        return event;
    }

    @Test(timeout = 10_000)
    public void anErrorThrownInsideASendIsLogged() {
        AvoBatcher batcher = new AvoBatcher(new AvoBatcher.Sender() {
            @Override
            public AvoNetworkCallsHandler.SendResult send(List<Map<String, Object>> events, String apiKey) {
                throw new StackOverflowError();
            }
        }, 1, 30, 1000, true);
        try {
            batcher.enqueue(event("E1"));
            batcher.flush(5000);
            assertTrue(stderr(), stderr().contains("Avo Inspector: something went wrong. Please report to support@avo.app."));
            assertEquals(0, batcher.pendingCount());
        } finally {
            batcher.destroy();
        }
    }

    @Test(timeout = 10_000)
    public void aSendThePoolCannotStartIsCountedAsDropped() {
        AvoBatcher.sendExecutorForTesting = new java.util.concurrent.Executor() {
            @Override
            public void execute(Runnable command) {
                throw new OutOfMemoryError("unable to create native thread");
            }
        };
        AvoBatcher batcher = new AvoBatcher(new AvoBatcher.Sender() {
            @Override
            public AvoNetworkCallsHandler.SendResult send(List<Map<String, Object>> events, String apiKey) {
                return AvoNetworkCallsHandler.SendResult.OK;
            }
        }, 3, 30, 1000, true);
        try {
            for (int i = 0; i < 3; i++) {
                batcher.enqueue(event("E" + i));
            }
            assertTrue(stderr(), stderr().contains("Avo Inspector: dropped 3 event(s) (send backlog full) in the last 10s."));
            assertEquals(0, batcher.pendingCount());
        } finally {
            batcher.destroy();
        }
    }
}
