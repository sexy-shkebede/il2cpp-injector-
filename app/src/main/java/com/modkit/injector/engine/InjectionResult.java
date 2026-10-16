package com.modkit.injector.engine;

import java.io.File;

public class InjectionResult {
    public boolean success;
    public File outApk;
    public InjectionMethod methodUsed;
    public String message;

    public static InjectionResult ok(File out, InjectionMethod m, String msg) {
        InjectionResult r = new InjectionResult();
        r.success = true;
        r.outApk = out;
        r.methodUsed = m;
        r.message = msg;
        return r;
    }

    public static InjectionResult fail(String msg) {
        InjectionResult r = new InjectionResult();
        r.success = false;
        r.message = msg;
        return r;
    }
}
