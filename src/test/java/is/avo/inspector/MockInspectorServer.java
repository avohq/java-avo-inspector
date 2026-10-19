package is.avo.inspector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

// Records every POST and answers with a configurable status and body.
class MockInspectorServer implements AutoCloseable {

    static class Request {
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] rawBody;
        // null when the request has no body.
        final JSONArray body;

        Request(String method, String path, Map<String, String> headers, byte[] rawBody, JSONArray body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.rawBody = rawBody;
            this.body = body;
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "{\"samplingRate\":1.0}";
    private volatile long responseDelayMs = 0;
    private volatile String location;
    private volatile java.util.concurrent.CountDownLatch hold;

    MockInspectorServer() throws IOException {
        this(false);
    }

    // concurrent: answers requests in parallel, as the real API does, instead of one at a time.
    MockInspectorServer(boolean concurrent) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        if (concurrent) {
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        }
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                record(exchange);
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    void respond(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    void redirect(int status, String location) {
        this.status = status;
        this.responseBody = "";
        this.location = location;
    }

    void delayResponses(long millis) {
        this.responseDelayMs = millis;
    }

    // Requests are recorded at once but answered only after releaseResponses().
    void holdResponses() {
        hold = new java.util.concurrent.CountDownLatch(1);
    }

    void releaseResponses() {
        java.util.concurrent.CountDownLatch current = hold;
        if (current != null) {
            current.countDown();
        }
    }

    List<Request> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    Request awaitRequest(int index, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<Request> current = requests();
            if (current.size() > index) {
                return current.get(index);
            }
            Thread.sleep(10);
        }
        return null;
    }

    private void record(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            headers.put(header.getKey().toLowerCase(), header.getValue().get(0));
        }
        byte[] raw = readAll(exchange.getRequestBody());
        byte[] json = "gzip".equals(headers.get("content-encoding")) ? readAll(new GZIPInputStream(new ByteArrayInputStream(raw))) : raw;
        JSONArray body = json.length == 0 ? null : new JSONArray(new String(json, StandardCharsets.UTF_8));
        synchronized (requests) {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, raw, body));
        }
        java.util.concurrent.CountDownLatch current = hold;
        if (current != null) {
            try {
                current.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (responseDelayMs > 0) {
            try {
                Thread.sleep(responseDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (location != null) {
            exchange.getResponseHeaders().set("Location", location);
        }
        exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    @Override
    public void close() {
        releaseResponses();
        server.stop(0);
    }
}
