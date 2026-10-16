package com.modkit.injector.engine;

import com.modkit.injector.engine.util.IOUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Lightweight editable view over an APK (which is just a zip).
 *
 * Untouched entries are streamed straight from the source on write, so only
 * modified/added entries are held in memory. Existing v1 signature files are
 * dropped — the output is re-signed afterwards.
 *
 * Compression method is preserved per entry. This matters: {@code resources.arsc}
 * and (when {@code extractNativeLibs="false"}) {@code .so} files must stay
 * uncompressed or the package won't install on newer Android. apksig realigns
 * the stored entries afterwards.
 */
public class ApkArchive {

    private final File source;
    private final Map<String, byte[]> overrides = new LinkedHashMap<>();
    private final Set<String> removals = new HashSet<>();

    public ApkArchive(File source) {
        this.source = source;
    }

    public boolean has(String name) {
        if (removals.contains(name)) return false;
        if (overrides.containsKey(name)) return true;
        try (ZipFile zf = new ZipFile(source)) {
            return zf.getEntry(name) != null;
        } catch (IOException e) {
            return false;
        }
    }

    public byte[] read(String name) throws IOException {
        if (overrides.containsKey(name)) return overrides.get(name);
        try (ZipFile zf = new ZipFile(source)) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                return IOUtils.readAll(in);
            }
        }
    }

    public void put(String name, byte[] data) {
        removals.remove(name);
        overrides.put(name, data);
    }

    public void remove(String name) {
        overrides.remove(name);
        removals.add(name);
    }

    public List<String> entryNames() throws IOException {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (ZipFile zf = new ZipFile(source)) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!removals.contains(n) && !overrides.containsKey(n)) {
                    out.add(n);
                    seen.add(n);
                }
            }
        }
        for (String n : overrides.keySet()) if (!seen.contains(n)) out.add(n);
        return out;
    }

    public List<String> dexNames() throws IOException {
        List<String> dex = new ArrayList<>();
        for (String n : entryNames()) if (n.matches("classes\\d*\\.dex")) dex.add(n);
        dex.sort((a, b) -> Integer.compare(dexOrder(a), dexOrder(b)));
        return dex;
    }

    private static int dexOrder(String n) {
        String mid = n.substring("classes".length(), n.length() - ".dex".length());
        return mid.isEmpty() ? 1 : Integer.parseInt(mid);
    }

    private static boolean isSignatureEntry(String name) {
        String u = name.toUpperCase(Locale.US);
        if (!u.startsWith("META-INF/")) return false;
        return u.equals("META-INF/MANIFEST.MF")
                || u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC");
    }

    /** Write an unsigned APK (signature files stripped) to {@code out}. */
    public void writeTo(File out) throws IOException {
        Map<String, Integer> origMethod = new HashMap<>();
        int nativeLibMethod = ZipEntry.DEFLATED;

        // Pre-scan source for per-entry compression method.
        try (ZipFile zf = new ZipFile(source)) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                origMethod.put(e.getName(), e.getMethod());
                if (e.getName().matches("lib/[^/]+/.*\\.so") && e.getMethod() == ZipEntry.STORED) {
                    nativeLibMethod = ZipEntry.STORED;
                }
            }
        }

        Set<String> written = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            zos.setLevel(6);

            try (ZipFile zf = new ZipFile(source)) {
                var en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (removals.contains(n) || overrides.containsKey(n) || isSignatureEntry(n)) continue;
                    // Stream passthrough entries so huge Unity assets don't load into RAM.
                    ZipEntry ne = new ZipEntry(n);
                    ne.setMethod(e.getMethod());
                    if (e.getMethod() == ZipEntry.STORED) {
                        ne.setSize(e.getSize());
                        ne.setCompressedSize(e.getSize());
                        ne.setCrc(e.getCrc());
                    }
                    zos.putNextEntry(ne);
                    try (InputStream in = zf.getInputStream(e)) {
                        IOUtils.copy(in, zos);
                    }
                    zos.closeEntry();
                    written.add(n);
                }
            }
            for (Map.Entry<String, byte[]> ov : overrides.entrySet()) {
                String n = ov.getKey();
                if (isSignatureEntry(n) || written.contains(n)) continue;
                writeEntry(zos, n, ov.getValue(), methodFor(n, origMethod, nativeLibMethod));
            }
        }
    }

    private static int methodFor(String name, Map<String, Integer> orig, int nativeLibMethod) {
        if (orig.containsKey(name)) return orig.get(name);               // replaced entry keeps its method
        if (name.endsWith("resources.arsc")) return ZipEntry.STORED;     // must be uncompressed
        if (name.matches("lib/[^/]+/.*\\.so")) return nativeLibMethod;   // match sibling libs
        return ZipEntry.DEFLATED;
    }

    private static void writeEntry(ZipOutputStream zos, String name, byte[] data, int method) throws IOException {
        ZipEntry ne = new ZipEntry(name);
        ne.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            ne.setSize(data.length);
            ne.setCompressedSize(data.length);
            ne.setCrc(crc.getValue());
        }
        zos.putNextEntry(ne);
        zos.write(data);
        zos.closeEntry();
    }
}
