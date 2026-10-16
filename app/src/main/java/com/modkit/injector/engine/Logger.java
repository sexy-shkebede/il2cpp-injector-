package com.modkit.injector.engine;

/** Simple log sink so the engine can stream progress to the UI. */
public interface Logger {
    void log(String line);

    default void step(String line) {
        log("→ " + line);
    }

    default void ok(String line) {
        log("✓ " + line);
    }

    default void warn(String line) {
        log("! " + line);
    }

    default void err(String line) {
        log("✗ " + line);
    }
}
