package evia.paradigma;

import java.nio.charset.StandardCharsets;
import java.util.*;

import evia.paradigma.NativeLang.*;

/**
 * The AVR back end of {@link NativeLang}: the same small language, compiled for 8-bit AVR chips. An int is 32 bits
 * and a float is an IEEE single (both one 4-byte value); the float operations are calls into the runtime's
 * software floating point (resources/evia/paradigma/native/avr/float.er).
 *
 * <p>Registers: r22..r25 hold the value being computed (low byte first), r18..r21 the second operand, Y (r28:r29)
 * is the frame pointer, X and Z and r16, r17 are scratch, r1 is zero between instructions that use mul.
 * Calls push the arguments left to right, 4 bytes each, low byte at the lowest address; the result comes back in
 * r22..r25. Globals and buffers live at fixed addresses in SRAM; strings stay in flash and are read with LPM.
 */
final class AvrGen {
    private static final int A0 = 22, A1 = 23, A2 = 24, A3 = 25, B0 = 18, B1 = 19, B2 = 20, B3 = 21,
            T = 16, T2 = 17, XL = 26, XH = 27, YL = 28, YH = 29, ZL = 30, ZH = 31;
    /** The registers of parameters 1-4 (four bytes each, from these). */
    private static final int[] PARAM = {A0, B0, 12, 8};
    private static final int SPL = 0x3D, SPH = 0x3E, SREG = 0x3F;
    private static final Set<String> COMPARE = Set.of("==", "!=", "<", "<=", ">", ">=");

    private final NativeLang lang;
    private final AvrChip chip;
    private final AvrAsm a;
    private final Map<String, Integer> ram = new HashMap<>();
    private final Map<String, String> flashStrings = new LinkedHashMap<>();   // text -> label
    private final Set<String> needed = new LinkedHashSet<>();
    /** The assembly floating point (AvrFloat) is called: emit it. */
    private int ramTop;

    AvrGen(NativeLang lang, AvrChip chip) {
        this.lang = lang;
        this.chip = chip;
        this.a = new AvrAsm(chip.longJumps());
    }

