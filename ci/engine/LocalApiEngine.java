package com.dsmod.probe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Open reimplementation of the Local API execution engine.
 *
 * <p>In the shipped Closed edition this is a separate encrypted-payload class that
 * {@code HookAccountLoginSecurity.executeLocalApiCompletion} resolves as
 * {@code com.dsmod.probe.z18}.  It builds a native DeepSeek completion request, mints a
 * proof-of-work, opens a session, drives the transport Flow and streams deltas back to the
 * gateway.</p>
 *
 * <p>This implementation reuses the module's own reviewed primitives
 * ({@link HookAttachmentPipeline#mintCompletionPow}, {@link HookAttachmentPipeline#createThrowawaySession},
 * {@link HookAttachmentPipeline#shallowCloneEw0}, and the transport captured in
 * {@link Main#liveR92}) instead of reconstructing DeepSeek's request class from scratch.
 * Because of that it needs one native request to have been observed first, which the module
 * captures in {@link HookChatPipeline#hookTransport}.  Until then it reports a clear
 * {@code template_missing} error rather than failing obscurely.</p>
 */
public final class LocalApiEngine {

    /** Most recent native completion request observed in the host, used as a field template. */
    private static volatile Object template;

    /** Maps the public API model id onto the host's native model selector. */
    private static final long FLOW_TIMEOUT_MS = 600_000L;

    private LocalApiEngine() {}

    /** Called by the transport hook so the engine has a request template to clone. */
    public static void rememberTemplate(Object request) {
        if (request == null) return;
        boolean first = template == null;
        template = request;
        if (first) logTemplateFields(request);
    }

    /** Logs the field values of a freshly captured template so defaults can be chosen. */
    private static void logTemplateFields(Object request) {
        try {
            StringBuilder out = new StringBuilder("[engine] template fields");
            for (char name = 'a'; name <= 'k'; name++) {
                Object value = MainReflectionSupport.fieldByName(request, String.valueOf(name));
                String rendered;
                if (value == null) {
                    rendered = "null";
                } else if (value instanceof String) {
                    rendered = "String(len=" + ((String) value).length() + ", \""
                            + MainReflectionSupport.truncateForLog((String) value, 60) + "\")";
                } else if (value instanceof java.util.List) {
                    rendered = "List(size=" + ((java.util.List<?>) value).size() + ")";
                } else {
                    rendered = value.getClass().getSimpleName() + "=" + value;
                }
                out.append("\n  ").append(name).append(" = ").append(rendered);
            }
            Main.log(out.toString());
        } catch (Throwable error) {
            Main.log("[engine] template dump failed: " + Main.safeThrowableMessage(error));
        }
    }

    public static boolean hasTemplate() {
        return template != null;
    }

    /** Reflective entry point expected by {@code HookAccountLoginSecurity}. */
    public static z2.CompletionResult executeLocalApiCompletion(
            z2.CompletionRequest request, z2.DeltaSink sink) throws Exception {
        if (request == null) {
            throw new z2.GatewayException(400, "invalid_request", "invalid_request_error",
                    "Empty completion request");
        }
        Object transport = Main.liveR92;
        Object powManager = Main.liveQ71;
        ClassLoader loader = Main.hostClassLoader;
        if (transport == null || powManager == null || loader == null) {
            throw new z2.GatewayException(503, "host_not_ready", "server_error",
                    "Native transport not captured yet; open DeepSeek and send one message");
        }

        HookAttachmentPipeline pipeline = HookAttachmentPipeline.INSTANCE;
        String sessionId = null;
        try {
            Object pow;
            try {
                pow = pipeline.mintCompletionPow(loader, powManager);
            } catch (Throwable error) {
                throw new z2.GatewayException(502, "pow_failed", "server_error",
                        "Proof-of-work mint failed: " + Main.safeThrowableMessage(error));
            }
            if (!(pow instanceof String) || ((String) pow).length() == 0) {
                throw new z2.GatewayException(502, "pow_failed", "server_error",
                        "Proof-of-work mint failed");
            }

            sessionId = pipeline.createThrowawaySession(loader, transport);
            if (sessionId == null) {
                throw new z2.GatewayException(502, "session_failed", "server_error",
                        "Native session create failed: " + Main.localApiLastSessionError);
            }

            Object nativeRequest = buildRequest(loader, pipeline, request, sessionId, pow);
            if (nativeRequest == null) {
                throw new z2.GatewayException(500, "request_build_failed", "server_error",
                        "Could not build a native completion request");
            }

            Object flow = invokeTransport(transport, nativeRequest);
            if (flow == null) {
                throw new z2.GatewayException(502, "transport_failed", "server_error",
                        "Native transport returned no stream");
            }

            StringBuilder text = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            collectFlow(loader, flow, sink, text, reasoning);
            return new z2.CompletionResult(text.toString(), reasoning.toString(), "stop");
        } finally {
            if (sessionId != null) {
                try { Main.MODULE.deleteThrowawaySession(loader, transport, sessionId); }
                catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Builds the host's native completion request.
     *
     * <p>Prefers constructing a fresh instance, which needs no prior native traffic.  The
     * constructor taking the session id first is the one the host uses for outbound
     * completions; the fields the gateway does not populate (`g`, `j`) are null and `h` is
     * false in requests the host itself sends.</p>
     *
     * <p>Falls back to cloning a request the host already sent, for host generations whose
     * constructor signature differs from the one inspected here.</p>
     */
    private static Object buildRequest(ClassLoader loader, HookAttachmentPipeline pipeline,
                                       z2.CompletionRequest request, String sessionId, Object pow) {
        Object constructed = constructRequest(loader, request, sessionId, pow);
        if (constructed != null) return constructed;
        Object source = template;
        if (source == null) {
            logRequestClassShape(loader);
            return null;
        }
        Object cloned = pipeline.shallowCloneEw0(source);
        if (cloned == null) return null;
        applyRequest(pipeline, cloned, request, sessionId, pow);
        return cloned;
    }

    /** Instantiates the host request class directly; returns null when the shape is unknown. */
    private static Object constructRequest(ClassLoader loader, z2.CompletionRequest request,
                                           String sessionId, Object pow) {
        try {
            Class<?> requestClass = HostCompat.load(loader, "ew0");
            for (java.lang.reflect.Constructor<?> ctor
                    : requestClass.getDeclaredConstructors()) {
                Class<?>[] types = ctor.getParameterTypes();
                if (types.length != 11) continue;
                if (types[0] != String.class || types[1] != Integer.class
                        || types[2] != String.class
                        || !java.util.List.class.isAssignableFrom(types[3])
                        || types[4] != boolean.class || types[5] != boolean.class
                        || types[6] != String.class || types[7] != boolean.class
                        || types[8] != String.class || types[9] != String.class
                        || types[10] != int.class) continue;
                ctor.setAccessible(true);
                Object built = ctor.newInstance(
                        sessionId,
                        null,
                        request.prompt,
                        new java.util.ArrayList<Object>(),
                        Boolean.valueOf(request.reasoning),
                        Boolean.valueOf(request.search),
                        null,
                        Boolean.FALSE,
                        nativeModel(request.requestedModel),
                        null,
                        Integer.valueOf(0));
                HookAttachmentPipeline.setFieldByName(built, "k", pow);
                return built;
            }
            Main.log("[engine] no usable request constructor on " + requestClass.getName());
        } catch (Throwable error) {
            Main.log("[engine] request construction failed: " + Main.safeThrowableMessage(error));
        }
        return null;
    }

    /** Writes the API request onto the cloned native request object. */
    private static void applyRequest(HookAttachmentPipeline pipeline, Object nativeRequest,
                                     z2.CompletionRequest request, String sessionId, Object pow) {
        HookAttachmentPipeline.setFieldByName(nativeRequest, "a", sessionId);
        HookAttachmentPipeline.setFieldByName(nativeRequest, "b", null);
        HookAttachmentPipeline.setFieldByName(nativeRequest, "c", request.prompt);
        // The host walks this collection unconditionally, so it must be a list, not null.
        HookAttachmentPipeline.setFieldByName(nativeRequest, "d",
                new java.util.ArrayList<Object>());
        HookAttachmentPipeline.setFieldByName(nativeRequest, "e",
                Boolean.valueOf(request.reasoning));
        HookAttachmentPipeline.setFieldByName(nativeRequest, "f",
                Boolean.valueOf(request.search));
        HookAttachmentPipeline.setFieldByName(nativeRequest, "i",
                nativeModel(request.requestedModel));
        HookAttachmentPipeline.setFieldByName(nativeRequest, "k", pow);
    }

    private static String nativeModel(String requested) {
        if (requested == null) return "default";
        // The module's catalog is authoritative: it maps each public id onto the native
        // selector and reflects which models the installed host generation still routes.
        try {
            String json = Main.localApiModelCatalogJson();
            if (json != null && json.length() > 0) {
                org.json.JSONArray catalog = new org.json.JSONArray(json);
                for (int i = 0; i < catalog.length(); i++) {
                    org.json.JSONObject entry = catalog.optJSONObject(i);
                    if (entry == null) continue;
                    String id = entry.optString("id", "").trim();
                    if (!id.equalsIgnoreCase(requested.trim())) continue;
                    String nativeId = entry.optString("native_model", "").trim();
                    if (nativeId.length() > 0) return nativeId;
                }
            }
        } catch (Throwable ignored) {}
        String value = requested.toLowerCase(java.util.Locale.US);
        if (value.contains("pro") || value.contains("expert")) return "expert";
        if (value.contains("vision")) return "vision";
        return "default";
    }

    /**
     * Logs the shape of the host's completion-request class.
     *
     * <p>The engine clones a captured instance, so it needs no constructor knowledge to run.
     * This dump exists to make a missing template diagnosable, and to document what a future
     * from-scratch construction would have to populate.</p>
     */
    private static void logRequestClassShape(ClassLoader loader) {
        try {
            Class<?> requestClass = HostCompat.load(loader, "ew0");
            StringBuilder out = new StringBuilder("[engine] request class ")
                    .append(requestClass.getName());
            for (java.lang.reflect.Constructor<?> ctor : requestClass.getDeclaredConstructors()) {
                out.append("\n  ctor(");
                Class<?>[] types = ctor.getParameterTypes();
                for (int i = 0; i < types.length; i++) {
                    if (i > 0) out.append(", ");
                    out.append(types[i].getSimpleName());
                }
                out.append(')');
            }
            for (Field field : requestClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                out.append("\n  field ").append(field.getName())
                        .append(" : ").append(field.getType().getSimpleName());
            }
            Main.log(out.toString());
        } catch (Throwable error) {
            Main.log("[engine] request class shape unavailable: "
                    + Main.safeThrowableMessage(error));
        }
    }

    /** Finds the transport entry point that takes the request and returns a Flow. */
    private static Object invokeTransport(Object transport, Object nativeRequest) throws Exception {
        Method best = selectTransportMethod(transport, nativeRequest);
        if (best == null) {
            throw new z2.GatewayException(502, "transport_failed", "server_error",
                    "No completion entry point found on " + transport.getClass().getName());
        }
        best.setAccessible(true);
        try {
            return best.invoke(transport, nativeRequest, null);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause() == null ? wrapped : wrapped.getCause();
            Main.log("[engine] transport " + best.getName() + " threw: " + cause
                    + "\n" + Main.stackToString(cause));
            throw new z2.GatewayException(502, "transport_failed", "server_error",
                    "Native transport rejected the request: "
                            + Main.safeThrowableMessage(cause));
        }
    }

    /**
     * The completion entry point is the transport method shaped {@code (request, Long) -> Flow}.
     * Prefer that exact shape; only fall back to any two-argument method if it is absent.
     */
    private static Method selectTransportMethod(Object transport, Object nativeRequest) {
        Method assignable = null;
        Method anyTwoArg = null;
        for (Method m : transport.getClass().getDeclaredMethods()) {
            if (m.getParameterTypes().length != 2) continue;
            if (m.getReturnType().isPrimitive()) continue;
            Class<?> first = m.getParameterTypes()[0];
            Class<?> second = m.getParameterTypes()[1];
            boolean boxedSecond = second == Long.class || second == long.class;
            if (boxedSecond && first.isAssignableFrom(nativeRequest.getClass())) return m;
            if (assignable == null && first.isAssignableFrom(nativeRequest.getClass())) {
                assignable = m;
            }
            if (anyTwoArg == null) anyTwoArg = m;
        }
        return assignable != null ? assignable : anyTwoArg;
    }

    /** Tracks which fragment the host is currently appending to. */
    private static final class FragmentState {
        String lastType = "RESPONSE";
    }

    /**
     * Parses one native flow event and emits any text / reasoning delta it carries.
     *
     * <p>Three shapes matter: the initial {@code {"v":{"response":{"fragments":[...]}}}}
     * snapshot, incremental {@code {"p":"...content","o":"APPEND","v":"..."}} updates,
     * and bare {@code {"v":"..."}} deltas.  The last two carry no fragment type, so the
     * type is tracked from the snapshots; without that every reasoning delta after the first
     * is misrouted into the visible answer.</p>
     */
    private static void emitDelta(Object value, z2.DeltaSink sink,
                                  StringBuilder text, StringBuilder reasoning,
                                  FragmentState state) {
        Object event = MainReflectionSupport.fieldByName(value, "a");
        Object payload = event == null ? null
                : MainReflectionSupport.fieldByName(event, "b");
        if (!(payload instanceof String)) return;
        String json = (String) payload;
        if (json.length() == 0 || json.charAt(0) != '{') return;
        try {
            JSONObject object = new JSONObject(json);
            String path = object.optString("p", "");

            // A path update.  The host is inconsistent about the opcode: some content
            // appends carry no "o" at all, so key off the path instead.
            if (path.length() > 0) {
                if (path.endsWith("fragments")) {
                    // A new fragment was opened (for example THINK -> RESPONSE).
                    emitFragments(object.optJSONArray("v"), sink, text, reasoning, state);
                    return;
                }
                if (path.contains("content")) {
                    String delta = object.optString("v", "");
                    if (delta.length() == 0) return;
                    deliver(sink, text, reasoning, delta, isReasoningPath(path)
                            || "THINK".equals(state.lastType));
                }
                return;
            }

            // Bare {"v":"..."} deltas belong to the most recent fragment.
            if (json.startsWith("{\"v\":\"")) {
                String delta = object.optString("v", "");
                if (delta.length() == 0) return;
                deliver(sink, text, reasoning, delta, "THINK".equals(state.lastType));
                return;
            }

            // Initial snapshot: {"v":{"response":{"fragments":[...]}}}.
            JSONObject root = object.optJSONObject("v");
            JSONObject response = root == null ? null : root.optJSONObject("response");
            emitFragments(response == null ? null : response.optJSONArray("fragments"),
                    sink, text, reasoning, state);
        } catch (Throwable error) {
            Main.log("[engine] delta parse failed: " + Main.safeThrowableMessage(error));
        }
    }

    /** Emits the starting content of newly opened fragments and tracks the active type. */
    private static void emitFragments(JSONArray fragments, z2.DeltaSink sink,
                                      StringBuilder text, StringBuilder reasoning,
                                      FragmentState state) throws Exception {
        if (fragments == null) return;
        for (int i = 0; i < fragments.length(); i++) {
            JSONObject fragment = fragments.optJSONObject(i);
            if (fragment == null) continue;
            String type = fragment.optString("type", "RESPONSE");
            state.lastType = type;
            String content = fragment.optString("content", "");
            if (content.length() == 0) continue;
            deliver(sink, text, reasoning, content, "THINK".equals(type));
        }
    }

    private static void deliver(z2.DeltaSink sink, StringBuilder text, StringBuilder reasoning,
                                String delta, boolean think) throws Exception {
        if (think) {
            reasoning.append(delta);
            sink.onReasoning(delta);
        } else {
            text.append(delta);
            sink.onText(delta);
        }
    }

    private static boolean isReasoningPath(String path) {
        String lower = path.toLowerCase(java.util.Locale.US);
        return lower.contains("think") || lower.contains("reason");
    }

    /**
     * Drives the native Flow with a dynamic proxy collector, forwarding text and reasoning
     * deltas to the gateway sink.  Mirrors the proxy technique the module already uses for
     * its image relay.
     */
    private static void collectFlow(ClassLoader loader, Object flow, final z2.DeltaSink sink,
                                    final StringBuilder text, final StringBuilder reasoning)
            throws Exception {
        Method collect = null;
        for (Class<?> itf : MainReflectionSupport.allInterfaces(flow.getClass())) {
            Method candidate = null;
            int twoArg = 0;
            for (Method m : itf.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 2) { candidate = m; twoArg++; }
            }
            if (twoArg == 1 && candidate != null
                    && candidate.getParameterTypes()[1].isInterface()) {
                collect = candidate;
                break;
            }
        }
        if (collect == null) {
            throw new z2.GatewayException(502, "flow_failed", "server_error",
                    "Native Flow interface not recognised");
        }
        final Class<?> collectorClass = collect.getParameterTypes()[0];
        final Class<?> continuationClass = collect.getParameterTypes()[1];

        Class<?> contextClass = null;
        for (Method m : continuationClass.getMethods()) {
            if (m.getParameterTypes().length == 0 && m.getReturnType().isInterface()) {
                contextClass = m.getReturnType();
                break;
            }
        }
        final Object context = contextClass == null ? null
                : Main.emptyContextProxy(loader, contextClass);

        final CountDownLatch done = new CountDownLatch(1);
        final FragmentState state = new FragmentState();
        final HookAttachmentPipeline pipeline = HookAttachmentPipeline.INSTANCE;

        InvocationHandler continuation = new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method m, Object[] args) {
                if (MainReflectionSupport.isObjectMethod(m)) {
                    return MainReflectionSupport.objectMethod(proxy, m, args);
                }
                if (m.getParameterTypes().length == 0) return context;
                done.countDown();
                return null;
            }
        };
        final Object rootContinuation =
                Proxy.newProxyInstance(loader, new Class<?>[]{continuationClass}, continuation);

        InvocationHandler collector = new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method m, Object[] args) {
                if (MainReflectionSupport.isObjectMethod(m)) {
                    return MainReflectionSupport.objectMethod(proxy, m, args);
                }
                if (m.getParameterTypes().length == 2 && args != null && args.length == 2) {
                    try {
                        emitDelta(args[0], sink, text, reasoning, state);
                    } catch (Throwable error) {
                        Main.log("[engine] collector error: " + error);
                    }
                    return null;
                }
                return null;
            }
        };
        Object collectorProxy =
                Proxy.newProxyInstance(loader, new Class<?>[]{collectorClass}, collector);

        collect.setAccessible(true);
        try {
            collect.invoke(flow, collectorProxy, rootContinuation);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause() == null ? wrapped : wrapped.getCause();
            Main.log("[engine] flow collection threw: " + cause + "\n"
                    + Main.stackToString(cause));
            throw new z2.GatewayException(502, "flow_failed", "server_error",
                    "Native stream failed: " + Main.safeThrowableMessage(cause));
        }
        if (!done.await(FLOW_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Main.log("[engine] flow did not complete within " + FLOW_TIMEOUT_MS + "ms");
        }
    }
}
