package com.modkit.injector.engine;

/** The available injection strategies. */
public enum InjectionMethod {
    /** Try every applicable method, most reliable first, until one works. */
    AUTO("Auto — try everything"),
    /** Insert System.loadLibrary(...) into the Unity activity via DEX/smali edit. */
    DEX_LOADLIBRARY("DEX / smali — loadLibrary in Unity activity"),
    /** Add a DT_NEEDED dependency to the game's native lib (version-agnostic). */
    ELF_NEEDED("ELF DT_NEEDED — patch libil2cpp/libunity"),
    /** Only place the .so into lib/<abi>/ (no auto-load wiring). */
    LIB_ONLY("Lib only — drop .so into lib/<abi>");

    public final String label;

    InjectionMethod(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
