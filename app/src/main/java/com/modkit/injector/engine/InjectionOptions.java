package com.modkit.injector.engine;

import java.io.File;

/** Everything the engine needs for one injection run. */
public class InjectionOptions {
    /** The target Unity APK (already copied to local storage). */
    public File targetApk;
    /** The native library to inject. */
    public File soFile;
    /** Target ABI directory inside the APK, e.g. "arm64-v8a". */
    public String abi = "arm64-v8a";
    /**
     * Name passed to System.loadLibrary(). If null/empty it is derived from the
     * .so file name (strip leading "lib" and trailing ".so").
     */
    public String libName;
    /** Chosen strategy. */
    public InjectionMethod method = InjectionMethod.AUTO;
    /** Where to write the finished, signed APK. */
    public File outApk;

    public String resolvedLibName() {
        if (libName != null && !libName.trim().isEmpty()) {
            return libName.trim();
        }
        String n = soFile.getName();
        if (n.endsWith(".so")) n = n.substring(0, n.length() - 3);
        if (n.startsWith("lib")) n = n.substring(3);
        return n;
    }

    /** File name the library gets inside lib/<abi>/, always lib*.so. */
    public String soEntryName() {
        return "lib/" + abi + "/" + "lib" + resolvedLibName() + ".so";
    }
}
