package com.modkit.injector.engine.methods;

import com.modkit.injector.engine.ApkArchive;
import com.modkit.injector.engine.Logger;
import com.modkit.injector.engine.elf.Elf;

import java.io.IOException;

/**
 * Adds a {@code DT_NEEDED} dependency to one of the game's own native libraries
 * so that loading it also loads our injected library — no smali/DEX edits,
 * works across Unity versions.
 */
public final class ElfNeededInjector {

    /** Game libraries most likely to be loaded first, best candidates first. */
    private static final String[] HOST_LIBS = {
            "libil2cpp.so", "libunity.so", "libmain.so", "libmono.so"
    };

    private ElfNeededInjector() {}

    /**
     * @param soname the dependency to add, e.g. {@code libmymod.so}
     * @return the APK entry name of the host library that was patched
     */
    public static String patch(ApkArchive apk, String abi, String soname, Logger log) throws IOException {
        IOException last = null;
        for (String host : HOST_LIBS) {
            String entry = "lib/" + abi + "/" + host;
            if (!apk.has(entry)) continue;
            byte[] bytes = apk.read(entry);
            if (bytes == null) continue;
            try {
                byte[] patched = Elf.injectNeeded(bytes, soname);
                apk.put(entry, patched);
                if (log != null) log.ok("Added NEEDED " + soname + " -> " + entry);
                return entry;
            } catch (Elf.ElfException e) {
                if (log != null) log.warn(host + ": " + e.getMessage());
                last = e;
            }
        }
        if (last != null) throw last;
        throw new IOException("No host native library found in lib/" + abi + "/ (libil2cpp/libunity/libmain)");
    }
}
