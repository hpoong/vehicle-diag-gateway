package com.hopoong.diag.testsupport;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

public final class StubReceiver implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile Duration hold = Duration.ZERO;
    public record Request(String method, String path, String contentType, String eventId, String requestId, String body) {}

    public StubReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                int responseStatus = status;
                Duration delay = hold;
                try (exchange) {
                    var h = exchange.getRequestHeaders();
                    requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                            h.getFirst("Content-Type"), h.getFirst("X-Event-Id"), h.getFirst("X-Request-Id"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    if (!delay.isZero()) new CountDownLatch(1).await(delay.toMillis(), TimeUnit.MILLISECONDS);
                    exchange.sendResponseHeaders(responseStatus, -1);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                catch (IOException ignored) { /* Client may time out before the response. */ }
            });
            server.start();
        } catch (IOException ex) { executor.shutdownNow(); throw new UncheckedIOException(ex); }
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public void respond(int value) { status = value; }
    public void hold(Duration value) { hold = value; }
    public List<Request> requests() { return List.copyOf(requests); }
    public void reset() { status = 200; hold = Duration.ZERO; requests.clear(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
    public static int unusedPort() {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    public static ClosingServer closeWithoutResponse() { return new ClosingServer(); }
    public static final class ClosingServer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        public ClosingServer() {
            try { server = new ServerSocket(0); }
            catch (IOException ex) { throw new UncheckedIOException(ex); }
            executor.submit(() -> {
                while (!server.isClosed()) {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        int length = 0;
                        for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                            if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                        }
                        for (int i=0; i<length; i++) if (reader.read() < 0) break;
                    } catch (IOException ignored) { }
                }
            });
        }
        public String url() { return "http://127.0.0.1:" + server.getLocalPort(); }
        @Override public void close() {
            try { server.close(); } catch (IOException ignored) { }
            executor.shutdownNow();
        }
    }
}
