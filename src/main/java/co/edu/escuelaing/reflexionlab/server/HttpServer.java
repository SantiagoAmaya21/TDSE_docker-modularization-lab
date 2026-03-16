package co.edu.escuelaing.reflexionlab.server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal HTTP server. Serves static files (HTML, PNG) and delegates GET requests
 * to a request handler (REST routes). Handles requests concurrently via a thread pool
 * and supports graceful shutdown (stops accepting new connections and waits for in-flight
 * requests to finish).
 */
public class HttpServer {

    private static final int DEFAULT_PORT = 35000;
    private static final String STATIC_PREFIX = "static/";
    private static final int DEFAULT_POOL_SIZE = 16;
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;
    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("htm", "text/html");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "application/javascript");
    }

    private final int port;
    private final RequestHandler requestHandler;
    private final int poolSize;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public HttpServer(int port, RequestHandler requestHandler) {
        this(port, requestHandler, DEFAULT_POOL_SIZE);
    }

    public HttpServer(int port, RequestHandler requestHandler, int poolSize) {
        this.port = port;
        this.requestHandler = requestHandler;
        this.poolSize = poolSize > 0 ? poolSize : DEFAULT_POOL_SIZE;
    }

    /**
     * Starts the server: binds the port, registers a shutdown hook for graceful shutdown,
     * and accepts connections in a loop, dispatching each to the thread pool.
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverSocket = new ServerSocket(port);
        executor = Executors.newFixedThreadPool(poolSize);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown signal received, stopping server gracefully...");
            stop();
        }));

        System.out.println("Server listening on http://localhost:" + port + " (pool size: " + poolSize + ")");
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> {
                    try (Socket s = clientSocket) {
                        handleConnection(s);
                    } catch (IOException e) {
                        if (running.get()) {
                            System.err.println("Error handling connection: " + e.getMessage());
                        }
                    }
                });
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("Accept error: " + e.getMessage());
                }
                break;
            }
        }
    }

    /**
     * Graceful shutdown: stops accepting new connections, closes the server socket,
     * then shuts down the executor and waits up to SHUTDOWN_AWAIT_SECONDS for
     * in-flight requests to complete.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing server socket: " + e.getMessage());
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("Server stopped.");
    }

    private void handleConnection(Socket clientSocket) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = clientSocket.getOutputStream();

        String requestLine = in.readLine();
        if (requestLine == null) return;

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            sendError(out, 400, "Bad Request");
            return;
        }

        String method = parts[0];
        String pathWithQuery = parts[1];
        String path = pathWithQuery.contains("?") ? pathWithQuery.substring(0, pathWithQuery.indexOf('?')) : pathWithQuery;
        Map<String, String> queryParams = new HashMap<>();
        if (pathWithQuery.contains("?")) {
            String qs = pathWithQuery.substring(pathWithQuery.indexOf('?') + 1).split(" ")[0];
            queryParams = parseQueryString(qs);
        }

        // Consume remaining headers until blank line
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) { }

        if (!"GET".equals(method)) {
            sendError(out, 405, "Method Not Allowed");
            return;
        }

        String response = requestHandler.handle(path, queryParams);
        if (response != null) {
            sendOk(out, response, "text/html");
            return;
        }

        // Static file from classpath, then filesystem
        String resourcePath = path.equals("/") ? "index.html" : path.replaceFirst("^/", "");
        String classpathResource = STATIC_PREFIX + resourcePath;
        InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(classpathResource);
        if (resourceStream != null) {
            byte[] body = resourceStream.readAllBytes();
            resourceStream.close();
            String ext = resourcePath.contains(".") ? resourcePath.substring(resourcePath.lastIndexOf('.') + 1) : "";
            String contentType = MIME_TYPES.getOrDefault(ext.toLowerCase(), "application/octet-stream");
            sendOk(out, body, contentType);
        } else {
            Path filePath = Path.of("src/main/resources/static", resourcePath);
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                byte[] body = Files.readAllBytes(filePath);
                String ext = filePath.getFileName().toString().contains(".")
                        ? filePath.getFileName().toString().substring(filePath.getFileName().toString().lastIndexOf('.') + 1)
                        : "";
                String contentType = MIME_TYPES.getOrDefault(ext.toLowerCase(), "application/octet-stream");
                sendOk(out, body, contentType);
            } else {
                sendError(out, 404, "Not Found");
            }
        }
    }

    private Map<String, String> parseQueryString(String qs) {
        Map<String, String> params = new HashMap<>();
        if (qs == null || qs.isEmpty()) return params;
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = decode(pair.substring(0, eq));
                String value = eq < pair.length() - 1 ? decode(pair.substring(eq + 1)) : "";
                params.put(key, value);
            }
        }
        return params;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private void sendOk(OutputStream out, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        sendOk(out, bytes, contentType);
    }

    private void sendOk(OutputStream out, byte[] body, String contentType) throws IOException {
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void sendError(OutputStream out, int code, String message) throws IOException {
        String body = "<html><body><h1>" + code + " " + message + "</h1></body></html>";
        String header = "HTTP/1.1 " + code + " " + message + "\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static int getDefaultPort() {
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                int p = Integer.parseInt(envPort.trim());
                if (p > 0 && p <= 65535) return p;
            } catch (NumberFormatException ignored) {
                // fallback to default
            }
        }
        String sysPropPort = System.getProperty("port");
        if (sysPropPort != null && !sysPropPort.isBlank()) {
            try {
                int p = Integer.parseInt(sysPropPort.trim());
                if (p > 0 && p <= 65535) return p;
            } catch (NumberFormatException ignored) {
                // fallback to default
            }
        }
        return DEFAULT_PORT;
    }
}
