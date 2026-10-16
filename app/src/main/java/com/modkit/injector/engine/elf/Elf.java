package com.modkit.injector.engine.elf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ELF reader/patcher that can add a {@code DT_NEEDED} dependency to a
 * shared object, so Android's dynamic linker auto-loads an extra library when
 * the game's native library is loaded.
 *
 * It navigates via program headers + the dynamic table (the same way the linker
 * does), so it works on fully stripped {@code .so} files with no section headers.
 *
 * Technique & constraints (kept deliberately conservative / in-place):
 *   - the new soname is written into trailing padding of {@code .dynstr};
 *   - one spare {@code DT_NULL} slot in the dynamic array is converted to
 *     {@code DT_NEEDED}, keeping a terminating {@code DT_NULL} after it.
 * If either kind of free space is missing, injection is refused with a clear
 * message (use the DEX method instead) rather than corrupting the file.
 */
public final class Elf {

    public static class ElfException extends IOException {
        public ElfException(String m) { super(m); }
    }

    // p_type / d_tag constants
    private static final int PT_LOAD = 1;
    private static final int PT_DYNAMIC = 2;
    private static final long DT_NULL = 0;
    private static final long DT_NEEDED = 1;
    private static final long DT_STRTAB = 5;
    private static final long DT_STRSZ = 10;

    private final ByteBuffer bb;
    private final boolean is64;

    private Elf(byte[] data) throws ElfException {
        if (data.length < 0x40
                || (data[0] & 0xFF) != 0x7F || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
            throw new ElfException("Not an ELF file");
        }
        int eiClass = data[4] & 0xFF;   // 1=32-bit, 2=64-bit
        int eiData = data[5] & 0xFF;    // 1=little, 2=big
        this.is64 = eiClass == 2;
        this.bb = ByteBuffer.wrap(data);
        bb.order(eiData == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
    }

    public static byte[] injectNeeded(byte[] data, String soname) throws ElfException {
        return new Elf(data).doInject(soname);
    }

    // --- program header access ---------------------------------------------

    private long phoff()      { return is64 ? u64(0x20) : u32(0x1C); }
    private int phentsize()   { return u16(is64 ? 0x36 : 0x2A); }
    private int phnum()       { return u16(is64 ? 0x38 : 0x2C); }

    private static class Seg {
        int type; long offset, vaddr, filesz;
    }

    private List<Seg> segments() {
        List<Seg> list = new ArrayList<>();
        long base = phoff();
        int ent = phentsize();
        int n = phnum();
        for (int i = 0; i < n; i++) {
            long p = base + (long) i * ent;
            Seg s = new Seg();
            s.type = (int) u32(p);
            if (is64) {
                s.offset = u64(p + 8);
                s.vaddr = u64(p + 16);
                s.filesz = u64(p + 32);
            } else {
                s.offset = u32(p + 4);
                s.vaddr = u32(p + 8);
                s.filesz = u32(p + 16);
            }
            list.add(s);
        }
        return list;
    }

    private long vaddrToOffset(List<Seg> segs, long vaddr) throws ElfException {
        for (Seg s : segs) {
            if (s.type == PT_LOAD && vaddr >= s.vaddr && vaddr < s.vaddr + s.filesz) {
                return s.offset + (vaddr - s.vaddr);
            }
        }
        throw new ElfException("Cannot map virtual address 0x" + Long.toHexString(vaddr));
    }

    // --- the actual injection ----------------------------------------------

    private byte[] doInject(String soname) throws ElfException {
        List<Seg> segs = segments();

        Seg dynamic = null;
        for (Seg s : segs) if (s.type == PT_DYNAMIC) { dynamic = s; break; }
        if (dynamic == null) throw new ElfException("No PT_DYNAMIC segment");

        int dynEnt = is64 ? 16 : 8;
        long dynOff = dynamic.offset;
        int dynCount = (int) (dynamic.filesz / dynEnt);

        long strtabVaddr = -1, strsz = -1;
        int firstNullIdx = -1, nullCount = 0;

        for (int i = 0; i < dynCount; i++) {
            long p = dynOff + (long) i * dynEnt;
            long tag = is64 ? bb.getLong((int) p) : (int) bb.getInt((int) p);
            long val = is64 ? u64(p + 8) : u32(p + 4);

            if (tag == DT_STRTAB) strtabVaddr = val;
            else if (tag == DT_STRSZ) strsz = val;

            if (tag == DT_NULL) {
                if (firstNullIdx < 0) firstNullIdx = i;
                nullCount++;
            } else if (firstNullIdx >= 0) {
                // a non-null after a null: not trailing padding -> reset
                firstNullIdx = -1;
                nullCount = 0;
            }
        }

        if (strtabVaddr < 0 || strsz < 0) throw new ElfException("Missing DT_STRTAB/DT_STRSZ");
        if (firstNullIdx < 0 || nullCount < 2) {
            throw new ElfException("No spare DT_NULL slot in dynamic table");
        }

        long strOff = vaddrToOffset(segs, strtabVaddr);

        // find a trailing zero run in .dynstr big enough for soname + NUL
        byte[] name = soname.getBytes(StandardCharsets.US_ASCII);
        int need = name.length + 1;
        int zeros = 0;
        for (long p = strOff + strsz - 1; p >= strOff; p--) {
            if (bb.get((int) p) == 0) zeros++;
            else break;
        }
        if (zeros < need) {
            throw new ElfException("Not enough padding in .dynstr for \"" + soname + "\"");
        }

        // write the soname at the start of that trailing zero run
        long writePos = strOff + strsz - zeros;
        long strIndex = writePos - strOff;
        for (int i = 0; i < name.length; i++) {
            bb.put((int) (writePos + i), name[i]);
        }
        // terminating NUL already present (it's a zero run)

        // convert the first spare DT_NULL into DT_NEEDED -> strIndex
        long slot = dynOff + (long) firstNullIdx * dynEnt;
        if (is64) {
            bb.putLong((int) slot, DT_NEEDED);
            bb.putLong((int) (slot + 8), strIndex);
        } else {
            bb.putInt((int) slot, (int) DT_NEEDED);
            bb.putInt((int) (slot + 4), (int) strIndex);
        }

        return bb.array();
    }

    // --- primitive readers --------------------------------------------------

    private int u16(long pos)  { return bb.getShort((int) pos) & 0xFFFF; }
    private long u32(long pos) { return bb.getInt((int) pos) & 0xFFFFFFFFL; }
    private long u64(long pos) { return bb.getLong((int) pos); }
}
