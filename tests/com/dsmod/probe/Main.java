package com.dsmod.probe;
/** Minimal test shim: only the members z1 actually touches. */
public final class Main {
    public static final java.util.List<String> LOG = new java.util.ArrayList<String>();
    public static void log(String message) { LOG.add(message); System.out.println("[log] " + message); }
    public static String localApiCustomModelsJson() { return "[]"; }
    /** Mirrors the module's catalog: the unified id first, then the legacy aliases. */
    public static String localApiModelCatalogJson() {
        return "[{\"id\":\"deepseek-flash\",\"native_model\":\"default\"},"
                + "{\"id\":\"deepseek-v4-flash\",\"native_model\":\"default\"},"
                + "{\"id\":\"deepseek-v4-pro\",\"native_model\":\"expert\"},"
                + "{\"id\":\"deepseek-vision\",\"native_model\":\"vision\"}]";
    }
    public static String safeThrowableMessage(Throwable t) {
        return t == null ? "null" : t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