    /** The program words, entry function main, with the timer-0 overflow interrupt in fn isr_timer0_ovf if there is one. */
    int[] compile() {
        ramTop = chip.ramStart();
        for (String g : lang.globals().keySet()) {
            ram.put(g, ramTop);
            ramTop += 4;
        }
        for (Map.Entry<String, Long> b : lang.bufferSize.entrySet()) {
            ram.put(b.getKey(), ramTop);
            ramTop += (int) (long) b.getValue();
        }
        if (ramTop > chip.ramEnd() - 128) {
            throw new IllegalStateException("the program needs " + (ramTop - chip.ramStart()) + " bytes of RAM; the "
                    + chip.display() + " has " + (chip.ramEnd() + 1 - chip.ramStart()) + " (and the stack needs some)");
        }
        boolean isr = lang.fns().containsKey("isr_timer0_ovf");
        for (int i = 0; i < chip.vectors(); i++) {
            a.jmpFixed(i == 0 ? "_start" : i == chip.timer0Ovf() && isr ? "_isr_t0" : "_bad_irq");
        }
        startup(isr);
        // only the functions the code actually calls, starting from main (and the interrupt handler)
        needed.add("main");
        if (isr) needed.add("isr_timer0_ovf");
        Set<String> done = new HashSet<>();
        boolean more = true;
        while (more) {
            more = false;
            for (String n : new ArrayList<>(needed)) {
                if (!done.add(n)) continue;
                Fn f = lang.fns().get(n);
                if (f == null) throw new IllegalStateException("the runtime has no function " + n);
                int before = a.here();
                if (!assembly(n)) new Gen(f).function();
                if (System.getProperty("evia.avr.sizes") != null) System.err.printf("%6d %s%n", 2 * (a.here() - before), f.name());
                more = true;
            }
        }
        for (Map.Entry<String, String> s : flashStrings.entrySet()) {
            a.label(s.getValue());
            byte[] b = s.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] withLength = new byte[b.length + 2];
            withLength[0] = (byte) b.length;
            withLength[1] = (byte) (b.length >> 8);
            System.arraycopy(b, 0, withLength, 2, b.length);
            a.bytes(withLength);
        }
        boolean more2 = true;              // the helper routines the code refers to (and the ones those refer to)
        while (more2) more2 = AvrFloat.emit(a) | helpers();
        for (Map.Entry<String, byte[]> t : lang.tables.entrySet()) {
            a.label("tbl_" + t.getKey());
            a.bytes(t.getValue());
        }
        int[] words = a.link();
        if (System.getProperty("evia.avr.map") != null) {
            a.symbols().entrySet().stream().sorted(Map.Entry.comparingByValue())
                    .forEach(e -> System.err.printf("%06x %s%n", e.getValue(), e.getKey()));
        }
        if (words.length * 2 > chip.flashBytes() - chip.bootloaderBytes()) {
            throw new IllegalStateException("the program is " + words.length * 2 + " bytes; the " + chip.display()
                    + " has room for " + (chip.flashBytes() - chip.bootloaderBytes()));
        }
        return words;
    }

    int ramUsed() {
        return ramTop - chip.ramStart();
    }

    /** A string in flash: [length: 2 bytes][UTF-8 bytes]; its byte address is what the program gets. */
    private String flashString(String text) {
        return flashStrings.computeIfAbsent(text, t -> "str_" + flashStrings.size());
    }

    private void startup(boolean isr) {
        a.label("_start");
        a.clr(1);
        a.out(SREG, 1);
        int top = chip.ramEnd();
        a.ldi(YL, top & 0xFF);
        a.ldi(YH, top >> 8);
        a.out(SPH, YH);
        a.out(SPL, YL);
        int len = ramTop - chip.ramStart();
        if (len > 0) {   // zero the globals and buffers
            a.ldi(XL, chip.ramStart() & 0xFF);
            a.ldi(XH, chip.ramStart() >> 8);
            a.ldi(A2, len & 0xFF);
            a.ldi(A3, len >> 8);
            String loop = a.newLabel("zero");
            a.label(loop);
            a.stXplus(1);
            a.sbiw(A2, 1);
            a.brne(loop);
        }
        a.call("fn_main");
        a.label("_end");             // the program has ended: interrupts off, sleep
        a.cli();
        a.sleep();
        a.rjmp("_end");
        a.label("_bad_irq");
        a.reti();
        if (isr) {
            a.label("_isr_t0");     // saves what any function may change, calls the handler
            a.push(0);
            a.in(0, SREG);
            a.push(0);
            a.push(1);
            a.clr(1);
            for (int r = 16; r <= 31; r++) a.push(r);
            a.call("fn_isr_timer0_ovf");
            for (int r = 31; r >= 16; r--) a.pop(r);
            a.pop(1);
            a.pop(0);
            a.out(SREG, 0);
            a.pop(0);
            a.reti();
        }
    }

    // ================================================================ runtime functions in assembly

    /**
     * Some runtime functions are written here in assembly, where the compiled versions would be much bigger (the
     * digits of numbers: on an ATmega8 every byte counts). They do what their bodies in avr/base.er do.
     * Returns false for every other function.
     */
    private boolean assembly(String name) {
        int ndig = ram.get("ndig"), ndn = ram.get("ndn");
        switch (name) {
            case "dig_from_int" -> {        // u >= 0 in acc: its digits, least significant first; at least one
                a.label("fn_dig_from_int");
                a.clr(T2);                  // the count (_udivmod32 leaves r17 alone)
                String loop = a.newLabel("dfi");
                a.label(loop);
                a.ldi(B0, 10);
                a.clr(B1);
                a.clr(B2);
                a.clr(B3);
                a.call("_udivmod32");       // acc = acc / 10, the remainder in XL
                a.ldi(ZL, ndig & 0xFF);
                a.ldi(ZH, ndig >> 8);
                a.add(ZL, T2);
                a.adc(ZH, 1);
                a.stdZ(0, XL);
                a.inc(T2);
                a.mov(T, A0);
                a.or(T, A1);
                a.or(T, A2);
                a.or(T, A3);
                a.brne(loop);
                a.sts(ndn, T2);
                a.sts(ndn + 1, 1);
                a.sts(ndn + 2, 1);
                a.sts(ndn + 3, 1);
                a.ret();
                return true;
            }
            case "dig_double" -> {          // the digits times two
                a.label("fn_dig_double");
                a.ldi(ZL, ndig & 0xFF);
                a.ldi(ZH, ndig >> 8);
                a.lds(T2, ndn);
                a.mov(XL, T2);
                a.clr(T);                   // the carry
                String loop = a.newLabel("dd"), store = a.newLabel("dds"), end = a.newLabel("dde");
                a.tst(XL);
                a.breq(end);
                a.label(loop);
                a.lddZ(A0, 0);
                a.lsl(A0);
                a.add(A0, T);
                a.clr(T);
                a.cpi(A0, 10);
                a.brlo(store);
                a.subi(A0, 10);
                a.ldi(T, 1);
                a.label(store);
                a.stZplus(A0);
                a.dec(XL);
                a.brne(loop);
                a.tst(T);
                a.breq(end);
                a.stdZ(0, T);               // a new top digit, 1
                a.inc(T2);
                a.sts(ndn, T2);
                a.label(end);
                a.ret();
                return true;
            }
            case "out_digits" -> {          // from (acc) and to (sec): ndig[to] down to ndig[from], as characters
                needed.add("out_byte");
                a.label("fn_out_digits");
                String loop = a.newLabel("od"), done = a.newLabel("odd");
                a.label(loop);
                a.cp(B0, A0);               // small numbers (-1 .. 48): their low bytes compare
                a.brlt(done);
                a.push(A0);
                a.push(B0);
                a.ldi(ZL, ndig & 0xFF);
                a.ldi(ZH, ndig >> 8);
                a.add(ZL, B0);
                a.adc(ZH, 1);
                a.lddZ(A0, 0);
                a.subi(A0, -'0' & 0xFF);
                a.clr(A1);
                a.clr(A2);
                a.clr(A3);
                a.call("fn_out_byte");
                a.pop(B0);
                a.pop(A0);
                a.dec(B0);
                a.rjmp(loop);
                a.label(done);
                a.ret();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ================================================================ 32-bit helpers

    /** The 32-bit helpers the code refers to and has not got yet; whether it wrote any. */
    private boolean helpers() {
        boolean any = false;
        if (AvrFloat.needs(a, "_mul32")) { mul32(); any = true; }
        if (AvrFloat.needs(a, "_udivmod32")) { udivmod32(); any = true; }
        if (AvrFloat.needs(a, "_divmod32")) { divmod32(); any = true; }
        if (AvrFloat.needs(a, "_neg_acc", "_neg_sec")) { neg(); any = true; }
        if (AvrFloat.needs(a, "_frame_enter", "_frame_leave")) { frames(); any = true; }
        if (AvrFloat.needs(a, "_shl32")) { shift("_shl32", 0); any = true; }
        if (AvrFloat.needs(a, "_shr32")) { shift("_shr32", 1); any = true; }
        if (AvrFloat.needs(a, "_sar32")) { shift("_sar32", 2); any = true; }
        return any;
    }

    private void mul32() {
        // acc = acc * sec (the low 32 bits; signed and unsigned alike)
        a.label("_mul32");
        a.clr(T);
        a.mul(A0, B0); a.movw(XL, 0);
        a.mul(A0, B2); a.movw(ZL, 0);
        a.mul(A0, B1); a.add(XH, 0); a.adc(ZL, 1); a.adc(ZH, T);
        a.mul(A1, B0); a.add(XH, 0); a.adc(ZL, 1); a.adc(ZH, T);
        a.mul(A0, B3); a.add(ZH, 0);
        a.mul(A1, B2); a.add(ZH, 0);
        a.mul(A2, B1); a.add(ZH, 0);
        a.mul(A3, B0); a.add(ZH, 0);
        a.mul(A1, B1); a.add(ZL, 0); a.adc(ZH, 1);
        a.mul(A2, B0); a.add(ZL, 0); a.adc(ZH, 1);
        a.movw(A0, XL);
        a.movw(A2, ZL);
        a.clr(1);
        a.ret();
    }

    private void udivmod32() {
        // unsigned: acc = acc / sec, remainder in X:Z (sec at most 2^31)
        a.label("_udivmod32");
        a.clr(XL); a.clr(XH); a.clr(ZL); a.clr(ZH);
        a.ldi(T, 32);
        String loop = a.newLabel("div"), skip = a.newLabel("divs");
        a.label(loop);
        a.lsl(A0); a.rol(A1); a.rol(A2); a.rol(A3);
        a.rol(XL); a.rol(XH); a.rol(ZL); a.rol(ZH);
        a.cp(XL, B0); a.cpc(XH, B1); a.cpc(ZL, B2); a.cpc(ZH, B3);
        a.brlo(skip);
        a.sub(XL, B0); a.sbc(XH, B1); a.sbc(ZL, B2); a.sbc(ZH, B3);
        a.inc(A0);
        a.label(skip);
        a.dec(T);
        a.brne(loop);
        a.ret();
    }

    private void divmod32() {
        // signed: acc = acc / sec (toward zero), sec = remainder (the sign of the dividend)
        a.label("_divmod32");
        a.push(A3);
        a.push(B3);
        a.sbrc(A3, 7);
        a.call("_neg_acc");
        a.sbrc(B3, 7);
        a.call("_neg_sec");
        a.call("_udivmod32");
        a.pop(T2);       // the divisor's sign
        a.pop(T);        // the dividend's sign
        a.movw(B0, XL);
        a.movw(B2, ZL);
        a.sbrc(T, 7);
        a.call("_neg_sec");
        a.eor(T2, T);
        a.sbrc(T2, 7);
        a.call("_neg_acc");
        a.ret();
    }

    private void neg() {
        a.label("_neg_acc");
        a.com(A0); a.com(A1); a.com(A2); a.com(A3);
        a.subi(A0, 0xFF); a.sbci(A1, 0xFF); a.sbci(A2, 0xFF); a.sbci(A3, 0xFF);
        a.ret();
        a.label("_neg_sec");
        a.com(B0); a.com(B1); a.com(B2); a.com(B3);
        a.subi(B0, 0xFF); a.sbci(B1, 0xFF); a.sbci(B2, 0xFF); a.sbci(B3, 0xFF);
        a.ret();
    }

    private void frames() {
        // frames: Z low byte = frame size. Called right at a function's start: the return address comes off the
        // stack, Y is saved, the frame is made, and the jump goes back through Z. Left with a jump at the end.
        a.label("_frame_enter");
        if (chip.pcBytes() == 3) a.pop(T2);   // the third byte of a 22-bit return address (0 below 128 KB)
        a.pop(XH);
        a.pop(XL);
        a.push(YL);
        a.push(YH);
        a.in(YL, SPL);
        a.in(YH, SPH);
        a.sub(YL, ZL);
        a.sbc(YH, 1);
        a.in(0, SREG);
        a.cli();
        a.out(SPH, YH);
        a.out(SREG, 0);
        a.out(SPL, YL);
        a.movw(ZL, XL);
        a.w(0x9409);                           // ijmp
        a.label("_frame_leave");
        a.add(YL, ZL);
        a.adc(YH, 1);
        a.in(0, SREG);
        a.cli();
        a.out(SPH, YH);
        a.out(SREG, 0);
        a.out(SPL, YL);
        a.pop(YH);
        a.pop(YL);
        a.ret();
    }

    private void shift(String name, int kind) {
        a.label(name);
        a.mov(T, B0);
        a.andi(T, 0x1F);
        String done = a.newLabel("sd"), loop = a.newLabel("sl");
        a.breq(done);
        a.label(loop);
        switch (kind) {
            case 0 -> { a.lsl(A0); a.rol(A1); a.rol(A2); a.rol(A3); }
            case 1 -> { a.lsr(A3); a.ror(A2); a.ror(A1); a.ror(A0); }
            default -> { a.asr(A3); a.ror(A2); a.ror(A1); a.ror(A0); }
        }
        a.dec(T);
        a.brne(loop);
        a.label(done);
        a.ret();
    }

    // ================================================================ functions

    private final class Gen {
        final Fn f;
        final Map<String, Integer> slot = new HashMap<>();
        final Map<String, Type> types = new HashMap<>();
        final Map<String, String> labels = new HashMap<>();
        final Deque<String[]> loops = new ArrayDeque<>();
        int nlocals;
        String retLabel;

        Gen(Fn f) {
            this.f = f;
        }

        RuntimeException err(String m) {
            return new IllegalStateException(f.file() + ": in " + f.name() + ": " + m);
        }

        /** Up to four parameters come in registers (acc, sec, r12-r15, r8-r11) and are kept as locals; more come on the stack. */
        boolean regParams() {
            return f.params().size() <= 4;
        }

        void function() {
            for (int i = 0; i < f.params().size(); i++) {
                if (regParams()) slot.put(f.params().get(i), nlocals++);
                else slot.put(f.params().get(i), -(i + 1));
                types.put(f.params().get(i), f.types().get(i));
            }
            collect(f.body());
            a.label("fn_" + f.name());
            int size = 4 * nlocals;
            if (size > 0 && size <= 255) {          // the shared entry: saves Y, makes the frame (_frame_enter)
                a.ldi(ZL, size);
                a.call("_frame_enter");
            } else {
                a.push(YL);
                a.push(YH);
                a.in(YL, SPL);
                a.in(YH, SPH);
                if (size > 0) setStack(-size);
            }
            if (regParams()) {
                for (int i = 0; i < f.params().size(); i++) {
                    int q = disp(f.params().get(i)), r = PARAM[i];
                    if (q + 3 <= 63) for (int k = 0; k < 4; k++) a.stdY(q + k, r + k);
                    else {
                        zFromY(q);
                        for (int k = 0; k < 4; k++) a.stdZ(k, r + k);
                    }
                }
            }
            retLabel = a.newLabel("ret");
            stmts(f.body());
            boolean endsWithReturn = !f.body().isEmpty() && f.body().get(f.body().size() - 1) instanceof Ret;
            if (f.ret() != Type.VOID && !endsWithReturn) constant(0);   // falling off the end returns 0
            a.label(retLabel);
            if (size > 0 && size <= 255) {
                a.ldi(ZL, size);
                a.jmp("_frame_leave");
            } else {
                if (size > 0) setStack(size);
                a.pop(YH);
                a.pop(YL);
                a.ret();
            }
        }

        /** Y += delta, then SP = Y (with interrupts held off while SP is half written). */
        void setStack(int delta) {
            a.subi(YL, (-delta) & 0xFF);
            a.sbci(YH, ((-delta) >> 8) & 0xFF);
            a.in(0, SREG);
            a.cli();
            a.out(SPH, YH);
            a.out(SREG, 0);
            a.out(SPL, YL);
        }

        void collect(List<St> body) {
            for (St s : body) {
                switch (s) {
                    case Var v -> {
                        if (!slot.containsKey(v.name())) slot.put(v.name(), nlocals++);
                    }
                    case If i -> {
                        collect(i.then());
                        collect(i.other());
                    }
                    case While w -> collect(w.body());
                    case Lbl l -> labels.put(l.name(), a.newLabel("L_" + l.name() + "_"));
                    default -> { }
                }
            }
        }

        /** Offset from Y of a local or parameter. */
        int disp(String name) {
            int s = slot.get(name);
            if (s >= 0) return 1 + 4 * s;
            int i = -s - 1, n = f.params().size();
            return 4 * nlocals + 2 + chip.pcBytes() + 1 + 4 * (n - 1 - i);
        }

        /** The local acc was just stored to (offset, and where the code then was): reloading it is not needed. */
        int storedQ = -1, storedAt = -1;

        boolean accHolds(int q) {
            return q == storedQ && a.here() == storedAt && !a.labelHere();
        }

        void loadLocal(int q) {
            if (accHolds(q)) return;
            if (q + 3 <= 63) {
                for (int k = 0; k < 4; k++) a.lddY(A0 + k, q + k);
            } else {
                zFromY(q);
                for (int k = 0; k < 4; k++) a.lddZ(A0 + k, k);
            }
        }

        void storeLocal(int q) {
            if (q + 3 <= 63) {
                for (int k = 0; k < 4; k++) a.stdY(q + k, A0 + k);
            } else {
                zFromY(q);
                for (int k = 0; k < 4; k++) a.stdZ(k, A0 + k);
            }
            storedQ = q;
            storedAt = a.here();
        }

        void zFromY(int q) {
            a.movw(ZL, YL);
            a.subi(ZL, (-q) & 0xFF);
            a.sbci(ZH, ((-q) >> 8) & 0xFF);
        }

        void constant(long v) {
            constant(A0, v);
        }

        /** A 32-bit constant into the four registers from base; zero takes three instructions. */
        void constant(int base, long v) {
            if ((int) v == 0) {
                a.clr(base);
                a.clr(base + 1);
                a.movw(base + 2, base);
                return;
            }
            for (int k = 0; k < 4; k++) a.ldi(base + k, (int) (v >> (8 * k)) & 0xFF);
        }

        void pushAcc() {
            for (int k = 3; k >= 0; k--) a.push(A0 + k);   // the low byte ends at the lowest address
        }

        void popAcc() {
            for (int k = 0; k < 4; k++) a.pop(A0 + k);
        }

        void accToSec() {
            a.movw(B0, A0);
            a.movw(B2, A2);
        }

        /** Jumps to the label when acc is zero (any distance). */
        void jumpIfZero(String target) {
            a.mov(T, A0);
            a.or(T, A1);
            a.or(T, A2);
            a.or(T, A3);
            a.jumpEq(target);
        }

        void stmts(List<St> body) {
            for (St s : body) stmt(s);
        }

        void stmt(St s) {
            switch (s) {
                case Var v -> {
                    Type ty = v.type();
                    if (v.init() != null) {
                        Type it = expr(v.init());
                        if (ty == null) ty = it;
                        else if (ty != it) throw err("var " + v.name() + " is " + ty + " but its value is " + it);
                    } else {
                        if (ty == null) throw err("var " + v.name() + " needs a type or a value");
                        constant(0);
                    }
                    storeLocal(disp(v.name()));
                    types.put(v.name(), ty);
                }
                case Assign as -> {
                    Type vt = expr(as.value());
                    if (slot.containsKey(as.name())) {
                        if (types.get(as.name()) != vt) throw err(as.name() + " is " + types.get(as.name()) + ", not " + vt);
                        storeLocal(disp(as.name()));
                    } else if (lang.globals().containsKey(as.name())) {
                        if (lang.globals().get(as.name()) != vt) throw err(as.name() + " is " + lang.globals().get(as.name()));
                        int addr = ram.get(as.name());
                        for (int k = 0; k < 4; k++) a.sts(addr + k, A0 + k);
                    } else {
                        throw err("unknown variable " + as.name());
                    }
                }
                case If i -> {
                    String els = a.newLabel("else"), end = a.newLabel("endif");
                    cond(i.cond(), els);
                    stmts(i.then());
                    if (!i.other().isEmpty()) a.jmp(end);
                    a.label(els);
                    stmts(i.other());
                    a.label(end);
                }
                case While w -> {
                    String top = a.newLabel("while"), end = a.newLabel("wend");
                    a.label(top);
                    cond(w.cond(), end);
                    loops.push(new String[]{top, end});
                    stmts(w.body());
                    loops.pop();
                    a.jmp(top);
                    a.label(end);
                }
                case Ret r -> {
                    if (r.value() != null) {
                        Type ty = expr(r.value());
                        if (ty != f.ret()) throw err("returns " + ty + " but the function returns " + f.ret());
                    }
                    a.jmp(retLabel);
                }
                case Do d -> expr(d.e());
                case Brk b -> a.jmp(loops.peek()[1]);
                case Cont c -> a.jmp(loops.peek()[0]);
                case Lbl l -> a.label(labels.get(l.name()));
                case Goto g -> {
                    String l = labels.get(g.name());
                    if (l == null) throw err("no label " + g.name());
                    a.jmp(l);
                }
            }
        }

        /** Jumps to ifFalse when the condition is false, branching on comparisons directly. */
        void cond(Ex c, String ifFalse) {
            branch(c, ifFalse, false);
        }

        /** Jumps to target when c is (when == true) true, or false. */
        void branch(Ex c, String target, boolean when) {
            if (c instanceof Un u && u.op().equals("!")) {
                branch(u.a(), target, !when);
                return;
            }
            if (c instanceof Bin b && (b.op().equals("&&") || b.op().equals("||"))) {
                boolean and = b.op().equals("&&");
                if (and != when) {          // && jumping when false, || jumping when true: either side decides
                    branch(b.a(), target, when);
                    branch(b.b(), target, when);
                } else {                    // && jumping when true, || jumping when false: both sides are needed
                    String skip = a.newLabel("bsk");
                    branch(b.a(), skip, !when);
                    branch(b.b(), target, when);
                    a.label(skip);
                }
                return;
            }
            if (c instanceof Bin b && COMPARE.contains(b.op()) && typeOf(b.a()) == Type.INT) {
                switch (compareFlags(b)) {
                    case "==" -> { if (when) a.jumpEq(target); else a.jumpNe(target); }
                    case "!=" -> { if (when) a.jumpNe(target); else a.jumpEq(target); }
                    case "<" -> { if (when) a.jumpLt(target); else a.jumpGe(target); }
                    default -> { if (when) a.jumpGe(target); else a.jumpLt(target); }
                }
                return;
            }
            if (expr(c) != Type.INT) throw err("a condition must be int");
            if (isBool(c)) {        // 0 or 1: the low byte says it
                a.tst(A0);
            } else {
                a.mov(T, A0);
                a.or(T, A1);
                a.or(T, A2);
                a.or(T, A3);
            }
            if (when) a.jumpNe(target); else a.jumpEq(target);
        }

        /** Whether an expression's value is always 0 or 1 (comparisons, !, && and ||). */
        boolean isBool(Ex e) {
            if (e instanceof Bin b) return COMPARE.contains(b.op()) || b.op().equals("&&") || b.op().equals("||");
            return e instanceof Un u && u.op().equals("!");
        }

        /**
         * Sets the flags for a signed int comparison and says which test then decides it: "==", "!=", "<" or ">=".
         * A constant 0..255 on the right is compared with CPI (and r1, the zero register) instead of being loaded.
         */
        String compareFlags(Bin b) {
            String op = b.op();
            Long kc = intConst(b.b());
            if (kc != null) {
                long v = (int) (long) kc;
                if ((op.equals(">") || op.equals("<=")) && v >= -1 && v < 255) {   // x > k is x >= k + 1
                    op = op.equals(">") ? ">=" : "<";
                    v++;
                }
                if (v >= 0 && v <= 255 && !op.equals(">") && !op.equals("<=")) {
                    if (expr(b.a()) != Type.INT) throw err("'" + b.op() + "' needs ints");
                    if (v == 0) a.cp(A0, 1); else a.cpi(A0, (int) v);
                    a.cpc(A1, 1);
                    a.cpc(A2, 1);
                    a.cpc(A3, 1);
                    return op;
                }
            }
            operands(b);
            boolean swap = op.equals(">") || op.equals("<=");
            compareRegs(swap);
            return switch (op) {
                case ">" -> "<";
                case "<=" -> ">=";
                default -> op;
            };
        }

        /** cp acc with sec (or sec with acc). */
        void compareRegs(boolean swap) {
            if (swap) {
                a.cp(B0, A0); a.cpc(B1, A1); a.cpc(B2, A2); a.cpc(B3, A3);
            } else {
                a.cp(A0, B0); a.cpc(A1, B1); a.cpc(A2, B2); a.cpc(A3, B3);
            }
        }

        /** Evaluates a binary operation's operands: the left into acc, the right into sec. */
        Type operands(Bin b) {
            if (simple(b.a()) && !simple(b.b())) {      // the computed right side first: no stack needed
                Type tb = expr(b.b());
                accToSec();
                Type ta = load(b.a(), A0);
                if (ta != tb) throw err("'" + b.op() + "' between " + ta + " and " + tb);
                return ta;
            }
            Type ta = expr(b.a());
            Type tb = simple(b.b()) ? load(b.b(), B0) : null;
            if (tb == null) {
                pushAcc();
                tb = expr(b.b());
                accToSec();
                popAcc();
            }
            if (ta != tb) throw err("'" + b.op() + "' between " + ta + " and " + tb);
            return ta;
        }

        /** The type of an expression without generating code (for choosing int or float comparison). */
        Type typeOf(Ex e) {
            return switch (e) {
                case Num n -> n.type();
                case Name n -> slot.containsKey(n.name()) ? types.get(n.name())
                        : lang.consts().containsKey(n.name()) ? lang.constTypes().get(n.name())
                        : lang.globals().containsKey(n.name()) ? lang.globals().get(n.name()) : Type.INT;
                case Call c -> switch (c.name()) {
                    case "loadf", "fbits", "i2f" -> Type.FLOAT;
                    case "load8", "loadp8", "loadp16", "load64", "bits", "f2i", "@str" -> Type.INT;
                    default -> lang.fns().containsKey(c.name()) ? lang.fns().get(c.name()).ret() : Type.INT;
                };
                case Un u -> u.op().equals("-") ? typeOf(u.a()) : Type.INT;
                case Bin b -> COMPARE.contains(b.op()) || b.op().equals("&&") || b.op().equals("||") ? Type.INT : typeOf(b.a());
            };
        }

        /** A constant, a variable or an address: loaded straight into registers, no stack needed. */
        boolean simple(Ex e) {
            if (e instanceof Num) return true;
            if (e instanceof Name n) {
                String id = n.name();
                return (slot.containsKey(id) && types.get(id) != null) || lang.consts().containsKey(id)
                        || lang.globals().containsKey(id) || lang.bufferSize.containsKey(id) || lang.tables.containsKey(id);
            }
            return false;
        }

        /** Loads a simple expression into the four registers from base (A0 or B0). */
        Type load(Ex e, int base) {
            if (e instanceof Num n) {
                long v = n.type() == Type.FLOAT ? Float.floatToRawIntBits((float) Double.longBitsToDouble(n.bits())) : n.bits();
                constant(base, v);
                return n.type();
            }
            String id = ((Name) e).name();
            if (slot.containsKey(id)) {
                int q = disp(id);
                if (base == A0 && accHolds(q)) return types.get(id);
                if (base == B0 && accHolds(q)) {
                    accToSec();
                    return types.get(id);
                }
                if (q + 3 <= 63) {
                    for (int k = 0; k < 4; k++) a.lddY(base + k, q + k);
                } else {
                    zFromY(q);
                    for (int k = 0; k < 4; k++) a.lddZ(base + k, k);
                }
                return types.get(id);
            }
            if (lang.consts().containsKey(id)) {
                long v = lang.consts().get(id);
                Type ty = lang.constTypes().get(id);
                if (ty == Type.FLOAT) v = Float.floatToRawIntBits((float) Double.longBitsToDouble(v));
                constant(base, v);
                return ty;
            }
            if (lang.globals().containsKey(id)) {
                int addr = ram.get(id);
                for (int k = 0; k < 4; k++) a.lds(base + k, addr + k);
                return lang.globals().get(id);
            }
            if (lang.tables.containsKey(id)) {
                a.ldiAddress(base, base + 1, "tbl_" + id);
                a.clr(base + 2);
                a.clr(base + 3);
                return Type.INT;
            }
            int addr = ram.get(id);
            for (int k = 0; k < 4; k++) a.ldi(base + k, k < 2 ? (addr >> (8 * k)) & 0xFF : 0);
            return Type.INT;
        }

        /** The value of a constant operand (an int literal or int const), or null. */
        Long intConst(Ex e) {
            if (e instanceof Num n && n.type() == Type.INT) return n.bits();
            if (e instanceof Name n && lang.consts().containsKey(n.name()) && lang.constTypes().get(n.name()) == Type.INT) {
                return lang.consts().get(n.name());
            }
            if (e instanceof Name n && lang.bufferSize.containsKey(n.name()) && !slot.containsKey(n.name())) {
                return (long) ram.get(n.name());     // a buffer's address
            }
            return null;
        }

        Type expr(Ex e) {
            return switch (e) {
                case Num n -> {
                    if (n.type() == Type.FLOAT) {
                        constant(Float.floatToRawIntBits((float) Double.longBitsToDouble(n.bits())));
                    } else {
                        constant(n.bits());
                    }
                    yield n.type();
                }
                case Name n -> name(n);
                case Call c -> call(c);
                case Un u -> unary(u);
                case Bin b -> binary(b);
            };
        }

        Type name(Name n) {
            String id = n.name();
            if (slot.containsKey(id)) {
                if (types.get(id) == null) throw err(id + " is used before its var");
                loadLocal(disp(id));
                return types.get(id);
            }
            if (lang.consts().containsKey(id)) {
                long v = lang.consts().get(id);
                Type ty = lang.constTypes().get(id);
                if (ty == Type.FLOAT) constant(Float.floatToRawIntBits((float) Double.longBitsToDouble(v)));
                else constant(v);
                return ty;
            }
            if (lang.globals().containsKey(id)) {
                int addr = ram.get(id);
                for (int k = 0; k < 4; k++) a.lds(A0 + k, addr + k);
                return lang.globals().get(id);
            }
            if (lang.bufferSize.containsKey(id)) {
                constant(ram.get(id));
                return Type.INT;
            }
            if (lang.tables.containsKey(id)) {
                flashAddress("tbl_" + id);
                return Type.INT;
            }
            if (lang.stringText.containsKey(id)) {
                flashAddress(flashString(lang.stringText.get(id)));
                return Type.INT;
            }
            throw err("unknown name " + id);
        }

        void flashAddress(String label) {
            a.ldiAddress(A0, A1, label);
            a.clr(A2);
            a.clr(A3);
        }

        Type unary(Un u) {
            Type ty = expr(u.a());
            switch (u.op()) {
                case "-" -> {
                    if (ty == Type.FLOAT) {
                        a.ldi(T, 0x80);
                        a.eor(A3, T);
                    } else {
                        a.call("_neg_acc");
                    }
                }
                case "!" -> {
                    if (ty != Type.INT) throw err("! needs an int");
                    a.mov(T, A0);
                    a.or(T, A1);
                    a.or(T, A2);
                    a.or(T, A3);
                    a.ldi(A0, 1);
                    String z = a.newLabel("not");
                    a.breq(z);
                    a.clr(A0);
                    a.label(z);
                    a.clr(A1);
                    a.clr(A2);
                    a.clr(A3);
                }
                default -> {
                    if (ty != Type.INT) throw err("~ needs an int");
                    a.com(A0); a.com(A1); a.com(A2); a.com(A3);
                }
            }
            return ty;
        }

        Type binary(Bin b) {
            if (b.op().equals("&&") || b.op().equals("||")) {
                // && stops at a 0, || at a 1; the result is 0 or 1
                String done = a.newLabel("sc");
                if (expr(b.a()) != Type.INT) throw err(b.op() + " needs ints");
                toBool();
                a.cpi(A0, 0);
                if (b.op().equals("&&")) a.jumpEq(done);
                else a.jumpNe(done);
                if (expr(b.b()) != Type.INT) throw err(b.op() + " needs ints");
                toBool();
                a.label(done);
                return Type.INT;
            }
            if (COMPARE.contains(b.op()) && typeOf(b.a()) == Type.INT) {
                compare(compareFlags(b));
                return Type.INT;
            }
            if (Set.of("+", "&", "|", "^", "*").contains(b.op()) && typeOf(b.a()) == Type.INT
                    && intConst(b.a()) != null && intConst(b.b()) == null) {
                b = new Bin(b.op(), b.b(), b.a(), b.line());     // the constant on the right, for the immediate forms
            }
            Long kc = typeOf(b.a()) == Type.INT ? intConst(b.b()) : null;
            if (kc != null && immediate(b, kc)) return Type.INT;
            Type ta = operands(b);
            if (ta == Type.FLOAT) return floatOp(b);
            switch (b.op()) {
                case "+" -> { a.add(A0, B0); a.adc(A1, B1); a.adc(A2, B2); a.adc(A3, B3); }
                case "-" -> { a.sub(A0, B0); a.sbc(A1, B1); a.sbc(A2, B2); a.sbc(A3, B3); }
                case "&" -> { for (int k = 0; k < 4; k++) a.and(A0 + k, B0 + k); }
                case "|" -> { for (int k = 0; k < 4; k++) a.or(A0 + k, B0 + k); }
                case "^" -> { for (int k = 0; k < 4; k++) a.eor(A0 + k, B0 + k); }
                case "*" -> a.call("_mul32");
                case "/" -> a.call("_divmod32");
                case "%" -> {
                    a.call("_divmod32");
                    a.movw(A0, B0);
                    a.movw(A2, B2);
                }
                case "<<" -> a.call("_shl32");
                case ">>" -> a.call("_sar32");
                case ">>>" -> a.call("_shr32");
                default -> compare(b.op());
            }
            return Type.INT;
        }

        /** acc op= a constant, with the AVR's immediate instructions; false when there is no short form. */
        boolean immediate(Bin b, long k) {
            String op = b.op();
            if (!List.of("&", "|", "+", "-", "<<", ">>", ">>>").contains(op)) return false;
            if (expr(b.a()) != Type.INT) throw err("'" + op + "' needs ints");
            int[] bytes = new int[4];
            for (int i = 0; i < 4; i++) bytes[i] = (int) (k >> (8 * i)) & 0xFF;
            switch (op) {
                case "&" -> {
                    for (int i = 0; i < 4; i++) {
                        if (bytes[i] == 0) a.clr(A0 + i);
                        else if (bytes[i] != 0xFF) a.andi(A0 + i, bytes[i]);
                    }
                }
                case "|" -> {
                    for (int i = 0; i < 4; i++) if (bytes[i] != 0) a.ori(A0 + i, bytes[i]);
                }
                case "+", "-" -> {
                    long v = op.equals("+") ? -k : k;     // subi/sbci subtract: + k is - (-k)
                    a.subi(A0, (int) v & 0xFF);
                    for (int i = 1; i < 4; i++) a.sbci(A0 + i, (int) (v >> (8 * i)) & 0xFF);
                }
                default -> {
                    int n = (int) (k & 31);
                    // whole bytes first, by moving registers
                    while (n >= 8) {
                        if (op.equals("<<")) {
                            a.mov(A3, A2); a.mov(A2, A1); a.mov(A1, A0); a.clr(A0);
                        } else {
                            a.mov(A0, A1); a.mov(A1, A2); a.mov(A2, A3);
                            if (op.equals(">>>")) {
                                a.clr(A3);
                            } else {                      // the sign fills in
                                a.clr(A3);
                                a.sbrc(A2, 7);
                                a.com(A3);
                            }
                        }
                        n -= 8;
                    }
                    if (n > 0) {
                        a.ldi(B0, n);
                        a.call(op.equals("<<") ? "_shl32" : op.equals(">>") ? "_sar32" : "_shr32");
                    }
                }
            }
            return true;
        }

        void toBool() {
            a.mov(T, A0);
            a.or(T, A1);
            a.or(T, A2);
            a.or(T, A3);
            a.ldi(A0, 0);
            String z = a.newLabel("bz");
            a.breq(z);
            a.ldi(A0, 1);
            a.label(z);
            a.clr(A1);
            a.clr(A2);
            a.clr(A3);
        }

        /** 1 or 0 in acc, by the flags compareFlags set and the test it named. */
        void compare(String op) {
            String yes = a.newLabel("cy");
            a.ldi(T, 1);
            switch (op) {
                case "==" -> a.breq(yes);
                case "!=" -> a.brne(yes);
                case "<" -> a.brlt(yes);
                default -> a.brge(yes);
            }
            a.ldi(T, 0);
            a.label(yes);
            a.mov(A0, T);
            a.clr(A1);
            a.clr(A2);
            a.clr(A3);
        }

        Type floatOp(Bin b) {
            if (b.op().equals("%")) {            // the exact remainder, in the runtime's language
                callFn("f_mod", 0);
                return Type.FLOAT;
            }
            String fn = switch (b.op()) {
                case "+" -> "_f_add";
                case "-" -> "_f_sub";
                case "*" -> "_f_mul";
                case "/" -> "_f_div";
                case "<" -> "_f_lt";
                case "<=" -> "_f_le";
                case ">" -> "_f_gt";
                case ">=" -> "_f_ge";
                case "==" -> "_f_eq";
                case "!=" -> "_f_ne";
                default -> throw err("'" + b.op() + "' is not for floats");
            };
            // acc and sec hold the operands, as the assembly routines (AvrFloat) take them
            a.call(fn);
            return COMPARE.contains(b.op()) ? Type.INT : Type.FLOAT;
        }

        void callFn(String name, int args) {
            if (!lang.fns().containsKey(name)) throw err("the runtime has no " + name);
            needed.add(name);
            a.call("fn_" + name);
            if (args > 0) dropArgs(4 * args);
        }

        /** SP += n (the caller drops its arguments), keeping acc. */
        void dropArgs(int n) {
            if (n <= 8) {
                for (int i = 0; i < n; i++) a.pop(T);
                return;
            }
            a.in(XL, SPL);
            a.in(XH, SPH);
            if (n <= 63) {
                a.adiw(XL, n);
            } else {
                a.subi(XL, (-n) & 0xFF);
                a.sbci(XH, ((-n) >> 8) & 0xFF);
            }
            a.in(0, SREG);
            a.cli();
            a.out(SPH, XH);
            a.out(SREG, 0);
            a.out(SPL, XL);
        }

        Type call(Call c) {
            List<Ex> args = c.args();
            switch (c.name()) {
                case "@str" -> {
                    flashAddress(flashString(((Name) args.get(0)).name()));
                    return Type.INT;
                }
                case "load8", "loadp8", "loadp16", "load64", "loadf" -> {
                    Long at = args.size() == 1 ? intConst(args.get(0)) : null;
                    if (at != null && c.name().equals("load8")) {     // a register or variable at a known address
                        a.lds(A0, (int) (long) at);
                        a.clr(A1); a.clr(A2); a.clr(A3);
                        return Type.INT;
                    }
                    want(args, 0, Type.INT);
                    a.movw(ZL, A0);
                    if (c.name().equals("loadp16")) {
                        a.lpmZplus(A0);
                        a.lpmZplus(A1);
                        a.clr(A2); a.clr(A3);
                        return Type.INT;
                    }
                    if (c.name().equals("loadp8")) {
                        a.lpmZplus(A0);
                        a.clr(A1); a.clr(A2); a.clr(A3);
                        return Type.INT;
                    }
                    if (c.name().equals("load8")) {
                        a.lddZ(A0, 0);
                        a.clr(A1); a.clr(A2); a.clr(A3);
                        return Type.INT;
                    }
                    for (int k = 0; k < 4; k++) a.lddZ(A0 + k, k);
                    return c.name().equals("loadf") ? Type.FLOAT : Type.INT;
                }
                case "store8", "store64", "storef" -> {
                    Long at = args.size() == 2 ? intConst(args.get(0)) : null;
                    Type vt = c.name().equals("storef") ? Type.FLOAT : Type.INT;
                    if (at != null) {                                    // a known address: STS
                        want(args, 1, vt);
                        int n = c.name().equals("store8") ? 1 : 4;
                        for (int k = 0; k < n; k++) a.sts((int) (long) at + k, A0 + k);
                        return Type.VOID;
                    }
                    if (args.size() == 2 && simple(args.get(0)) && !simple(args.get(1))) {   // the value first, then the address
                        want(args, 1, vt);
                        accToSec();
                        if (load(args.get(0), A0) != Type.INT) throw err(c.name() + " needs an int address");
                        a.movw(ZL, A0);
                        if (c.name().equals("store8")) a.stdZ(0, B0);
                        else for (int k = 0; k < 4; k++) a.stdZ(k, B0 + k);
                        return Type.VOID;
                    }
                    if (args.size() == 2 && simple(args.get(1))) {       // a simple value: no stack
                        want(args, 0, Type.INT);
                        a.movw(ZL, A0);
                        if (load(args.get(1), B0) != vt) throw err(c.name() + " needs " + vt);
                        if (c.name().equals("store8")) a.stdZ(0, B0);
                        else for (int k = 0; k < 4; k++) a.stdZ(k, B0 + k);
                        return Type.VOID;
                    }
                    want(args, 0, Type.INT);
                    pushAcc();
                    want(args, 1, c.name().equals("storef") ? Type.FLOAT : Type.INT);
                    accToSec();
                    popAcc();
                    a.movw(ZL, A0);
                    if (c.name().equals("store8")) a.stdZ(0, B0);
                    else for (int k = 0; k < 4; k++) a.stdZ(k, B0 + k);
                    return Type.VOID;
                }
                case "i2f" -> {
                    want(args, 0, Type.INT);
                    a.call("_f_from_int");
                    return Type.FLOAT;
                }
                case "f2i" -> {
                    want(args, 0, Type.FLOAT);
                    a.call("_f_to_int");
                    return Type.INT;
                }
                case "bits" -> {
                    want(args, 0, Type.FLOAT);
                    return Type.INT;
                }
                case "fbits" -> {
                    want(args, 0, Type.INT);
                    return Type.FLOAT;
                }
                case "halt" -> {          // the program has ended: interrupts off, sleep
                    a.jmp("_end");
                    return Type.VOID;
                }
                case "sei" -> {
                    a.sei();
                    return Type.VOID;
                }
                case "cli" -> {
                    a.cli();
                    return Type.VOID;
                }
                case "syscall" -> throw err("there are no system calls on an AVR chip");
                default -> {
                    Fn callee = lang.fns().get(c.name());
                    if (callee == null) throw err("unknown function " + c.name());
                    if (callee.params().size() != args.size()) throw err(c.name() + " takes " + callee.params().size() + " arguments");
                    if (args.size() <= 2) {        // in registers: the first in acc, the second in sec
                        if (args.size() == 2) {
                            callInRegisters(callee, args);
                        } else if (args.size() == 1) {
                            want(args, 0, callee.types().get(0));
                        }
                        callFn(c.name(), 0);
                        return callee.ret();
                    }
                    if (args.size() <= 4) {
                        callInRegisters(callee, args);
                        callFn(c.name(), 0);
                        return callee.ret();
                    }
                    for (int i = 0; i < args.size(); i++) {
                        want(args, i, callee.types().get(i));
                        pushAcc();
                    }
                    callFn(c.name(), args.size());
                    return callee.ret();
                }
            }
        }

        /**
         * Two to four arguments into PARAM registers: the computed ones first (kept on the stack while the next is
         * computed), then the simple ones loaded straight in.
         */
        void callInRegisters(Fn callee, List<Ex> args) {
            int n = args.size();
            List<Integer> computed = new ArrayList<>();
            for (int i = 0; i < n; i++) if (!simple(args.get(i))) computed.add(i);
            for (int j = 0; j < computed.size(); j++) {
                want(args, computed.get(j), callee.types().get(computed.get(j)));
                if (computed.size() > 1) pushAcc();
            }
            int held = computed.size() == 1 ? computed.get(0) : -1;     // the one value still in acc
            if (held > 0) {
                a.movw(PARAM[held], A0);
                a.movw(PARAM[held] + 2, A2);
            }
            int temp = held == 0 ? B0 : A0;
            for (int i = 2; i < n; i++) {                                 // r8-r15 take no LDI: through a temporary
                if (computed.contains(i)) continue;
                Type t = load(args.get(i), temp);
                if (t != callee.types().get(i)) throw err("argument " + (i + 1) + ": expected " + callee.types().get(i) + ", got " + t);
                a.movw(PARAM[i], temp);
                a.movw(PARAM[i] + 2, temp + 2);
            }
            if (computed.size() > 1) {
                for (int j = computed.size() - 1; j >= 0; j--) {
                    int r = PARAM[computed.get(j)];
                    for (int k = 0; k < 4; k++) a.pop(r + k);
                }
            }
            for (int i = 0; i < 2; i++) {
                if (computed.contains(i)) continue;
                Type t = load(args.get(i), PARAM[i]);
                if (t != callee.types().get(i)) throw err("argument " + (i + 1) + ": expected " + callee.types().get(i) + ", got " + t);
            }
        }

        void want(List<Ex> args, int i, Type ty) {
            Type got = expr(args.get(i));
            if (got != ty) throw err("argument " + (i + 1) + ": expected " + ty + ", got " + got);
        }
    }
}
