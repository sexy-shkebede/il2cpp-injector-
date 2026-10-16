package com.modkit.injector.engine;

import android.content.Context;

import com.modkit.injector.engine.methods.DexLoadLibraryInjector;
import com.modkit.injector.engine.methods.ElfNeededInjector;
import com.modkit.injector.engine.methods.LibOnlyInjector;
import com.modkit.injector.engine.sign.ApkKeySigner;
import com.modkit.injector.engine.util.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/** Orchestrates a full injection run: place lib → wire auto-load → repackage → sign. */
public class Injector {

    private final Context ctx;

    public Injector(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public InjectionResult run(InjectionOptions opts, Logger log) {
        File workDir = new File(ctx.getCacheDir(), "work");
        IOUtils.deleteRecursive(workDir);
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();

        try {
            // ---- validate ----
            if (opts.targetApk == null || !opts.targetApk.exists())
                return InjectionResult.fail("Target APK not selected");
            if (opts.soFile == null || !opts.soFile.exists())
                return InjectionResult.fail("Native library (.so) not selected");

            String libName = opts.resolvedLibName();
            String soname = "lib" + libName + ".so";
            log.log("APK:  " + opts.targetApk.getName());
            log.log("Lib:  " + opts.soFile.getName() + "  (loadLibrary \"" + libName + "\")");
            log.log("ABI:  " + opts.abi);
            log.log("Mode: " + opts.method.label);
            log.log("");

            byte[] soBytes = readFile(opts.soFile);
            ApkArchive apk = new ApkArchive(opts.targetApk);

            // ---- the library must be inside the APK for every method ----
            LibOnlyInjector.place(apk, opts, soBytes, log);

            // ---- wire auto-loading ----
            List<String> applied = new ArrayList<>();
            boolean wantDex = opts.method == InjectionMethod.AUTO || opts.method == InjectionMethod.DEX_LOADLIBRARY;
            boolean wantElf = opts.method == InjectionMethod.AUTO || opts.method == InjectionMethod.ELF_NEEDED;

            if (wantDex) {
                try {
                    applyDex(apk, libName, workDir, log);
                    applied.add("DEX/smali loadLibrary");
                } catch (Exception e) {
                    log.warn("DEX method failed: " + e.getMessage());
                    if (opts.method == InjectionMethod.DEX_LOADLIBRARY)
                        return InjectionResult.fail("DEX injection failed: " + e.getMessage());
                }
            }

            if (wantElf) {
                try {
                    String host = ElfNeededInjector.patch(apk, opts.abi, soname, log);
                    applied.add("ELF DT_NEEDED (" + host + ")");
                } catch (Exception e) {
                    log.warn("ELF method failed: " + e.getMessage());
                    if (opts.method == InjectionMethod.ELF_NEEDED)
                        return InjectionResult.fail("ELF injection failed: " + e.getMessage());
                }
            }

            if (opts.method == InjectionMethod.AUTO && applied.isEmpty()) {
                return InjectionResult.fail(
                        "Auto mode: no auto-load method succeeded. The lib is placed but " +
                        "nothing loads it. Check the ABI / that this is a Unity game.");
            }
            if (opts.method == InjectionMethod.LIB_ONLY) {
                log.warn("Lib-only: the game or another method must load it at runtime.");
                applied.add("lib placed only");
            }

            // ---- repackage ----
            log.step("Repackaging APK...");
            File unsigned = new File(workDir, "unsigned.apk");
            apk.writeTo(unsigned);
            log.ok("Built unsigned APK (" + unsigned.length() + " bytes)");

            // ---- sign ----
            log.step("Signing...");
            if (opts.outApk.exists()) //noinspection ResultOfMethodCallIgnored
                opts.outApk.delete();
            ApkKeySigner.sign(ctx, unsigned, opts.outApk, log);

            log.log("");
            log.ok("Done. Applied: " + String.join(" + ", applied));
            log.log("Output: " + opts.outApk.getAbsolutePath());
            log.log("Reminder: uninstall the original game before installing this.");

            return InjectionResult.ok(opts.outApk, opts.method,
                    "Injected via " + String.join(" + ", applied));

        } catch (Throwable t) {
            log.err(t.getClass().getSimpleName() + ": " + t.getMessage());
            return InjectionResult.fail(String.valueOf(t.getMessage()));
        } finally {
            IOUtils.deleteRecursive(workDir);
        }
    }

    private void applyDex(ApkArchive apk, String libName, File workDir, Logger log) throws Exception {
        UnityDetector.UnityInfo info = UnityDetector.detect(apk, log);
        if (!info.found) {
            throw new IllegalStateException("no Unity activity to patch");
        }
        byte[] dexBytes = apk.read(info.dexEntry);
        byte[] patched = DexLoadLibraryInjector.patchDex(dexBytes, info.targetType, libName, workDir);
        apk.put(info.dexEntry, patched);
        log.ok("Injected loadLibrary(\"" + libName + "\") into " + shortType(info.targetType));
    }

    private static String shortType(String type) {
        int slash = type.lastIndexOf('/');
        int end = type.endsWith(";") ? type.length() - 1 : type.length();
        return type.substring(slash + 1, end);
    }

    private static byte[] readFile(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            return IOUtils.readAll(fis);
        }
    }
}
