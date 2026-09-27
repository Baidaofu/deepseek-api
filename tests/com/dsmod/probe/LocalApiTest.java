package com.dsmod.probe;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** End-to-end functional test of the reconstructed Local API gateway on the JVM. */
public final class LocalApiTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        Context context = new Context();

        z2.Backend backend = new z2.Backend() {
            @Override public boolean isReady() { return true; }
            @Override public String readinessDetail() { return "test-ready"; }
            @Override public z2.CompletionResult complete(z2.CompletionRequest req, z2.DeltaSink sink)
                    throws Exception {
                sink.onUpstreamStarted();
                sink.onReasoning("thinking about " + req.requestedModel);
                String reply = "echo<" + req.prompt.replace('\n', '|') + ">";
                for (int i = 0; i < reply.length(); i += 7) {
                    sink.onText(reply.substring(i, Math.min(reply.length(), i + 7)));
                }
                return new z2.CompletionResult(reply, "thinking", "stop");
            }
        };

        z1.start(context, backend);
        int port = z1.port();
        check("server started on a real port", port > 0 && z1.isRunning());
        check("endpoint reports /v1", z1.endpoint().endsWith("/v1"));
        check("api key auto-generated", z1.apiKey().startsWith("sk-dq0-"));
        check("root endpoint is loopback http", z1.rootEndpoint().startsWith("http://127.0.0.1:"));

        z1.setProtocolMode(context, z2.PROTOCOL_OPENAI);
        check("protocol switches to openai", z2.PROTOCOL_OPENAI.equals(z1.protocolMode()));

        String models = call(port, "GET", "/v1/models", null, z1.apiKey(), null);
        check("GET /v1/models lists the unified deepseek-flash id",
                models.contains("deepseek-flash"));
        check("GET /v1/models keeps the legacy aliases",
                models.contains("deepseek-v4-flash") && models.contains("deepseek-v4-pro"));
        check("GET /v1/models is an object list", models.contains("\"object\":\"list\""));

        String chat = call(port, "POST", "/v1/chat/completions",
                "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hello world\"}]}",
                z1.apiKey(), null);
        check("chat completion echoes prompt", chat.contains("echo<user: hello world>"));
        check("chat completion has assistant message", chat.contains("\"role\":\"assistant\""));
        check("chat completion is not a stream", chat.contains("\"object\":\"chat.completion\""));

        String sse = call(port, "POST", "/v1/chat/completions",
                "{\"model\":\"deepseek-v4-flash\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"stream me\"}]}",
                z1.apiKey(), null);
        check("SSE contains chat.completion.chunk", sse.contains("chat.completion.chunk"));
        check("SSE contains reasoning_content", sse.contains("reasoning_content"));
        check("SSE terminates with [DONE]", sse.trim().endsWith("data: [DONE]"));

        String responses = call(port, "POST", "/v1/responses",
                "{\"model\":\"deepseek-v4-flash\",\"input\":\"responses api call\"}",
                z1.apiKey(), null);
        check("responses returns output_text", responses.contains("\"type\":\"output_text\""));
        check("responses echoes prompt", responses.contains("responses api call"));

        z1.setProtocolMode(context, z2.PROTOCOL_ANTHROPIC);
        check("protocol switches to anthropic", z2.PROTOCOL_ANTHROPIC.equals(z1.protocolMode()));

        String mismatch = call(port, "POST", "/v1/chat/completions",
                "{\"model\":\"x\",\"messages\":[]}", z1.apiKey(), null);
        check("openai route rejected under anthropic mode", mismatch.contains("protocol_mismatch"));

        String messages = call(port, "POST", "/v1/messages",
                "{\"model\":\"deepseek-v4-flash\",\"max_tokens\":100,\"thinking\":{\"type\":\"enabled\"},"
              + "\"messages\":[{\"role\":\"user\",\"content\":\"anthropic hello\"}]}",
                null, z1.apiKey());
        check("anthropic message returns message object", messages.contains("\"type\":\"message\""));
        check("anthropic echoes prompt", messages.contains("anthropic hello"));
        check("anthropic includes thinking block", messages.contains("\"type\":\"thinking\""));
        check("anthropic stop_reason end_turn", messages.contains("\"stop_reason\":\"end_turn\""));

        String count = call(port, "POST", "/v1/messages/count_tokens",
                "{\"model\":\"x\",\"messages\":[{\"role\":\"user\",\"content\":\"count these tokens\"}]}",
                null, z1.apiKey());
        check("count_tokens returns input_tokens", count.contains("\"input_tokens\""));

        String noKey = call(port, "GET", "/v1/models", null, null, null);
        check("missing key is rejected", noKey.contains("invalid_api_key"));
        String badKey = call(port, "GET", "/v1/models", null, "wrong-key", null);
        check("wrong key is rejected", badKey.contains("invalid_api_key"));

        String before = z1.apiKey();
        String rotated = z1.rotateKey(context);
        check("rotateKey changes the key", rotated != null && !rotated.equals(before));
        check("rotated key accepted", call(port, "GET", "/v1/models", null, rotated, null)
                .contains("deepseek-v4-flash"));
        check("old key now rejected", call(port, "GET", "/v1/models", null, before, null)
                .contains("invalid_api_key"));
        check("setCustomKey rejects short key", z1.setCustomKey(context, "abc") != null);
        check("setCustomKey accepts long key", "sk-custom-long-enough".equals(
                z1.setCustomKey(context, "sk-custom-long-enough")));

        String notFound = call(port, "POST", "/v1/nope", "{}", z1.apiKey(), null);
        check("unknown path is 404-ish", notFound.contains("not_found"));

        z1.stop();
        check("stop() halts the server", !z1.isRunning());

        System.out.println();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static void check(String name, boolean condition) {
        if (condition) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }

    /** Minimal raw HTTP client; header/auth mode selects Bearer vs x-api-key. */
    static String call(int port, String method, String path, String body,
                       String bearer, String xApiKey) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(15000);
        try {
            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: 127.0.0.1\r\n");
            head.append("Connection: close\r\n");
            if (bearer != null) head.append("Authorization: Bearer ").append(bearer).append("\r\n");
            if (xApiKey != null) head.append("x-api-key: ").append(xApiKey).append("\r\n");
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (payload.length > 0) head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(payload.length).append("\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (payload.length > 0) out.write(payload);
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = in.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            String raw = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            int split = raw.indexOf("\r\n\r\n");
            return split < 0 ? raw : raw.substring(split + 4);
        } finally {
            socket.close();
        }
    }
}