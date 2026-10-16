package com.modkit.injector.engine.methods;

import com.modkit.injector.engine.ApkArchive;
import com.modkit.injector.engine.InjectionOptions;
import com.modkit.injector.engine.Logger;

/**
 * Places the native library into {@code lib/<abi>/} as {@code lib<name>.so}.
 *
 * Every method needs the library present in the APK; this is also the whole of
 * the "Lib only" strategy (useful when the game loads the lib by name itself,
 * or as a building block paired with the DEX / ELF wiring).
 */
public final class LibOnlyInjector {

    private LibOnlyInjector() {}

    public static String place(ApkArchive apk, InjectionOptions opts, byte[] soBytes, Logger log) {
        String entry = opts.soEntryName();
        apk.put(entry, soBytes);
        if (log != null) log.ok("Placed library -> " + entry + " (" + soBytes.length + " bytes)");
        return entry;
    }
}
