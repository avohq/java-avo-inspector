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
        final Map<String, String> headers;
        final byte[] rawBody;
        final JSONArray body;

        Request(Map<String, String> headers, byte[] rawBody, JSONArray body) {
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

    MockInspectorServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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

    void delayResponses(long millis) {
        this.responseDelayMs = millis;
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
        synchronized (requests) {
            requests.add(new Request(headers, raw, new JSONArray(new String(json, StandardCharsets.UTF_8))));
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
        exchange.sendResponseHeaders(status, response.length);
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
        server.stop(0);
    }
}
