package com.dsmod.probe;

import android.content.Context;

import java.io.File;

/**
 * Payload resolver for the secondary gateway DEX.
 *
 * <p>The shipped Closed edition keeps the Local API gateway ({@code com.dsmod.probe.z1})
 * inside an encrypted secondary payload that is decrypted at runtime by {@code z15} and
 * injected into a child class loader.  This edition ships the gateway as ordinary bytecode
 * inside the module DEX, so the resolver simply resolves the gateway from the module class
 * loader.  The public shape (detect / available / payloadClass / invalidateCloudPayload) is
 * identical so {@link z13} keeps working unchanged.</p>
 *
 * <p>A decrypted payload is still preferred when one is present on disk, which lets a user
 * drop in an official payload file without rebuilding the module.</p>
 */
final class z14 {

    /** Canonical name of the gateway class expected by {@link z13}. */
    private static final String GATEWAY = "com.dsmod.probe.z1";

    /** Optional decrypted payload drop-in locations, checked in order. */
    private static final String[] PAYLOAD_PATHS = new String[]{
            "/data/data/com.deepseek.chat/files/dq0_payload.dex",
            "/data/data/com.deepseek.chat/files/deekseep_local_api_payload.dex"
    };

    private static volatile ClassLoader payloadLoader;
    private static volatile boolean detected;

    private z14() {}

    static String detect(Context context) {
        detected = true;
        String detail = localPayloadDetail(context);
        if (detail != null) return detail;
        return "in-process gateway (" + GATEWAY + ")";
    }

    static boolean available() {
        detected = true;
        return true;
    }

    static Class<?> payloadClass(Context context, String name) {
        if (name == null || name.length() == 0) return null;
        // 1. Prefer an explicit decrypted payload DEX when the user provided one.
        ClassLoader external = externalLoader(context);
        if (external != null) {
            try { return Class.forName(name, true, external); }
            catch (Throwable ignored) {}
        }
        // 2. Fall back to the in-process gateway compiled into this module.
        try { return Class.forName(name, true, z14.class.getClassLoader()); }
        catch (Throwable ignored) {}
        return null;
    }

    static void invalidateCloudPayload() {
        payloadLoader = null;
    }

    // ---------------------------------------------------------------------------------

    private static String localPayloadDetail(Context context) {
        File file = existingPayload();
        return file == null ? null : file.getAbsolutePath();
    }

    private static File existingPayload() {
        for (String path : PAYLOAD_PATHS) {
            try {
                File file = new File(path);
                if (file.isFile() && file.length() > 0L) return file;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static synchronized ClassLoader externalLoader(Context context) {
        if (payloadLoader != null) return payloadLoader;
        File file = existingPayload();
        if (file == null) return null;
        try {
            ClassLoader parent = z14.class.getClassLoader();
            // A DexClassLoader keeps the decrypted payload isolated from the host loader
            // while still sharing the module's z2 contract types through the parent.
            payloadLoader = new dalvik.system.DexClassLoader(
                    file.getAbsolutePath(),
                    payloadCacheDir(context),
                    null,
                    parent);
        } catch (Throwable error) {
            try {
                Main.log("local API payload load failed: " + Main.safeThrowableMessage(error));
            } catch (Throwable ignored) {}
            payloadLoader = null;
        }
        return payloadLoader;
    }

    private static String payloadCacheDir(Context context) {
        try {
            File dir = context == null ? null : context.getCodeCacheDir();
            if (dir == null) dir = new File("/data/data/com.deepseek.chat/files");
            File target = new File(dir, "dq0_payload");
            if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
                return dir.getAbsolutePath();
            }
            return target.getAbsolutePath();
        } catch (Throwable ignored) {
            return "/data/data/com.deepseek.chat/files";
        }
    }
}