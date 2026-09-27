package com.dsmod.probe;
import android.content.Context;
/** Test shim: TLS disabled, plain scheme. */
final class z5 {
    static boolean isEnabled(Context c) { return false; }
    static java.net.ServerSocket createUnboundServerSocket(Context c) throws Exception {
        return new java.net.ServerSocket();
    }
    static String scheme(Context c) { return "http"; }
}
