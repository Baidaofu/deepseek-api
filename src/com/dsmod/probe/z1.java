package com.dsmod.probe;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Local API gateway: a loopback HTTP server that speaks the OpenAI Chat Completions /
 * Responses protocol and the Anthropic Messages protocol, and relays each request into
 * the native DeepSeek transport through {@link z2.Backend}.
 *
 * <p>This class is the open-source reimplementation of the payload-resident gateway that
 * the shipped Closed edition keeps inside its encrypted secondary payload.  {@link z13}
 * reaches it purely reflectively and only depends on the method names declared here, so
 * it can be dropped in as the in-process "payload" without changing the host DEX.</p>
 *
 * <p>Lifecycle: {@link #start(Context, z2.Backend)} binds the loopback listener and keeps it
 * alive until {@link #stop()}.  Configuration (protocol, key, port) is persisted to the same
 * SharedPreferences file the settings UI uses, so the UI and the server never disagree.</p>
 */
public final class z1 {

    // ---------------------------------------------------------------------------------
    // Configuration keys / defaults
    // ---------------------------------------------------------------------------------

    private static final String PREFS = "dq0_local_api";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PROTOCOL = "protocol";
    private static final String KEY_PORT = "port";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_PREFERRED_PORT = "preferred_port";

    static final String PROTOCOL_OPENAI = z2.PROTOCOL_OPENAI;
    static final String PROTOCOL_ANTHROPIC = z2.PROTOCOL_ANTHROPIC;

    /** Port the loopback listener prefers.  Kept in the ephemeral-safe upper range. */
    static final int DEFAULT_PORT = 8765;

    // ---------------------------------------------------------------------------------
    // Status files.  The Closed payload's gateway published these too; the module's log
    // exporter reads them and the launcher page shows the public copy.  Paths mirror the
    // constants declared in z13.
    // ---------------------------------------------------------------------------------

    private static final String INFO_FILE = "/data/data/com.deepseek.chat/files/dq0.txt";
    private static final String LOG_FILE =
            "/data/data/com.deepseek.chat/files/deekseep_api.log";
    private static final String STATUS_FILE =
            "/data/data/com.deepseek.chat/files/deekseep_api_status.json";
    /** Written to shared storage so the module process can read it without root. */
    static final String PUBLIC_INFO_FILE = "/storage/emulated/0/Deekseep_API.txt";

    private static final AtomicLong STARTED_AT = new AtomicLong();
    private static final AtomicLong TOTAL_REQUESTS = new AtomicLong();
    private static final AtomicLong SUCCESSFUL_REQUESTS = new AtomicLong();
    private static final AtomicLong FAILED_REQUESTS = new AtomicLong();
    private static final AtomicLong STREAMING_REQUESTS = new AtomicLong();
    private static final AtomicLong REASONING_REQUESTS = new AtomicLong();
    private static final AtomicLong TOOL_ROUNDS = new AtomicLong();
    private static final AtomicLong LATENCY_SUM_MS = new AtomicLong();
    private static final AtomicLong MAX_LATENCY_MS = new AtomicLong();

    /** Hard cap on a single request body so a hostile client cannot exhaust the host heap. */
    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    /** Per-connection read/write timeout. */
    private static final int SOCKET_TIMEOUT_MS = 600_000;

    private static final String DEFAULT_OPENAI_MODEL = "deepseek-v4-flash";
    private static final String DEFAULT_ANTHROPIC_MODEL = "deepseek-v4-flash";

    // ---------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------

    private static final Object LOCK = new Object();
    private static final AtomicLong REQUEST_SEQ = new AtomicLong();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile Context appContext;
    private static volatile z2.Backend backend;
    private static volatile ServerSocket listener;
    private static volatile Thread acceptThread;
    private static volatile ExecutorService workers;
    private static volatile int boundPort;
    private static volatile boolean stopping;

    private static volatile String runtimeDiagnostic = "";

    private z1() {}

    // ---------------------------------------------------------------------------------
    // Lifecycle (called reflectively by z13)
    // ---------------------------------------------------------------------------------

    public static synchronized void start(Context context, z2.Backend completionBackend) {
        if (context != null) appContext = context.getApplicationContext();
        backend = completionBackend;
        stopping = false;
        if (listener != null && !listener.isClosed()) {
            return; // already running
        }
        try {
            bind();
        } catch (Throwable error) {
            runtimeDiagnostic = "bind failed: " + safe(error);
            try { Main.log("[local-api] " + runtimeDiagnostic); } catch (Throwable ignored) {}
        }
    }

    public static synchronized void stop() {
        stopping = true;
        ServerSocket server = listener;
        listener = null;
        boundPort = 0;
        if (server != null) {
            try { server.close(); } catch (Throwable ignored) {}
        }
        Thread thread = acceptThread;
        acceptThread = null;
        if (thread != null) thread.interrupt();
        ExecutorService pool = workers;
        workers = null;
        if (pool != null) pool.shutdownNow();
        runtimeDiagnostic = "";
    }

    // ---------------------------------------------------------------------------------
    // Status / info publishing
    // ---------------------------------------------------------------------------------

    /** Human-readable connection summary, also mirrored to shared storage. */
    static String infoText() {
        StringBuilder out = new StringBuilder();
        out.append("Deekseep Local API\n");
        out.append("state: ").append(isRunning() ? "running" : "stopped").append('\n');
        out.append("protocol: ").append(protocolMode()).append('\n');
        out.append("base_url: ").append(openAiEndpoint()).append('\n');
        out.append("lan_base_url: ").append(lanEndpoint()).append('\n');
        out.append("api_key: ").append(apiKey()).append('\n');
        out.append("https: ").append(z5.isEnabled(appContext) ? "on" : "off").append('\n');
        return out.toString();
    }

    private static void publishInfo() {
        String text = infoText();
        writeTextFile(INFO_FILE, text);
        writeTextFile(PUBLIC_INFO_FILE, text);
    }

    /** Request counters, matching the Closed payload's status document. */
    private static void publishStatus() {
        try {
            long total = TOTAL_REQUESTS.get();
            JSONObject status = new JSONObject();
            status.put("started_at", STARTED_AT.get());
            status.put("uptime_ms", isRunning()
                    ? Math.max(0L, System.currentTimeMillis() - STARTED_AT.get()) : 0L);
            status.put("total_requests", total);
            status.put("successful_requests", SUCCESSFUL_REQUESTS.get());
            status.put("failed_requests", FAILED_REQUESTS.get());
            status.put("streaming_requests", STREAMING_REQUESTS.get());
            status.put("reasoning_requests", REASONING_REQUESTS.get());
            status.put("tool_rounds", TOOL_ROUNDS.get());
            status.put("average_latency_ms", total == 0 ? 0 : LATENCY_SUM_MS.get() / total);
            status.put("max_latency_ms", MAX_LATENCY_MS.get());
            status.put("context_relay_success", 0);
            status.put("context_relay_cached", 0);
            status.put("context_relay_failed", 0);
            status.put("auto_recovery_count", 0);
            writeTextFile(STATUS_FILE, status.toString());
        } catch (Throwable ignored) {}
    }

    private static void appendLog(String message) {
        try {
            File file = new File(LOG_FILE);
            if (file.length() > 512 * 1024L) {
                File rotated = new File(LOG_FILE + ".1");
                if (rotated.exists()) rotated.delete();
                file.renameTo(rotated);
                file = new File(LOG_FILE);
            }
            String stamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .format(new java.util.Date());
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write((stamp + " " + message + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {}
    }

    private static void writeTextFile(String path, String text) {
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) return;
            FileOutputStream out = new FileOutputStream(file, false);
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

    public static boolean isRunning() {
        ServerSocket server = listener;
        return server != null && !server.isClosed();
    }

    private static void bind() throws Exception {
        int preferred = preferredPort(appContext);
        ServerSocket server = z5.isEnabled(appContext)
                ? z5.createUnboundServerSocket(appContext)
                : new ServerSocket();
        server.setReuseAddress(true);
        try {
            server.bind(new java.net.InetSocketAddress(
                    InetAddress.getByName("0.0.0.0"), preferred), 64);
        } catch (IOException busy) {
            // Preferred port taken: fall back to an ephemeral port rather than failing.
            try { server.close(); } catch (Throwable ignored) {}
            server = z5.isEnabled(appContext)
                    ? z5.createUnboundServerSocket(appContext)
                    : new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(
                    InetAddress.getByName("0.0.0.0"), 0), 64);
        }
        listener = server;
        boundPort = server.getLocalPort();
        workers = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "dq0-api-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
        Thread acceptor = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "dq0-api-accept");
        acceptor.setDaemon(true);
        acceptThread = acceptor;
        acceptor.start();
        ensureApiKey(appContext);
        STARTED_AT.set(System.currentTimeMillis());
        publishStatus();
        publishInfo();
        appendLog("listener started on " + rootEndpoint() + "/v1");
        try {
            Main.log("[local-api] listening on port " + boundPort
                    + " protocol=" + protocolMode());
        } catch (Throwable ignored) {}
    }

    private static void acceptLoop() {
        while (!stopping) {
            ServerSocket server = listener;
            if (server == null || server.isClosed()) return;
            Socket socket = null;
            try {
                socket = server.accept();
            } catch (IOException error) {
                if (stopping || server.isClosed()) return;
                continue;
            }
            final Socket client = socket;
            ExecutorService pool = workers;
            if (pool == null) {
                closeQuietly(client);
                return;
            }
            try {
                pool.execute(new Runnable() {
                    @Override public void run() { serve(client); }
                });
            } catch (Throwable rejected) {
                closeQuietly(client);
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Endpoint reporting (read reflectively by z13)
    // ---------------------------------------------------------------------------------

    public static String endpoint() {
        return openAiEndpoint();
    }

    public static String openAiEndpoint() {
        return rootEndpoint() + "/v1";
    }

    public static String rootEndpoint() {
        return z5.scheme(appContext) + "://127.0.0.1:" + port();
    }

    public static String lanRootEndpoint() {
        String address = lanAddress();
        return address == null ? rootEndpoint()
                : z5.scheme(appContext) + "://" + address + ":" + port();
    }

    public static String lanEndpoint() {
        return lanRootEndpoint() + "/v1";
    }

    public static int port() {
        return boundPort;
    }

    public static int tlsPort() {
        return z5.isEnabled(appContext) ? boundPort : 0;
    }

    public static boolean isHttpsRunning() {
        return z5.isEnabled(appContext) && isRunning();
    }

    public static String protocolMode() {
        String value = getString(KEY_PROTOCOL, PROTOCOL_OPENAI);
        return PROTOCOL_ANTHROPIC.equals(value) ? PROTOCOL_ANTHROPIC : PROTOCOL_OPENAI;
    }

    public static String apiKey() {
        return getString(KEY_API_KEY, "");
    }

    public static String connectionInfo() {
        return "base=" + openAiEndpoint() + "\nprotocol=" + protocolMode()
                + "\nkey=" + apiKey();
    }

    public static String runtimeStatus() {
        if (isRunning()) {
            return "running port=" + port() + " protocol=" + protocolMode();
        }
        return runtimeDiagnostic.length() == 0 ? "stopped" : runtimeDiagnostic;
    }

    public static int preferredPort(Context context) {
        SharedPreferences store = store(context);
        if (store == null) return DEFAULT_PORT;
        int fallback = store.getInt(KEY_PORT, DEFAULT_PORT);
        return store.getInt(KEY_PREFERRED_PORT, fallback);
    }

    public static int preferredPort() {
        return preferredPort(appContext);
    }

    public static void setProtocolMode(Context context, String requested) {
        String normalized = PROTOCOL_ANTHROPIC.equals(requested)
                ? PROTOCOL_ANTHROPIC : PROTOCOL_OPENAI;
        SharedPreferences store = store(context);
        if (store != null) store.edit().putString(KEY_PROTOCOL, normalized).apply();
    }

    public static String setPreferredPort(Context context, int requestedPort) {
        if (requestedPort < 0 || requestedPort > 65535) {
            return "端口必须在 0-65535 之间";
        }
        SharedPreferences store = store(context);
        if (store == null) return "模块上下文不可用";
        store.edit().putInt(KEY_PREFERRED_PORT, requestedPort).apply();
        // Rebind so the change takes effect immediately.
        synchronized (z1.class) {
            if (isRunning()) {
                stop();
                try { start(appContext, backend); }
                catch (Throwable error) { return "重启失败：" + safe(error); }
            }
        }
        return null;
    }

    public static String rotateKey(Context context) {
        String generated = randomKey();
        SharedPreferences store = store(context);
        if (store != null) store.edit().putString(KEY_API_KEY, generated).apply();
        return generated;
    }

    public static String setCustomKey(Context context, String candidate) {
        String value = candidate == null ? "" : candidate.trim();
        if (value.length() < 8) {
            return "API Key 至少需要 8 个字符";
        }
        SharedPreferences store = store(context);
        if (store == null) return "模块上下文不可用";
        store.edit().putString(KEY_API_KEY, value).apply();
        return value;
    }

    public static void diagnostic(String message) {
        try { Main.log("[local-api] " + message); } catch (Throwable ignored) {}
    }

    public static void ioDiagnostic(String requestId, String tag, String content) {
        try {
            Main.log("[local-api][" + requestId + "] " + tag + " " + content);
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------------------------
    // Stream generation guards (reflectively called by z13)
    // ---------------------------------------------------------------------------------

    public static z2.DeltaSink nextStreamGenerationForRetry(z2.DeltaSink sink) {
        return sink;
    }

    public static z2.DeltaSink newGenerationGuard(AtomicLong epoch, long generation,
                                                  z2.DeltaSink sink, String requestId) {
        return new GenerationGuard(epoch, generation, sink, requestId);
    }

    public static boolean looksStructurallyTruncatedText(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.length() == 0) return false;
        // A response that stopped inside an unterminated code fence is the common
        // truncation shape for agent payloads.
        int fences = 0;
        int index = -1;
        while ((index = trimmed.indexOf("```", index + 1)) >= 0) fences++;
        return (fences % 2) != 0;
    }

    // ---------------------------------------------------------------------------------
    // HTTP handling
    // ---------------------------------------------------------------------------------

    private static void serve(Socket socket) {
        OutputStream rawOut = null;
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
            rawOut = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);

            while (!stopping) {
                HttpRequest request = readRequest(in);
                if (request == null) return; // client closed / keep-alive finished
                dispatch(request, rawOut);
                rawOut.flush();
                if (request.closeAfter) return;
            }
        } catch (Throwable error) {
            runtimeDiagnostic = "serve: " + safe(error);
        } finally {
            closeQuietly(rawOut);
            closeQuietly(socket);
        }
    }

    private static void dispatch(HttpRequest request, OutputStream out) throws IOException {
        String path = normalizePath(request.path);
        String method = request.method;

        if ("OPTIONS".equals(method)) {
            writeEmpty(out, 204, request.keepAlive, null);
            return;
        }
        if (!authorized(request)) {
            writeJson(out, 401, request.keepAlive, errorBody(
                    "invalid_api_key", "authentication_error",
                    "Missing or invalid API key"), null);
            return;
        }
        if (("GET".equals(method) || "POST".equals(method))
                && (path.equals("/v1/models") || path.equals("/models"))) {
            writeJson(out, 200, request.keepAlive, modelsBody(), null);
            return;
        }
        if ("POST".equals(method) && (path.equals("/v1/chat/completions")
                || path.equals("/chat/completions"))) {
            handleChatCompletions(request, out);
            return;
        }
        if ("POST".equals(method) && (path.equals("/v1/responses")
                || path.equals("/responses"))) {
            handleResponses(request, out);
            return;
        }
        if ("POST".equals(method) && (path.equals("/v1/messages")
                || path.equals("/messages"))) {
            handleAnthropicMessages(request, out);
            return;
        }
        if ("POST".equals(method) && path.equals("/v1/messages/count_tokens")) {
            handleCountTokens(request, out);
            return;
        }
        writeJson(out, 404, request.keepAlive, errorBody(
                "not_found", "invalid_request_error", "Unknown path: " + request.path), null);
    }

    // ---------------------------------------------------------------------------------
    // OpenAI Chat Completions
    // ---------------------------------------------------------------------------------

    private static void handleChatCompletions(HttpRequest request, OutputStream out)
            throws IOException {
        String requestId = nextRequestId();
        JSONObject body;
        try {
            body = parseJson(request.body);
        } catch (Throwable error) {
            writeJson(out, 400, request.keepAlive, errorBody(
                    "invalid_json", "invalid_request_error", safe(error)), null);
            return;
        }
        if (PROTOCOL_ANTHROPIC.equals(protocolMode())) {
            writeJson(out, 400, request.keepAlive, errorBody(
                    "protocol_mismatch", "invalid_request_error",
                    "Server is configured for the Anthropic protocol; use /v1/messages"), null);
            return;
        }
        try {
            z2.CompletionRequest completion = buildOpenAiRequest(requestId, body);
            boolean stream = body.optBoolean("stream", false);
            execute(completion, stream, out, request.keepAlive, requestId,
                    Renderer.OPENAI_CHAT);
        } catch (z2.GatewayException error) {
            writeJson(out, error.status, request.keepAlive,
                    errorBody(error.code, error.type, error.getMessage()), null);
        } catch (Throwable error) {
            writeJson(out, 500, request.keepAlive, errorBody(
                    "internal_error", "server_error", safe(error)), null);
        }
    }

    // ---------------------------------------------------------------------------------
    // OpenAI Responses
    // ---------------------------------------------------------------------------------

    private static void handleResponses(HttpRequest request, OutputStream out)
            throws IOException {
        String requestId = nextRequestId();
        JSONObject body;
        try {
            body = parseJson(request.body);
        } catch (Throwable error) {
            writeJson(out, 400, request.keepAlive, errorBody(
                    "invalid_json", "invalid_request_error", safe(error)), null);
            return;
        }
        if (PROTOCOL_ANTHROPIC.equals(protocolMode())) {
            writeJson(out, 400, request.keepAlive, errorBody(
                    "protocol_mismatch", "invalid_request_error",
                    "Server is configured for the Anthropic protocol; use /v1/messages"), null);
            return;
        }
        try {
            z2.CompletionRequest completion = buildResponsesRequest(requestId, body);
            boolean stream = body.optBoolean("stream", false);
            execute(completion, stream, out, request.keepAlive, requestId,
                    Renderer.OPENAI_RESPONSES);
        } catch (z2.GatewayException error) {
            writeJson(out, error.status, request.keepAlive,
                    errorBody(error.code, error.type, error.getMessage()), null);
        } catch (Throwable error) {
            writeJson(out, 500, request.keepAlive, errorBody(
                    "internal_error", "server_error", safe(error)), null);
        }
    }

    // ---------------------------------------------------------------------------------
    // Anthropic Messages
    // ---------------------------------------------------------------------------------

    private static void handleAnthropicMessages(HttpRequest request, OutputStream out)
            throws IOException {
        String requestId = nextRequestId();
        JSONObject body;
        try {
            body = parseJson(request.body);
        } catch (Throwable error) {
            writeJson(out, 400, request.keepAlive, anthropicError(
                    "invalid_request_error", safe(error)), null);
            return;
        }
        if (!PROTOCOL_ANTHROPIC.equals(protocolMode())) {
            writeJson(out, 400, request.keepAlive, anthropicError(
                    "invalid_request_error",
                    "Server is configured for the OpenAI protocol; use /v1/chat/completions"),
                    null);
            return;
        }
        try {
            z2.CompletionRequest completion = buildAnthropicRequest(requestId, body);
            boolean stream = body.optBoolean("stream", false);
            execute(completion, stream, out, request.keepAlive, requestId,
                    Renderer.ANTHROPIC);
        } catch (z2.GatewayException error) {
            writeJson(out, error.status, request.keepAlive,
                    anthropicError(error.type, error.getMessage()), null);
        } catch (Throwable error) {
            writeJson(out, 500, request.keepAlive,
                    anthropicError("api_error", safe(error)), null);
        }
    }

    private static void handleCountTokens(HttpRequest request, OutputStream out)
            throws IOException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        try {
            JSONObject body = parseJson(request.body);
            int tokens = estimateTokens(flattenAnthropicInput(body));
            JSONObject result = new JSONObject();
            result.put("input_tokens", tokens);
            writeJson(out, 200, request.keepAlive, result.toString(), headers);
        } catch (Throwable error) {
            writeJson(out, 400, request.keepAlive,
                    anthropicError("invalid_request_error", safe(error)), null);
        }
    }

    // ---------------------------------------------------------------------------------
    // Request construction
    // ---------------------------------------------------------------------------------

    private static z2.CompletionRequest buildOpenAiRequest(String requestId, JSONObject body)
            throws Exception {
        String model = body.optString("model", DEFAULT_OPENAI_MODEL);
        List<JSONObject> messages = new ArrayList<JSONObject>();
        JSONArray input = body.optJSONArray("messages");
        if (input != null) {
            for (int i = 0; i < input.length(); i++) {
                JSONObject message = input.optJSONObject(i);
                if (message != null) messages.add(message);
            }
        }
        Prompt prompt = renderOpenAiPrompt(messages);
        boolean reasoning = requestWantsReasoning(body);
        boolean search = body.optBoolean("web_search", false)
                || body.optBoolean("search", false);
        int maxTokens = body.optInt("max_tokens",
                body.optInt("max_completion_tokens", 0));
        z2.CompletionRequest request = new z2.CompletionRequest(
                requestId, model, model, prompt.base, prompt.full,
                reasoning, search, maxTokens, null, null, false);
        return decorate(request, body, prompt);
    }

    private static z2.CompletionRequest buildResponsesRequest(String requestId, JSONObject body)
            throws Exception {
        String model = body.optString("model", DEFAULT_OPENAI_MODEL);
        JSONArray input = body.optJSONArray("input");
        List<JSONObject> messages = new ArrayList<JSONObject>();
        if (input != null) {
            for (int i = 0; i < input.length(); i++) {
                Object entry = input.opt(i);
                if (entry instanceof JSONObject) {
                    JSONObject message = (JSONObject) entry;
                    String role = message.optString("role", "user");
                    messages.add(new JSONObject()
                            .put("role", role)
                            .put("content", message.opt("content")));
                } else if (entry instanceof String) {
                    messages.add(new JSONObject()
                            .put("role", "user")
                            .put("content", (String) entry));
                }
            }
        } else if (body.has("input")) {
            // The Responses API also accepts a bare string for input.
            Object raw = body.opt("input");
            if (raw != null && !JSONObject.NULL.equals(raw)) {
                messages.add(new JSONObject()
                        .put("role", "user")
                        .put("content", stringify(raw)));
            }
        }
        String instructions = body.optString("instructions", "");
        if (instructions.length() > 0) {
            messages.add(0, new JSONObject()
                    .put("role", "system").put("content", instructions));
        }
        Prompt prompt = renderOpenAiPrompt(messages);
        boolean reasoning = requestWantsReasoning(body);
        int maxTokens = body.optInt("max_output_tokens", 0);
        z2.CompletionRequest request = new z2.CompletionRequest(
                requestId, model, model, prompt.base, prompt.full,
                reasoning, false, maxTokens, null,
                body.optString("previous_response_id", null), true);
        return decorate(request, body, prompt);
    }

    private static z2.CompletionRequest buildAnthropicRequest(String requestId, JSONObject body)
            throws Exception {
        String model = body.optString("model", DEFAULT_ANTHROPIC_MODEL);
        String system = extractAnthropicSystem(body);
        List<JSONObject> messages = new ArrayList<JSONObject>();
        JSONArray input = body.optJSONArray("messages");
        if (input != null) {
            for (int i = 0; i < input.length(); i++) {
                JSONObject message = input.optJSONObject(i);
                if (message != null) messages.add(message);
            }
        }
        Prompt prompt = renderAnthropicPrompt(system, messages);
        boolean reasoning = anthropicWantsReasoning(body);
        int maxTokens = body.optInt("max_tokens", 0);
        z2.CompletionRequest request = new z2.CompletionRequest(
                requestId, model, model, prompt.base, prompt.full,
                reasoning, false, maxTokens, null, null, false);
        JSONObject metadata = body.optJSONObject("metadata");
        if (metadata != null) {
            String session = metadata.optString("user_id", "");
            if (session.length() > 0) {
                request = request.withClientSessionScope("anthropic:" + session);
            }
        }
        return decorate(request, body, prompt);
    }

    private static z2.CompletionRequest decorate(z2.CompletionRequest request,
                                                 JSONObject body, Prompt prompt) {
        z2.CompletionRequest result = request;
        if (prompt.toolPlan != null) {
            result = result.withToolHistory(null);
        }
        if (prompt.hasImages) {
            result = result.withInputImages(prompt.images);
        }
        return result;
    }

    // ---------------------------------------------------------------------------------
    // Prompt rendering
    // ---------------------------------------------------------------------------------

    private static final class Prompt {
        String base = "";
        String full = "";
        z2.ToolPlan toolPlan;
        boolean hasImages;
        List<z4.Attachment> images = Collections.emptyList();
    }

    private static Prompt renderOpenAiPrompt(List<JSONObject> messages) {
        Prompt prompt = new Prompt();
        StringBuilder base = new StringBuilder();
        for (JSONObject message : messages) {
            String role = message.optString("role", "user");
            String content = extractTextContent(message.opt("content"), base);
            if (content.length() == 0) continue;
            base.append(roleTag(role)).append(": ").append(content).append('\n');
        }
        prompt.base = base.toString().trim();
        prompt.full = prompt.base;
        return prompt;
    }

    private static Prompt renderAnthropicPrompt(String system, List<JSONObject> messages) {
        Prompt prompt = new Prompt();
        StringBuilder base = new StringBuilder();
        if (system.length() > 0) {
            base.append("system: ").append(system).append('\n');
        }
        for (JSONObject message : messages) {
            String role = message.optString("role", "user");
            String content = extractTextContent(message.opt("content"), base);
            if (content.length() == 0) continue;
            base.append(roleTag(role)).append(": ").append(content).append('\n');
        }
        prompt.base = base.toString().trim();
        prompt.full = prompt.base;
        return prompt;
    }

    private static String roleTag(String role) {
        if ("system".equals(role)) return "system";
        if ("assistant".equals(role)) return "assistant";
        if ("tool".equals(role)) return "tool";
        return "user";
    }

    /** Flattens a string / part-array content value into plain text. */
    private static String extractTextContent(Object content, StringBuilder sink) {
        if (content == null) return "";
        if (content instanceof String) return ((String) content).trim();
        if (!(content instanceof JSONArray)) return String.valueOf(content).trim();
        JSONArray parts = (JSONArray) content;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            Object entry = parts.opt(i);
            if (entry instanceof String) {
                appendLine(text, (String) entry);
                continue;
            }
            if (!(entry instanceof JSONObject)) continue;
            JSONObject part = (JSONObject) entry;
            String type = part.optString("type", "text");
            if ("text".equals(type) || "input_text".equals(type)
                    || "output_text".equals(type)) {
                appendLine(text, part.optString("text", ""));
            } else if ("tool_result".equals(type)) {
                appendLine(text, stringify(part.opt("content")));
            } else if ("tool_use".equals(type)) {
                appendLine(text, JSON_TAG + part.optString("name", "tool")
                        + " " + stringify(part.opt("input")));
            } else if ("thinking".equals(type)) {
                appendLine(text, part.optString("thinking", ""));
            }
        }
        return text.toString().trim();
    }

    private static final String JSON_TAG = "[tool-use]";

    private static String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof JSONArray) {
            StringBuilder text = new StringBuilder();
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                appendLine(text, stringify(array.opt(i)));
            }
            return text.toString().trim();
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("text")) return object.optString("text", "");
            return object.toString();
        }
        return String.valueOf(value);
    }

    private static String extractAnthropicSystem(JSONObject body) {
        Object system = body.opt("system");
        if (system == null) return "";
        if (system instanceof String) return ((String) system).trim();
        return stringify(system);
    }

    private static String flattenAnthropicInput(JSONObject body) {
        StringBuilder text = new StringBuilder();
        String system = extractAnthropicSystem(body);
        if (system.length() > 0) appendLine(text, system);
        JSONArray messages = body.optJSONArray("messages");
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) continue;
                appendLine(text, extractTextContent(message.opt("content"), text));
            }
        }
        return text.toString();
    }

    private static boolean requestWantsReasoning(JSONObject body) {
        if (body.has("reasoning")) {
            Object reasoning = body.opt("reasoning");
            if (reasoning instanceof JSONObject) {
                String effort = ((JSONObject) reasoning).optString("effort", "");
                return effort.length() > 0 && !"none".equals(effort);
            }
            if (reasoning instanceof Boolean) return (Boolean) reasoning;
        }
        return body.optBoolean("thinking", false);
    }

    private static boolean anthropicWantsReasoning(JSONObject body) {
        Object thinking = body.opt("thinking");
        if (thinking instanceof JSONObject) {
            String type = ((JSONObject) thinking).optString("type", "");
            return "enabled".equals(type) || "adaptive".equals(type);
        }
        return false;
    }

    // ---------------------------------------------------------------------------------
    // Execution + rendering
    // ---------------------------------------------------------------------------------

    private enum Renderer { OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC }

    private static void execute(final z2.CompletionRequest request, final boolean stream,
                                final OutputStream out, final boolean keepAlive,
                                final String requestId, final Renderer renderer)
            throws Exception {
        z2.Backend target = backend;
        if (target == null) {
            throw new z2.GatewayException(503, "host_not_ready", "server_error",
                    "Local API backend is not attached");
        }
        if (!target.isReady()) {
            throw new z2.GatewayException(503, "host_not_ready", "server_error",
                    target.readinessDetail());
        }
        final StreamSink sink = new StreamSink(stream, out, keepAlive, renderer, request);
        long startedAt = System.currentTimeMillis();
        TOTAL_REQUESTS.incrementAndGet();
        if (stream) STREAMING_REQUESTS.incrementAndGet();
        if (request.reasoning) REASONING_REQUESTS.incrementAndGet();
        try {
            z2.CompletionResult result = target.complete(request, sink);
            sink.finish(result);
            SUCCESSFUL_REQUESTS.incrementAndGet();
        } catch (z2.GatewayException error) {
            FAILED_REQUESTS.incrementAndGet();
            sink.fail(error.status, renderer == Renderer.ANTHROPIC
                    ? anthropicError(error.type, error.getMessage())
                    : errorBody(error.code, error.type, error.getMessage()));
        } catch (Throwable error) {
            FAILED_REQUESTS.incrementAndGet();
            sink.fail(500, renderer == Renderer.ANTHROPIC
                    ? anthropicError("api_error", safe(error))
                    : errorBody("internal_error", "server_error", safe(error)));
        } finally {
            long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
            LATENCY_SUM_MS.addAndGet(elapsed);
            MAX_LATENCY_MS.accumulateAndGet(elapsed, Math::max);
            appendLog(requestId + " " + renderer.name().toLowerCase(Locale.US)
                    + (stream ? " stream" : "") + " " + elapsed + "ms");
            publishStatus();
            publishInfo();
        }
    }

    /**
     * Bridges {@link z2.DeltaSink} callbacks to either an SSE stream or a single buffered
     * JSON response, depending on what the client asked for.
     */
    private static final class StreamSink implements z2.DeltaSink {
        private final boolean stream;
        private final OutputStream out;
        private final boolean keepAlive;
        private final Renderer renderer;
        private final String requestId;
        private final String model;
        private final long created;

        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private boolean started;
        private boolean finished;
        private boolean cancelled;
        private final AtomicLong lastHeartbeat = new AtomicLong(System.currentTimeMillis());

        StreamSink(boolean stream, OutputStream out, boolean keepAlive,
                   Renderer renderer, z2.CompletionRequest request) {
            this.stream = stream;
            this.out = out;
            this.keepAlive = keepAlive;
            this.renderer = renderer;
            this.requestId = request.requestId;
            this.model = request.requestedModel;
            this.created = System.currentTimeMillis() / 1000L;
        }

        @Override public void onUpstreamStarted() {
            if (!stream || started) return;
            started = true;
            try {
                writeSseHeaders(out, keepAlive);
                if (renderer == Renderer.ANTHROPIC) {
                    JSONObject message = new JSONObject();
                    message.put("type", "message_start");
                    JSONObject payload = new JSONObject();
                    payload.put("id", "msg_" + requestId);
                    payload.put("type", "message");
                    payload.put("role", "assistant");
                    payload.put("model", model);
                    payload.put("content", new JSONArray());
                    payload.put("stop_reason", JSONObject.NULL);
                    payload.put("usage", new JSONObject()
                            .put("input_tokens", 0).put("output_tokens", 0));
                    message.put("message", payload);
                    sse(out, "message_start", message.toString());
                } else if (renderer == Renderer.OPENAI_RESPONSES) {
                    JSONObject created = new JSONObject();
                    created.put("type", "response.created");
                    created.put("response", new JSONObject()
                            .put("id", "resp_" + requestId)
                            .put("status", "in_progress")
                            .put("model", model));
                    sse(out, "response.created", created.toString());
                } else {
                    JSONObject chunk = baseChatChunk("assistant", null);
                    sse(out, null, chunk.toString());
                }
            } catch (Throwable ignored) {}
        }

        @Override public boolean onText(String delta) {
            if (delta == null || delta.length() == 0) return !cancelled;
            onUpstreamStarted();
            text.append(delta);
            if (stream) {
                emitText(delta, false);
            }
            return !cancelled;
        }

        @Override public boolean onReasoning(String delta) {
            if (delta == null || delta.length() == 0) return !cancelled;
            onUpstreamStarted();
            reasoning.append(delta);
            if (stream) {
                emitReasoning(delta);
            }
            return !cancelled;
        }

        @Override public boolean isCancelled() {
            return cancelled;
        }

        @Override public String publishedTextSnapshot() {
            return text.toString();
        }

        private void emitText(String delta, boolean reasoning) {
            try {
                if (renderer == Renderer.ANTHROPIC) {
                    JSONObject event = new JSONObject();
                    event.put("type", "content_block_delta");
                    event.put("index", 0);
                    event.put("delta", new JSONObject()
                            .put("type", "text_delta").put("text", delta));
                    sse(out, "content_block_delta", event.toString());
                } else if (renderer == Renderer.OPENAI_RESPONSES) {
                    JSONObject event = new JSONObject();
                    event.put("type", "response.output_text.delta");
                    event.put("delta", delta);
                    sse(out, "response.output_text.delta", event.toString());
                } else {
                    sse(out, null, baseChatChunk(null, delta).toString());
                }
            } catch (Throwable ignored) {}
        }

        private void emitReasoning(String delta) {
            try {
                if (renderer == Renderer.ANTHROPIC) {
                    JSONObject event = new JSONObject();
                    event.put("type", "content_block_delta");
                    event.put("index", 0);
                    event.put("delta", new JSONObject()
                            .put("type", "thinking_delta").put("thinking", delta));
                    sse(out, "content_block_delta", event.toString());
                } else if (renderer == Renderer.OPENAI_RESPONSES) {
                    JSONObject event = new JSONObject();
                    event.put("type", "response.reasoning_summary_text.delta");
                    event.put("delta", delta);
                    sse(out, "response.reasoning_summary_text.delta", event.toString());
                } else {
                    JSONObject chunk = baseChatChunk(null, null);
                    JSONObject choice = chunk.optJSONArray("choices").optJSONObject(0);
                    choice.put("delta", new JSONObject().put("reasoning_content", delta));
                    sse(out, null, chunk.toString());
                }
            } catch (Throwable ignored) {}
        }

        private JSONObject baseChatChunk(String role, String content) {
            JSONObject chunk = new JSONObject();
            try {
                chunk.put("id", "chatcmpl-" + requestId);
                chunk.put("object", "chat.completion.chunk");
                chunk.put("created", created);
                chunk.put("model", model);
                JSONObject delta = new JSONObject();
                if (role != null) delta.put("role", role);
                if (content != null) delta.put("content", content);
                chunk.put("choices", new JSONArray().put(new JSONObject()
                        .put("index", 0).put("delta", delta)
                        .put("finish_reason", JSONObject.NULL)));
            } catch (Throwable ignored) {}
            return chunk;
        }

        void finish(z2.CompletionResult result) throws IOException {
            if (finished) return;
            finished = true;
            if (stream) {
                if (!started) onUpstreamStarted();
                emitTerminal(result);
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            writeJson(out, 200, keepAlive, nonStreamingBody(result), null);
        }

        private void emitTerminal(z2.CompletionResult result) {
            try {
                if (renderer == Renderer.ANTHROPIC) {
                    JSONObject stop = new JSONObject();
                    stop.put("type", "message_delta");
                    stop.put("delta", new JSONObject()
                            .put("stop_reason", anthropicStop(result.finishReason))
                            .put("stop_sequence", JSONObject.NULL));
                    stop.put("usage", new JSONObject()
                            .put("output_tokens", estimateTokens(text.toString())));
                    sse(out, "message_delta", stop.toString());
                    sse(out, "message_stop",
                            new JSONObject().put("type", "message_stop").toString());
                } else if (renderer == Renderer.OPENAI_RESPONSES) {
                    JSONObject done = new JSONObject();
                    done.put("type", "response.completed");
                    done.put("response", new JSONObject()
                            .put("id", "resp_" + requestId)
                            .put("status", "completed")
                            .put("model", model)
                            .put("output", new JSONArray().put(new JSONObject()
                                    .put("type", "message")
                                    .put("role", "assistant")
                                    .put("content", new JSONArray().put(new JSONObject()
                                            .put("type", "output_text")
                                            .put("text", text.toString()))))));
                    sse(out, "response.completed", done.toString());
                } else {
                    JSONObject chunk = baseChatChunk(null, null);
                    JSONObject choice = chunk.optJSONArray("choices").optJSONObject(0);
                    choice.put("delta", new JSONObject());
                    choice.put("finish_reason", openAiStop(result.finishReason));
                    sse(out, null, chunk.toString());
                }
            } catch (Throwable ignored) {}
        }

        private String nonStreamingBody(z2.CompletionResult result) {
            JSONObject body = new JSONObject();
            try {
                if (renderer == Renderer.ANTHROPIC) {
                    body.put("id", "msg_" + requestId);
                    body.put("type", "message");
                    body.put("role", "assistant");
                    body.put("model", model);
                    JSONArray content = new JSONArray();
                    if (reasoning.length() > 0) {
                        content.put(new JSONObject()
                                .put("type", "thinking").put("thinking", reasoning.toString()));
                    }
                    content.put(new JSONObject()
                            .put("type", "text").put("text", text.toString()));
                    body.put("content", content);
                    body.put("stop_reason", anthropicStop(result.finishReason));
                    body.put("stop_sequence", JSONObject.NULL);
                    body.put("usage", new JSONObject()
                            .put("input_tokens", 0)
                            .put("output_tokens", estimateTokens(text.toString())));
                    return body.toString();
                }
                if (renderer == Renderer.OPENAI_RESPONSES) {
                    body.put("id", "resp_" + requestId);
                    body.put("object", "response");
                    body.put("status", "completed");
                    body.put("model", model);
                    body.put("output", new JSONArray().put(new JSONObject()
                            .put("type", "message")
                            .put("role", "assistant")
                            .put("content", new JSONArray().put(new JSONObject()
                                    .put("type", "output_text")
                                    .put("text", text.toString())))));
                    return body.toString();
                }
                body.put("id", "chatcmpl-" + requestId);
                body.put("object", "chat.completion");
                body.put("created", created);
                body.put("model", model);
                JSONObject message = new JSONObject();
                message.put("role", "assistant");
                message.put("content", text.toString());
                if (reasoning.length() > 0) {
                    message.put("reasoning_content", reasoning.toString());
                }
                body.put("choices", new JSONArray().put(new JSONObject()
                        .put("index", 0)
                        .put("message", message)
                        .put("finish_reason", openAiStop(result.finishReason))));
                body.put("usage", new JSONObject()
                        .put("prompt_tokens", 0)
                        .put("completion_tokens", estimateTokens(text.toString()))
                        .put("total_tokens", estimateTokens(text.toString())));
            } catch (Throwable ignored) {}
            return body.toString();
        }

        void fail(int status, String errorJson) throws IOException {
            if (finished) return;
            finished = true;
            if (stream) {
                if (!started) writeSseHeaders(out, keepAlive);
                out.write(("data: " + errorJson + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            writeJson(out, status, keepAlive, errorJson, null);
        }

        @Override public boolean isSatisfied() {
            return finished;
        }
    }

    // ---------------------------------------------------------------------------------
    // Response bodies
    // ---------------------------------------------------------------------------------

    private static String modelsBody() {
        JSONObject body = new JSONObject();
        try {
            JSONArray data = new JSONArray();
            for (String id : modelIds()) {
                data.put(new JSONObject()
                        .put("id", id).put("object", "model")
                        .put("owned_by", "deepseek"));
            }
            body.put("object", "list");
            body.put("data", data);
        } catch (Throwable ignored) {}
        return body.toString();
    }

    /**
     * The module owns the model catalog: it knows which public ids map onto which native
     * model, and which of them the installed host generation still routes.  Defer to it
     * rather than guessing, so the gateway never advertises a stale list.
     */
    private static List<String> modelIds() {
        List<String> ids = catalogModelIds();
        if (ids.isEmpty()) {
            ids.add("deepseek-flash");
        }
        return ids;
    }

    /** Reads {@code Main.localApiModelCatalogJson()} and returns its ids in order. */
    private static List<String> catalogModelIds() {
        List<String> ids = new ArrayList<String>();
        try {
            String json = Main.localApiModelCatalogJson();
            if (json == null || json.length() == 0) return ids;
            JSONArray catalog = new JSONArray(json);
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject entry = catalog.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("id", "").trim();
                if (id.length() > 0 && !ids.contains(id)) ids.add(id);
            }
        } catch (Throwable ignored) {}
        return ids;
    }

    private static String errorBody(String code, String type, String message) {
        JSONObject error = new JSONObject();
        try {
            error.put("message", message == null ? "" : message);
            error.put("type", type == null ? "invalid_request_error" : type);
            error.put("code", code == null ? "invalid_request_error" : code);
            return new JSONObject().put("error", error).toString();
        } catch (Throwable ignored) {
            return "{\"error\":{\"message\":\"internal error\"}}";
        }
    }

    private static String anthropicError(String type, String message) {
        JSONObject error = new JSONObject();
        try {
            error.put("type", "error");
            error.put("error", new JSONObject()
                    .put("type", type == null ? "invalid_request_error" : type)
                    .put("message", message == null ? "" : message));
            return error.toString();
        } catch (Throwable ignored) {
            return "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"\"}}";
        }
    }

    private static String openAiStop(String finishReason) {
        if (finishReason == null) return "stop";
        String value = finishReason.toLowerCase(Locale.US);
        if (value.contains("tool")) return "tool_calls";
        if (value.contains("length") || value.contains("max")) return "length";
        return "stop";
    }

    private static String anthropicStop(String finishReason) {
        if (finishReason == null) return "end_turn";
        String value = finishReason.toLowerCase(Locale.US);
        if (value.contains("tool")) return "tool_use";
        if (value.contains("length") || value.contains("max")) return "max_tokens";
        return "end_turn";
    }

    private static int estimateTokens(String value) {
        if (value == null || value.length() == 0) return 0;
        return Math.max(1, value.length() / 4);
    }

    // ---------------------------------------------------------------------------------
    // HTTP primitives
    // ---------------------------------------------------------------------------------

    private static final class HttpRequest {
        String method = "GET";
        String path = "/";
        String body = "";
        boolean keepAlive = true;
        boolean closeAfter;
        final Map<String, String> headers = new LinkedHashMap<String, String>();
    }

    private static HttpRequest readRequest(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null) return null;
        while (requestLine.length() == 0) {
            requestLine = readLine(in);
            if (requestLine == null) return null;
        }
        HttpRequest request = new HttpRequest();
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) throw new IOException("malformed request line");
        request.method = parts[0].toUpperCase(Locale.US);
        request.path = parts[1];
        String version = parts.length > 2 ? parts[2] : "HTTP/1.1";
        request.keepAlive = !version.contains("1.0");

        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            request.headers.put(name, value);
        }
        String connection = request.headers.get("connection");
        if (connection != null) {
            if (connection.toLowerCase(Locale.US).contains("close")) request.keepAlive = false;
            if (connection.toLowerCase(Locale.US).contains("keep-alive")) request.keepAlive = true;
        }
        request.closeAfter = !request.keepAlive;

        int length = 0;
        String declared = request.headers.get("content-length");
        if (declared != null) {
            try { length = Integer.parseInt(declared.trim()); }
            catch (NumberFormatException error) { throw new IOException("bad content-length"); }
        }
        if (length < 0 || length > MAX_BODY_BYTES) throw new IOException("body too large");
        if (length > 0) {
            byte[] buffer = new byte[length];
            int read = 0;
            while (read < length) {
                int count = in.read(buffer, read, length - read);
                if (count < 0) throw new IOException("truncated body");
                read += count;
            }
            request.body = new String(buffer, StandardCharsets.UTF_8);
        }
        return request;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int value;
        while ((value = in.read()) >= 0) {
            if (value == '\n') {
                byte[] bytes = buffer.toByteArray();
                int end = bytes.length;
                if (end > 0 && bytes[end - 1] == '\r') end--;
                return new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
            }
            buffer.write(value);
            if (buffer.size() > 16 * 1024) throw new IOException("header line too long");
        }
        return buffer.size() == 0 ? null : buffer.toString("ISO-8859-1");
    }

    private static String normalizePath(String path) {
        if (path == null) return "/";
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static boolean authorized(HttpRequest request) {
        String configured = apiKey();
        if (configured == null || configured.length() == 0) return true;
        String authorization = request.headers.get("authorization");
        if (authorization != null) {
            String value = authorization.trim();
            if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                value = value.substring(7).trim();
            }
            if (constantTimeEquals(configured, value)) return true;
        }
        String apiKeyHeader = request.headers.get("x-api-key");
        if (apiKeyHeader != null && constantTimeEquals(configured, apiKeyHeader.trim())) {
            return true;
        }
        return false;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) return false;
        int diff = 0;
        for (int i = 0; i < left.length; i++) diff |= left[i] ^ right[i];
        return diff == 0;
    }

    private static void writeSseHeaders(OutputStream out, boolean keepAlive) throws IOException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "text/event-stream; charset=utf-8");
        headers.put("Cache-Control", "no-cache, no-transform");
        headers.put("X-Accel-Buffering", "no");
        headers.put("Connection", keepAlive ? "keep-alive" : "close");
        writeHead(out, 200, "OK", headers, -1);
    }

    private static void sse(OutputStream out, String event, String data) throws IOException {
        StringBuilder frame = new StringBuilder();
        if (event != null) frame.append("event: ").append(event).append('\n');
        frame.append("data: ").append(data).append("\n\n");
        out.write(frame.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void writeJson(OutputStream out, int status, boolean keepAlive,
                                  String body, Map<String, String> extraHeaders)
            throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Cache-Control", "no-store");
        if (extraHeaders != null) headers.putAll(extraHeaders);
        headers.put("Connection", keepAlive ? "keep-alive" : "close");
        writeHead(out, status, statusText(status), headers, bytes.length);
        if (bytes.length > 0) out.write(bytes);
        out.flush();
    }

    private static void writeEmpty(OutputStream out, int status, boolean keepAlive,
                                   Map<String, String> extraHeaders) throws IOException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Headers",
                "Authorization, Content-Type, x-api-key, anthropic-version");
        headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.put("Connection", keepAlive ? "keep-alive" : "close");
        if (extraHeaders != null) headers.putAll(extraHeaders);
        writeHead(out, status, statusText(status), headers, 0);
        out.flush();
    }

    private static void writeHead(OutputStream out, int status, String reason,
                                  Map<String, String> headers, int contentLength)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        if (contentLength >= 0) head.append("Content-Length: ").append(contentLength).append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "OK";
        }
    }

    // ---------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------

    /** Resolve the preferences store for an explicit context, falling back to the app context. */
    private static SharedPreferences store(Context context) {
        Context target = context != null ? context : appContext;
        return target == null ? null : target.getSharedPreferences(PREFS, 0);
    }

    /** Null-safe read: the gateway class is resolvable outside the host process too. */
    private static String getString(String key, String fallback) {
        SharedPreferences store = store(null);
        if (store == null) return fallback;
        String value = store.getString(key, fallback);
        return value == null ? fallback : value;
    }

    private static void ensureApiKey(Context context) {
        if (context == null) return;
        SharedPreferences store = context.getSharedPreferences(PREFS, 0);
        String existing = store.getString(KEY_API_KEY, "");
        if (existing == null || existing.length() == 0) {
            store.edit().putString(KEY_API_KEY, randomKey()).apply();
        }
    }

    private static String randomKey() {
        byte[] buffer = new byte[24];
        RANDOM.nextBytes(buffer);
        StringBuilder builder = new StringBuilder("sk-dq0-");
        for (byte value : buffer) builder.append(String.format(Locale.US, "%02x", value));
        return builder.toString();
    }

    private static String nextRequestId() {
        return Long.toHexString(System.currentTimeMillis()) + "-"
                + Long.toHexString(REQUEST_SEQ.incrementAndGet());
    }

    private static JSONObject parseJson(String body) throws Exception {
        if (body == null || body.trim().length() == 0) return new JSONObject();
        return new JSONObject(body);
    }

    private static void appendLine(StringBuilder sink, String value) {
        if (value == null) return;
        String trimmed = value.trim();
        if (trimmed.length() == 0) return;
        if (sink.length() > 0) sink.append('\n');
        sink.append(trimmed);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Throwable ignored) {}
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.length() == 0 ? "" : ": " + message);
    }

    private static String lanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (network.isLoopback() || !network.isUp()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Delegating sink that drops deltas from a superseded upstream generation. */
    private static final class GenerationGuard implements z2.DeltaSink {
        private final AtomicLong epoch;
        private final long generation;
        private final z2.DeltaSink delegate;
        private final StringBuilder published = new StringBuilder();

        GenerationGuard(AtomicLong epoch, long generation, z2.DeltaSink delegate, String requestId) {
            this.epoch = epoch;
            this.generation = generation;
            this.delegate = delegate;
        }

        private boolean current() {
            return delegate != null
                    && (epoch == null || epoch.get() == generation);
        }

        @Override public void onUpstreamStarted() throws Exception {
            if (current()) delegate.onUpstreamStarted();
        }

        @Override public boolean onText(String delta) throws Exception {
            if (!current()) return false;
            boolean keep = delegate.onText(delta);
            if (keep) published.append(delta);
            return keep;
        }

        @Override public boolean onReasoning(String delta) throws Exception {
            if (!current()) return false;
            return delegate.onReasoning(delta);
        }

        @Override public boolean isCancelled() {
            return !current() || delegate.isCancelled();
        }

        @Override public boolean isSatisfied() {
            return current() && delegate.isSatisfied();
        }

        @Override public String publishedTextSnapshot() {
            return published.length() > 0 ? published.toString()
                    : (delegate == null ? "" : delegate.publishedTextSnapshot());
        }
    }
}