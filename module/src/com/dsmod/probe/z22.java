package com.dsmod.probe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * smspatch — SO-level inline hook.  libsmspatch.so patches libsmsdk.so's
 * sub_4A48C (root/magisk/KernelSU detector) to return 0 at the native layer,
 * which the Java-side SMSDK.ma() hook cannot reach.  Native symbols are
 * registered via JNI_OnLoad + RegisterNatives (no exported names).
 */
final class z22 {
    private static final String LIB_NAME = "smspatch";
    private static final String DEST_PATH =
            "/data/data/com.deepseek.chat/files/libsmspatch.so";

    private static volatile boolean attempted;

    private z22() {}

    static synchronized void initialize() {
        if (attempted) return;
        attempted = true;
        boolean ok = false;
        try {
            System.loadLibrary(LIB_NAME);
            ok = true;
        } catch (Throwable ignored) {
            // LSPosed's LspModuleClassLoader cannot map the .so directly; extract it manually.
        }
        if (!ok) ok = loadExtracted();
        if (!ok) {
            try { Main.log("smspatch unavailable"); } catch (Throwable ignored) {}
        }
    }

    private static boolean loadExtracted() {
        File destination = new File(DEST_PATH);
        try {
            byte[] bytes = readFromModuleApk();
            if (bytes == null || bytes.length < 512) {
                if (destination.isFile() && destination.length() > 512L) {
                    System.load(destination.getAbsolutePath());
                    return true;
                }
                return false;
            }
            FileOutputStream output = new FileOutputStream(destination, false);
            try {
                output.write(bytes);
                output.flush();
            } finally {
                output.close();
            }
            System.load(destination.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            try { Main.log("smspatch extract failed: " + Main.safeThrowableMessage(t)); }
            catch (Throwable ignored) {}
            return false;
        }
    }

    private static byte[] readFromModuleApk() {
        ClassLoader loader = z22.class.getClassLoader();
        if (loader == null) return null;
        String[] abis = null;
        try { abis = android.os.Build.SUPPORTED_ABIS; } catch (Throwable ignored) {}
        if (abis == null || abis.length == 0) abis = new String[]{"arm64-v8a"};
        for (String abi : abis) {
            byte[] bytes = readResource(loader, "lib/" + abi + "/lib" + LIB_NAME + ".so");
            if (bytes != null) return bytes;
            bytes = readResource(loader, "META-INF/com.dsmod.probe.native/"
                    + abi + "/lib" + LIB_NAME + ".so");
            if (bytes != null) return bytes;
        }
        return null;
    }

    private static byte[] readResource(ClassLoader loader, String path) {
        InputStream input = null;
        try {
            input = loader.getResourceAsStream(path);
            if (input == null) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
                if (output.size() > 4 * 1024 * 1024) return null;
            }
            return output.toByteArray();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (input != null) {
                try { input.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Patches sub_4A48C to return 0 (no root). Returns true when patched. */
    static native boolean patchRootDetector();
}
