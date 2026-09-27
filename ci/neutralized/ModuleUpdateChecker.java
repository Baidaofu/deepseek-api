package com.dsmod.probe;

import android.app.Activity;

/**
 * Neutralised placeholder.
 *
 * <p>The upstream module polls the author's GitHub releases on every start and offers to
 * download an update. This build performs no update check and makes no outbound request:
 * the method is a no-op and no release URL is present in the compiled output.</p>
 */
final class ModuleUpdateChecker {

    private ModuleUpdateChecker() {}

    static void checkOnStartup(final Activity activity) {
        // Intentionally disabled in this build.
    }
}
