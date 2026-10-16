package com.modkit.injector.engine.methods;

import com.modkit.injector.engine.util.IOUtils;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.dexbacked.DexBackedClassDef;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Inserts {@code System.loadLibrary("name")} into a target class's static
 * initializer ({@code <clinit>}) by rewriting the containing dex with dexlib2.
 *
 * Patching {@code <clinit>} means the library loads the moment the class is
 * initialized — before any of the game's own activity code runs — which is
 * exactly what the classic "smali injection" does, but done programmatically
 * and without unpacking resources.
 */
public final class DexLoadLibraryInjector {

    private DexLoadLibraryInjector() {}

    /**
     * @param dexBytes   raw bytes of the dex containing {@code targetType}
     * @param targetType smali descriptor, e.g. {@code Lcom/unity3d/player/UnityPlayerGameActivity;}
     * @param libName    argument for loadLibrary (no "lib" prefix, no ".so")
     * @param workDir    scratch dir for the temp output dex
     * @return rewritten dex bytes
     */
    public static byte[] patchDex(byte[] dexBytes, String targetType, String libName, File workDir)
            throws IOException {

        DexBackedDexFile dex = DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(), new ByteArrayInputStream(dexBytes));

        List<ClassDef> classes = new ArrayList<>();
        boolean patched = false;

        for (DexBackedClassDef cls : dex.getClasses()) {
            if (!cls.getType().equals(targetType)) {
                classes.add(cls);
                continue;
            }
            classes.add(patchClass(cls, libName));
            patched = true;
        }

        if (!patched) {
            throw new IOException("Target class not found in dex: " + targetType);
        }

        ImmutableDexFile out = new ImmutableDexFile(Opcodes.getDefault(), classes);

        File tmp = new File(workDir, "patched-" + System.nanoTime() + ".dex");
        try {
            DexPool.writeTo(new FileDataStore(tmp), out);
            return readFile(tmp);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static ImmutableClassDef patchClass(DexBackedClassDef cls, String libName) throws IOException {
        List<Method> directMethods = new ArrayList<>();
        boolean hadClinit = false;

        for (Method m : cls.getDirectMethods()) {
            if (m.getName().equals("<clinit>")) {
                hadClinit = true;
                directMethods.add(withPrependedLoad(m, libName));
            } else {
                directMethods.add(m);
            }
        }
        if (!hadClinit) {
            directMethods.add(newClinit(cls.getType(), libName));
        }

        return new ImmutableClassDef(
                cls.getType(),
                cls.getAccessFlags(),
                cls.getSuperclass(),
                cls.getInterfaces(),
                cls.getSourceFile(),
                cls.getAnnotations(),
                cls.getStaticFields(),
                cls.getInstanceFields(),
                directMethods,
                cls.getVirtualMethods());
    }

    /** Build a fresh {@code <clinit>} that loads the library and returns. */
    private static ImmutableMethod newClinit(String definingClass, String libName) {
        MutableMethodImplementation impl = new MutableMethodImplementation(1);
        impl.addInstruction(0, constString(0, libName));
        impl.addInstruction(1, invokeLoadLibrary(0));
        impl.addInstruction(2, new BuilderInstruction10x(Opcode.RETURN_VOID));

        int flags = AccessFlags.STATIC.getValue() | AccessFlags.CONSTRUCTOR.getValue();
        return new ImmutableMethod(
                definingClass, "<clinit>",
                Collections.emptyList(), "V",
                flags, Collections.emptySet(), Collections.emptySet(), impl);
    }

    /** Prepend loadLibrary to the front of an existing {@code <clinit>}. */
    private static ImmutableMethod withPrependedLoad(Method clinit, String libName) throws IOException {
        MethodImplementation existing = clinit.getImplementation();
        if (existing == null) {
            MutableMethodImplementation impl = new MutableMethodImplementation(1);
            impl.addInstruction(0, constString(0, libName));
            impl.addInstruction(1, invokeLoadLibrary(0));
            impl.addInstruction(2, new BuilderInstruction10x(Opcode.RETURN_VOID));
            return rebuild(clinit, impl);
        }

        MutableMethodImplementation impl = new MutableMethodImplementation(existing);
        if (impl.getRegisterCount() < 1) {
            // Would need register v0; can't grow the frame safely here.
            throw new IOException("Existing <clinit> has no registers; use the ELF method instead.");
        }
        impl.addInstruction(0, constString(0, libName));
        impl.addInstruction(1, invokeLoadLibrary(0));
        return rebuild(clinit, impl);
    }

    private static ImmutableMethod rebuild(Method from, MethodImplementation impl) {
        return new ImmutableMethod(
                from.getDefiningClass(),
                from.getName(),
                from.getParameters(),
                from.getReturnType(),
                from.getAccessFlags(),
                from.getAnnotations(),
                from.getHiddenApiRestrictions(),
                impl);
    }

    private static BuilderInstruction21c constString(int reg, String value) {
        return new BuilderInstruction21c(Opcode.CONST_STRING, reg, new ImmutableStringReference(value));
    }

    private static BuilderInstruction35c invokeLoadLibrary(int reg) {
        ImmutableMethodReference ref = new ImmutableMethodReference(
                "Ljava/lang/System;", "loadLibrary",
                Collections.singletonList("Ljava/lang/String;"), "V");
        return new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, reg, 0, 0, 0, 0, ref);
    }

    private static byte[] readFile(File f) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            return IOUtils.readAll(fis);
        }
    }
}
