package evia.paradigma;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An AVR machine-code writer: the instructions the AVR back end needs, encoded by hand, with labels. Program memory
 * is a list of 16-bit words; {@link #link} fills in every jump, call, branch and flash address, and {@link #hex}
 * writes the result as Intel HEX, the file every AVR uploader reads.
 */
final class AvrAsm {
    /** JMP and CALL (two words) exist on chips with more than 8 KB of flash; the ATmega8 has only RJMP and RCALL. */
    final boolean longJumps;
    private final List<Integer> words = new ArrayList<>();
    private final Map<String, Integer> labels = new HashMap<>();   // word addresses
    private record Fix(int at, String label, char kind) {}         // r: rjmp/rcall, j: jmp/call, b: branch, L/H: byte address lo/hi
    private final List<Fix> fixes = new ArrayList<>();
    /** Jumps whose size is settled when linking: c = conditional jump, j = jmp, k = call. Written there, at full size. */
    private record Relax(int at, int full, char kind, int bit, boolean set, String label) {}
    private final List<Relax> relax = new ArrayList<>();
    private int unique;

    AvrAsm(boolean longJumps) {
        this.longJumps = longJumps;
    }

    String newLabel(String hint) {
        return "." + hint + (unique++);
    }

    private int lastLabelAt = -1;

    void label(String name) {
        if (labels.put(name, words.size()) != null) throw new IllegalStateException("label defined twice: " + name);
        lastLabelAt = words.size();
    }

    /** Whether a label is at the current position (code may jump here). */
    boolean labelHere() {
        return lastLabelAt == words.size();
    }

    boolean defined(String name) {
        return labels.containsKey(name);
    }

    /** Whether any jump, call or address refers to the label so far. */
    boolean referenced(String name) {
        for (Fix f : fixes) if (f.label().equals(name)) return true;
        for (Relax r : relax) if (r.label().equals(name)) return true;
        return false;
    }

    int here() {
        return words.size();
    }

    void w(int x) {
        words.add(x & 0xFFFF);
    }

    /** Bytes in program memory (strings, tables), padded to a whole word. */
    void bytes(byte[] b) {
        for (int i = 0; i < b.length; i += 2) w((b[i] & 0xFF) | (i + 1 < b.length ? (b[i + 1] & 0xFF) << 8 : 0));
    }

    // ================================================================ instructions

    private static int rd(int d) { return (d & 0x1F) << 4; }
    private static int rr(int r) { return (r & 0x10) << 5 | (r & 0x0F); }

    private void two(int op, int d, int r) { w(op | rd(d) | rr(r)); }

    void add(int d, int r) { two(0x0C00, d, r); }
    void adc(int d, int r) { two(0x1C00, d, r); }
    void sub(int d, int r) { two(0x1800, d, r); }
    void sbc(int d, int r) { two(0x0800, d, r); }
    void and(int d, int r) { two(0x2000, d, r); }
    void or(int d, int r) { two(0x2800, d, r); }
    void eor(int d, int r) { two(0x2400, d, r); }
    void cp(int d, int r) { two(0x1400, d, r); }
    void cpc(int d, int r) { two(0x0400, d, r); }
    void mov(int d, int r) { two(0x2C00, d, r); }
    void mul(int d, int r) { two(0x9C00, d, r); }
    void clr(int d) { eor(d, d); }
    void tst(int d) { and(d, d); }

    void movw(int d, int r) { w(0x0100 | (d / 2) << 4 | (r / 2)); }

    private void imm(int op, int d, int k) {
        if (d < 16) throw new IllegalArgumentException("immediate needs r16..r31, not r" + d);
        w(op | (k & 0xF0) << 4 | (d & 0x0F) << 4 | (k & 0x0F));
    }

    void ldi(int d, int k) { imm(0xE000, d, k); }
    void subi(int d, int k) { imm(0x5000, d, k); }
    void sbci(int d, int k) { imm(0x4000, d, k); }
    void cpi(int d, int k) { imm(0x3000, d, k); }
    void andi(int d, int k) { imm(0x7000, d, k); }
    void ori(int d, int k) { imm(0x6000, d, k); }

    private void one(int op, int d) { w(op | rd(d)); }

    void com(int d) { one(0x9400, d); }
    void neg(int d) { one(0x9401, d); }
    void inc(int d) { one(0x9403, d); }
    void dec(int d) { one(0x940A, d); }
    void lsr(int d) { one(0x9406, d); }
    void ror(int d) { one(0x9407, d); }
    void asr(int d) { one(0x9405, d); }
    void lsl(int d) { add(d, d); }
    void rol(int d) { adc(d, d); }
    void push(int r) { one(0x920F, r); }
    void pop(int d) { one(0x900F, d); }

    void adiw(int d, int k) { w(0x9600 | (k & 0x30) << 2 | ((d - 24) / 2) << 4 | (k & 0x0F)); }
    void sbiw(int d, int k) { w(0x9700 | (k & 0x30) << 2 | ((d - 24) / 2) << 4 | (k & 0x0F)); }

    /** in d, io (io = data address - 0x20, 0..63). */
    void in(int d, int io) { w(0xB000 | (io & 0x30) << 5 | rd(d) | (io & 0x0F)); }
    void out(int io, int r) { w(0xB800 | (io & 0x30) << 5 | rd(r) | (io & 0x0F)); }

    void lds(int d, int addr) { one(0x9000, d); w(addr); }
    void sts(int addr, int r) { one(0x9200, r); w(addr); }

    /** ldd d, Y+q / Z+q (q 0..63). */
    void lddY(int d, int q) { disp(0x8008, d, q); }
    void lddZ(int d, int q) { disp(0x8000, d, q); }
    void stdY(int q, int r) { disp(0x8208, r, q); }
    void stdZ(int q, int r) { disp(0x8200, r, q); }

    private void disp(int op, int reg, int q) {
        if (q < 0 || q > 63) throw new IllegalArgumentException("displacement " + q);
        w(op | (q & 0x20) << 8 | (q & 0x18) << 7 | rd(reg) | (q & 0x07));
    }

    void ldX(int d) { one(0x900C, d); }
    void ldXplus(int d) { one(0x900D, d); }
    void stX(int r) { one(0x920C, r); }
    void stXplus(int r) { one(0x920D, r); }
    void lpmZplus(int d) { one(0x9005, d); }
    void stZplus(int r) { one(0x9201, r); }

    void ret() { w(0x9508); }
    void reti() { w(0x9518); }
    void cli() { w(0x94F8); }
    void sei() { w(0x9478); }
    void sleep() { w(0x9588); }
    void nop() { w(0x0000); }

    /** sbrs/sbrc: skip the next instruction if bit b of r is set / clear. */
    void sbrs(int r, int b) { w(0xFE00 | rd(r) | b); }
    void sbrc(int r, int b) { w(0xFC00 | rd(r) | b); }

    // ---- jumps

    void rjmp(String label) { fixes.add(new Fix(here(), label, 'r')); w(0xC000); }
    void rcall(String label) { fixes.add(new Fix(here(), label, 'r')); w(0xD000); }

    /** A jump of fixed size (the interrupt vector table): JMP where the chip has it, else RJMP. */
    void jmpFixed(String label) {
        if (!longJumps) {
            rjmp(label);
            return;
        }
        fixes.add(new Fix(here(), label, 'j'));
        w(0x940C);
        w(0);
    }

    private void relaxable(int full, char kind, int bit, boolean set, String label) {
        relax.add(new Relax(here(), full, kind, bit, set, label));
        for (int i = 0; i < full; i++) w(0);
    }

    /**
     * A jump anywhere in the flash. When linking it becomes the shortest that reaches: nothing (the target is the
     * next instruction), RJMP, or JMP. Never right after a skip instruction (it may vanish).
     */
    void jmp(String label) {
        relaxable(longJumps ? 2 : 1, 'j', 0, false, label);
    }

    /** A call anywhere in the flash: RCALL when it reaches, else CALL. */
    void call(String label) {
        if (!longJumps) {
            rcall(label);
            return;
        }
        relaxable(2, 'k', 0, false, label);
    }

    /** Jumps (any distance) when SREG bit is set (set) or clear: a short branch when it reaches, else one around a jump. */
    void jumpIf(int bit, boolean set, String label) {
        relaxable(1 + (longJumps ? 2 : 1), 'c', bit, set, label);
    }

    void jumpEq(String l) { jumpIf(Z, true, l); }
    void jumpNe(String l) { jumpIf(Z, false, l); }
    void jumpLt(String l) { jumpIf(S, true, l); }
    void jumpGe(String l) { jumpIf(S, false, l); }
    void jumpLo(String l) { jumpIf(C, true, l); }
    void jumpSh(String l) { jumpIf(C, false, l); }

    /** Short conditional branches (+-63 words): BRxS / BRxC of an SREG bit. */
    static final int C = 0, Z = 1, N = 2, V = 3, S = 4;

    void brbs(int bit, String label) { fixes.add(new Fix(here(), label, 'b')); w(0xF000 | bit); }
    void brbc(int bit, String label) { fixes.add(new Fix(here(), label, 'b')); w(0xF400 | bit); }

    void breq(String l) { brbs(Z, l); }
    void brne(String l) { brbc(Z, l); }
    void brlt(String l) { brbs(S, l); }
    void brge(String l) { brbc(S, l); }
    void brlo(String l) { brbs(C, l); }
    void brsh(String l) { brbc(C, l); }
    void brmi(String l) { brbs(N, l); }
    void brpl(String l) { brbc(N, l); }

    /** ldi lo/hi of a label's byte address in program memory (for LPM). */
    void ldiAddress(int lo, int hi, String label) {
        fixes.add(new Fix(here(), label, 'L'));
        ldi(lo, 0);
        fixes.add(new Fix(here(), label, 'H'));
        ldi(hi, 0);
    }

    /** The final byte address of every label that does not start with a dot (after {@link #link}; for debugging). */
    Map<String, Integer> symbols() {
        Map<String, Integer> m = new TreeMap<>();
        for (var e : labels.entrySet()) if (!e.getKey().startsWith(".")) m.put(e.getKey(), 2 * map(e.getValue()));
        return m;
    }

    // ================================================================ linking and Intel HEX

    private int[] size;          // the relaxable jumps' sizes while linking
    private long[] removedUpTo;  // words removed by relaxable jumps 0..i

    /** Where an address of the unrelaxed program ends up. */
    private int map(int x) {
        int lo = 0, hi = relax.size();          // the number of relaxable jumps that start before x
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (relax.get(mid).at() < x) lo = mid + 1; else hi = mid;
        }
        return lo == 0 ? x : (int) (x - removedUpTo[lo - 1]);
    }

    private int target(String label) {
        Integer t = labels.get(label);
        if (t == null) throw new IllegalStateException("undefined label " + label);
        return map(t);
    }

    private void sums() {
        long s = 0;
        for (int i = 0; i < relax.size(); i++) {
            s += relax.get(i).full() - size[i];
            removedUpTo[i] = s;
        }
    }

    private boolean rjmpReaches(int k) {
        return !longJumps || (k >= -2048 && k <= 2047);
    }

    /** Shrinks the relaxable jumps as far as they go. Shrinking only brings things closer, so it settles. */
    private void settle() {
        int n = relax.size();
        size = new int[n];
        removedUpTo = new long[n];
        for (int i = 0; i < n; i++) size[i] = relax.get(i).full();
        sums();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                Relax r = relax.get(i);
                int at = map(r.at()), t = target(r.label()), cur = size[i];
                if (cur == 0) continue;
                boolean forward = t >= at + cur;
                int after = t - (at + cur);                     // forward: the distance past the jump's end
                int k1 = forward ? after : t - (at + 1);        // a one-word form's offset
                int want = cur;
                switch (r.kind()) {
                    case 'j' -> want = forward && after == 0 ? 0 : rjmpReaches(k1) ? 1 : 2;
                    case 'k' -> want = rjmpReaches(k1) ? 1 : 2;
                    case 'c' -> {
                        int k2 = forward ? after : t - (at + 2);
                        if (forward && after == 0) want = 0;
                        else if (k1 >= -64 && k1 <= 63) want = 1;
                        else if (rjmpReaches(k2)) want = 2;
                        else want = 3;
                    }
                    default -> throw new IllegalStateException();
                }
                if (want < cur) {
                    size[i] = want;
                    sums();
                    changed = true;
                }
            }
        }
    }

    private static int jmpWord(int op, int t) {
        return op | ((t >> 16) & 0x3E) << 3 | ((t >> 16) & 1);
    }

    private int rel(int k) {
        if (!longJumps) k = ((k % 4096) + 4096 + 2048) % 4096 - 2048;   // 8 KB: the program counter wraps
        if (k < -2048 || k > 2047) throw new IllegalStateException("rjmp too far");
        return k & 0x0FFF;
    }

    int[] link() {
        settle();
        int n = relax.size();
        long removed = n == 0 ? 0 : removedUpTo[n - 1];
        int[] out = new int[(int) (words.size() - removed)];
        int ri = 0, o = 0;
        for (int i = 0; i < words.size(); ) {
            if (ri < n && relax.get(ri).at() == i) {
                Relax r = relax.get(ri);
                int sz = size[ri], at = o, t = target(r.label());
                switch (r.kind()) {
                    case 'j', 'k' -> {
                        if (sz == 1) out[o++] = (r.kind() == 'j' ? 0xC000 : 0xD000) | rel(t - (at + 1));
                        else if (sz == 2) {
                            out[o++] = jmpWord(r.kind() == 'j' ? 0x940C : 0x940E, t);
                            out[o++] = t & 0xFFFF;
                        }
                    }
                    case 'c' -> {
                        if (sz == 1) {
                            out[o++] = (r.set() ? 0xF000 : 0xF400) | ((t - (at + 1)) & 0x7F) << 3 | r.bit();
                        } else if (sz >= 2) {
                            // the opposite branch skips the jump
                            out[o++] = (r.set() ? 0xF400 : 0xF000) | ((sz - 1) & 0x7F) << 3 | r.bit();
                            if (sz == 2) out[o++] = 0xC000 | rel(t - (at + 2));
                            else {
                                out[o++] = jmpWord(0x940C, t);
                                out[o++] = t & 0xFFFF;
                            }
                        }
                    }
                    default -> throw new IllegalStateException();
                }
                i += r.full();
                ri++;
            } else {
                out[o++] = words.get(i++);
            }
        }
        for (Fix f0 : fixes) {
            Fix f = new Fix(map(f0.at()), f0.label(), f0.kind());
            int t = target(f.label());
            switch (f.kind()) {
                case 'r' -> out[f.at()] |= rel(t - (f.at() + 1));
                case 'b' -> {
                    int k = t - (f.at() + 1);
                    if (k < -64 || k > 63) throw new IllegalStateException("branch too far: " + f.label());
                    out[f.at()] |= (k & 0x7F) << 3;
                }
                case 'j' -> {
                    out[f.at()] = jmpWord(out[f.at()], t);
                    out[f.at() + 1] = t & 0xFFFF;
                }
                case 'L', 'H' -> {
                    int addr = t * 2;
                    int k = f.kind() == 'L' ? addr & 0xFF : (addr >> 8) & 0xFF;
                    out[f.at()] |= (k & 0xF0) << 4 | (k & 0x0F);
                }
                default -> throw new IllegalStateException();
            }
        }
        return out;
    }

    /** Intel HEX of program words, 16 bytes a line, with extended addresses beyond 64 KB. */
    static String hex(int[] words) {
        byte[] b = new byte[words.length * 2];
        for (int i = 0; i < words.length; i++) {
            b[2 * i] = (byte) words[i];
            b[2 * i + 1] = (byte) (words[i] >> 8);
        }
        StringBuilder sb = new StringBuilder();
        int segment = -1;
        for (int at = 0; at < b.length; at += 16) {
            if (at >> 16 != segment) {
                segment = at >> 16;
                record(sb, 0, 4, new byte[]{(byte) (segment >> 8), (byte) segment});
            }
            int n = Math.min(16, b.length - at);
            byte[] chunk = new byte[n];
            System.arraycopy(b, at, chunk, 0, n);
            record(sb, at & 0xFFFF, 0, chunk);
        }
        record(sb, 0, 1, new byte[0]);
        return sb.toString();
    }

    private static void record(StringBuilder sb, int addr, int type, byte[] data) {
        int sum = data.length + (addr >> 8) + (addr & 0xFF) + type;
        sb.append(':').append(String.format("%02X%04X%02X", data.length, addr, type));
        for (byte x : data) {
            sb.append(String.format("%02X", x & 0xFF));
            sum += x & 0xFF;
        }
        sb.append(String.format("%02X", (-sum) & 0xFF)).append('\n');
    }

    static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
