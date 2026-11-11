package evia.paradigma;

import java.util.Set;

/**
 * IEEE single precision for AVR chips, written in AVR assembly: + - * /, comparisons and the conversions from and to
 * int, rounded to nearest with ties to even. Normal numbers, zeros, infinities and NaN (always the quiet NaN
 * 7FC00000); a result too small to be normal becomes zero, as with avr-libc.
 *
 * <p>The operands come in r22..r25 (a) and r18..r21 (b), the result goes to r22..r25. The routines use r0, r1
 * (left 0), r2..r17 and X; Y is the caller's frame pointer and is not touched. The timer interrupt saves only the
 * registers the compiled code uses (r0, r1, r16..r31), which is why r2..r15 are free here.
 */
final class AvrFloat {
    private AvrFloat() {}

    private static final int A0 = 22, A1 = 23, A2 = 24, A3 = 25, B0 = 18, B1 = 19, B2 = 20, B3 = 21,
            T = 16, CNT = 17, XL = 26, XH = 27, EA = 10, EB = 11, SA = 12, SB = 13, STICKY = 14, ZERO = 15;

    /**
     * The routines the program refers to and has not got yet (each group once). Returns whether it wrote any: they
     * refer to one another, so the caller repeats until nothing more is needed.
     */
    static boolean emit(AvrAsm a) {
        boolean any = false;
        if (needs(a, "_f_add", "_f_sub")) { add(a); any = true; }
        if (needs(a, "_f_mul")) { mul(a); any = true; }
        if (needs(a, "_f_div")) { div(a); any = true; }
        if (needs(a, "_f_from_int")) { fromInt(a); any = true; }
        if (needs(a, "_f_to_int")) { toInt(a); any = true; }
        if (needs(a, "_f_lt", "_f_le", "_f_gt", "_f_ge", "_f_eq", "_f_ne", "_f_cmp")) { compare(a); any = true; }
        if (needs(a, "_f_round", "_f_round8", "_f_pack", "_f_nan", "_f_inf", "_f_zero")) { roundAndPack(a); any = true; }
        if (needs(a, "_fu_a")) { unpack(a, "_fu_a", A0, A1, A2, A3, EA, SA); any = true; }
        if (needs(a, "_fu_b")) { unpack(a, "_fu_b", B0, B1, B2, B3, EB, SB); any = true; }
        return any;
    }

    /** Whether a group (its labels) is referred to and not written yet. */
    static boolean needs(AvrAsm a, String... labels) {
        if (a.defined(labels[0])) return false;
        for (String l : labels) if (a.referenced(l)) return true;
        return false;
    }

    /** sign to s (0 or 0x80), exponent to e, mantissa with its hidden bit to r[3]:r[2]:r[1], r[0] = 0 (guard). */
    private static void unpack(AvrAsm a, String label, int r0, int r1, int r2, int r3, int e, int s) {
        a.label(label);
        a.mov(T, r3);
        a.andi(T, 0x80);
        a.mov(s, T);
        a.mov(T, r2);
        a.lsl(T);              // C = exponent bit 0
        a.mov(e, r3);
        a.rol(e);              // e = the 8 exponent bits
        a.mov(r3, r2);
        a.ori(r3, 0x80);
        a.mov(r2, r1);
        a.mov(r1, r0);
        a.clr(r0);
        String nz = a.newLabel("fun");
        a.tst(e);
        a.brne(nz);
        a.clr(r1);             // zero (and too-small numbers): no mantissa
        a.clr(r2);
        a.clr(r3);
        a.label(nz);
        a.ret();
    }

    /**
     * Mantissa in A3:A2:A1 (top bit set), guard byte in A0, exponent in X (16-bit, signed), sign in SA: rounds to
     * nearest (ties to even) and packs into A3..A0. Overflow gives infinity, underflow zero.
     */
    private static void roundAndPack(AvrAsm a) {
        a.label("_f_round");
        String pack = a.newLabel("fpk"), up = a.newLabel("fup");
        a.cpi(A0, 0x80);
        a.brlo(pack);
        a.brne(up);
        a.sbrs(A1, 0);         // a tie: up only when the last kept bit is 1
        a.rjmp(pack);
        a.label(up);
        a.ldi(T, 1);
        a.add(A1, T);
        a.adc(A2, 1);          // r1 is 0
        a.adc(A3, 1);
        a.brsh(pack);          // no carry out
        a.ror(A3);             // 1.0000 again, one exponent higher
        a.adiw(XL, 1);
        a.label(pack);
        a.label("_f_pack");
        String zero = "_f_zero", inf = "_f_inf";
        a.tst(XH);
        a.brmi(zero);
        a.brne(inf);
        a.ldi(T, 255);
        a.cp(XL, T);
        a.brsh(inf);
        a.tst(XL);
        a.breq(zero);
        a.mov(A0, A1);
        a.mov(A1, A2);
        a.mov(A2, A3);
        a.andi(A2, 0x7F);
        a.mov(A3, XL);
        a.lsr(A3);
        String nob = a.newLabel("fnb");
        a.brsh(nob);
        a.ori(A2, 0x80);
        a.label(nob);
        a.or(A3, SA);
        a.ret();
        a.label(zero);           // signed zero
        a.clr(A0);
        a.clr(A1);
        a.clr(A2);
        a.mov(A3, SA);
        a.ret();
        a.label(inf);            // signed infinity
        a.clr(A0);
        a.clr(A1);
        a.ldi(A2, 0x80);
        a.ldi(A3, 0x7F);
        a.or(A3, SA);
        a.ret();
        a.label("_f_nan");
        a.clr(A0);
        a.clr(A1);
        a.ldi(A2, 0xC0);
        a.ldi(A3, 0x7F);
        a.ret();
        // the exponent in X from EA; then round
        a.label("_f_round8");
        a.mov(XL, EA);
        a.clr(XH);
        a.rjmp("_f_round");
    }

    /** Z set when the unpacked mantissa r3:r2:r1 (hidden bit ignored) is zero: infinity rather than NaN. */
    private static void fraction(AvrAsm a, int r1, int r2, int r3) {
        a.mov(T, r3);
        a.andi(T, 0x7F);
        a.or(T, r2);
        a.or(T, r1);
    }

    private static void add(AvrAsm a) {
        a.label("_f_sub");
        a.subi(B3, 0x80);                    // b = -b
        a.label("_f_add");
        a.clr(ZERO);
        a.call("_fu_a");
        a.call("_fu_b");
        a.ldi(T, 255);
        a.cp(EA, T);
        far(a, "eq", "_fa_aspec");
        a.cp(EB, T);
        far(a, "eq", "_fa_bspec");
        a.tst(EA);
        far(a, "eq", "_fa_azero");
        a.tst(EB);
        far(a, "eq", "_f_round8");                 // b is zero: a, packed again
        // |a| >= |b|: else swap them
        a.cp(A1, B1);
        a.cpc(A2, B2);
        a.cpc(A3, B3);
        a.cpc(EA, EB);
        String noswap = a.newLabel("fns");
        a.brsh(noswap);
        for (int[] p : new int[][]{{A0, B0}, {A1, B1}, {A2, B2}, {A3, B3}, {EA, EB}, {SA, SB}}) {
            a.mov(T, p[0]);
            a.mov(p[0], p[1]);
            a.mov(p[1], T);
        }
        a.label(noswap);
        // b >>= ea - eb, keeping a sticky bit
        a.mov(CNT, EA);
        a.sub(CNT, EB);
        a.clr(STICKY);
        String loop = a.newLabel("fsl"), shifted = a.newLabel("fsd"), far = a.newLabel("fsf"), keep = a.newLabel("fsk");
        a.cpi(CNT, 32);
        a.brsh(far);
        a.label(loop);
        a.tst(CNT);
        a.breq(shifted);
        a.lsr(B3);
        a.ror(B2);
        a.ror(B1);
        a.ror(B0);
        a.brsh(keep);
        a.inc(STICKY);
        a.label(keep);
        a.dec(CNT);
        a.rjmp(loop);
        a.label(far);
        a.clr(B1);
        a.clr(B2);
        a.clr(B3);
        a.ldi(B0, 1);
        a.label(shifted);
        String nost = a.newLabel("fnst");
        a.tst(STICKY);
        a.breq(nost);
        a.ori(B0, 1);
        a.label(nost);
        a.mov(T, SA);
        a.eor(T, SB);
        String subtract = a.newLabel("fsub");
        a.brne(subtract);
        a.add(A0, B0);
        a.adc(A1, B1);
        a.adc(A2, B2);
        a.adc(A3, B3);
        far(a, "sh", "_f_round8");
        a.ror(A3);                           // the carry comes in at the top
        a.ror(A2);
        a.ror(A1);
        a.ror(A0);
        String ns2 = a.newLabel("fns2");
        a.brsh(ns2);
        a.ori(A0, 1);
        a.label(ns2);
        a.inc(EA);
        a.rjmp("_f_round8");
        a.label(subtract);
        a.sub(A0, B0);
        a.sbc(A1, B1);
        a.sbc(A2, B2);
        a.sbc(A3, B3);
        a.mov(T, A0);
        a.or(T, A1);
        a.or(T, A2);
        a.or(T, A3);
        String nonzero = a.newLabel("fnz");
        a.brne(nonzero);
        a.clr(A3);                           // x - x = +0
        a.ret();
        a.label(nonzero);
        String norm = a.newLabel("fnm");
        a.label(norm);
        a.sbrc(A3, 7);
        a.rjmp("_f_round8");
        a.lsl(A0);
        a.rol(A1);
        a.rol(A2);
        a.rol(A3);
        a.dec(EA);
        a.brne(norm);
        a.rjmp("_f_zero");
        // a is infinite or NaN
        a.label("_fa_aspec");
        fraction(a, A1, A2, A3);
        far(a, "ne", "_f_nan");
        a.cp(EB, T);                         // T is not 255 any more: reload
        a.ldi(T, 255);
        a.cp(EB, T);
        String aonly = a.newLabel("fao");
        a.brne(aonly);
        fraction(a, B1, B2, B3);
        far(a, "ne", "_f_nan");
        a.mov(T, SA);
        a.eor(T, SB);
        far(a, "ne", "_f_nan");                    // inf - inf
        a.label(aonly);
        a.rjmp("_f_inf");
        a.label("_fa_bspec");
        fraction(a, B1, B2, B3);
        far(a, "ne", "_f_nan");
        a.mov(SA, SB);
        a.rjmp("_f_inf");
        a.label("_fa_azero");                // a is zero: b, or a signed zero
        a.tst(EB);
        String bnz = a.newLabel("fbnz");
        a.brne(bnz);
        a.and(SA, SB);                       // -0 only when both are -0
        a.rjmp("_f_zero");
        a.label(bnz);
        a.movw(A0, B0);
        a.movw(A2, B2);
        a.mov(EA, EB);
        a.mov(SA, SB);
        a.rjmp("_f_round8");
    }

    private static void mul(AvrAsm a) {
        a.label("_f_mul");
        a.clr(ZERO);
        a.call("_fu_a");
        a.call("_fu_b");
        a.mov(T, SA);
        a.eor(T, SB);
        a.mov(SA, T);
        a.ldi(T, 255);
        String aspec = a.newLabel("fma"), bspec = a.newLabel("fmb");
        a.cp(EA, T);
        far(a, "eq", aspec);
        a.cp(EB, T);
        far(a, "eq", bspec);
        a.tst(EA);
        far(a, "eq", "_f_zero");
        a.tst(EB);
        far(a, "eq", "_f_zero");
        // X = ea + eb - 127
        a.mov(XL, EA);
        a.clr(XH);
        a.add(XL, EB);
        a.adc(XH, ZERO);
        a.sbiw(XL, 63);
        a.sbiw(XL, 63);
        a.sbiw(XL, 1);
        // the 48-bit product of A3:A2:A1 and B3:B2:B1 in r7..r2
        for (int r = 2; r <= 7; r++) a.clr(r);
        int[] ra = {A1, A2, A3}, rb = {B1, B2, B3};
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                int k = 2 + i + j;
                a.mul(ra[i], rb[j]);
                a.add(k, 0);
                a.adc(k + 1, 1);
                for (int c = k + 2; c <= 7; c++) a.adc(c, ZERO);
            }
        }
        a.clr(1);
        String top = a.newLabel("fmt");
        a.sbrc(7, 7);
        a.rjmp(top);
        a.lsl(2);
        a.rol(3);
        a.rol(4);
        a.rol(5);
        a.rol(6);
        a.rol(7);
        a.sbiw(XL, 1);
        a.label(top);
        a.adiw(XL, 1);
        a.mov(A3, 7);
        a.mov(A2, 6);
        a.mov(A1, 5);
        a.mov(A0, 4);
        a.or(2, 3);
        a.tst(2);
        far(a, "eq", "_f_round");
        a.ori(A0, 1);
        a.rjmp("_f_round");
        a.label(aspec);                       // a infinite or NaN
        fraction(a, A1, A2, A3);
        far(a, "ne", "_f_nan");
        a.tst(EB);
        far(a, "eq", "_f_nan");                     // inf * 0
        a.ldi(T, 255);
        a.cp(EB, T);
        far(a, "ne", "_f_inf");
        fraction(a, B1, B2, B3);
        far(a, "ne", "_f_nan");
        a.rjmp("_f_inf");
        a.label(bspec);                       // b infinite or NaN, a finite
        fraction(a, B1, B2, B3);
        far(a, "ne", "_f_nan");
        a.tst(EA);
        far(a, "eq", "_f_nan");
        a.rjmp("_f_inf");
    }

    private static void div(AvrAsm a) {
        a.label("_f_div");
        a.clr(ZERO);
        a.call("_fu_a");
        a.call("_fu_b");
        a.mov(T, SA);
        a.eor(T, SB);
        a.mov(SA, T);
        a.ldi(T, 255);
        String aspec = a.newLabel("fda"), bspec = a.newLabel("fdb"), bzero = a.newLabel("fdz");
        a.cp(EA, T);
        far(a, "eq", aspec);
        a.cp(EB, T);
        far(a, "eq", bspec);
        a.tst(EB);
        far(a, "eq", bzero);
        a.tst(EA);
        far(a, "eq", "_f_zero");
        // X = ea - eb + 127
        a.mov(XL, EA);
        a.clr(XH);
        a.sub(XL, EB);
        a.sbc(XH, ZERO);
        a.adiw(XL, 63);
        a.adiw(XL, 63);
        a.adiw(XL, 1);
        // dividend r5:r2 = mantissa a, divisor r9:r6 = mantissa b
        a.mov(2, A1);
        a.mov(3, A2);
        a.mov(4, A3);
        a.clr(5);
        a.mov(6, B1);
        a.mov(7, B2);
        a.mov(8, B3);
        a.clr(9);
        String ok = a.newLabel("fdok");
        a.cp(2, 6);
        a.cpc(3, 7);
        a.cpc(4, 8);
        a.brsh(ok);
        a.lsl(2);
        a.rol(3);
        a.rol(4);
        a.rol(5);
        a.sbiw(XL, 1);
        a.label(ok);
        a.clr(A0);
        a.clr(A1);
        a.clr(A2);
        a.clr(A3);
        a.ldi(CNT, 32);
        String loop = a.newLabel("fdl"), skip = a.newLabel("fds");
        a.label(loop);
        a.lsl(A0);
        a.rol(A1);
        a.rol(A2);
        a.rol(A3);
        a.cp(2, 6);
        a.cpc(3, 7);
        a.cpc(4, 8);
        a.cpc(5, 9);
        a.brlo(skip);
        a.sub(2, 6);
        a.sbc(3, 7);
        a.sbc(4, 8);
        a.sbc(5, 9);
        a.ori(A0, 1);
        a.label(skip);
        a.lsl(2);
        a.rol(3);
        a.rol(4);
        a.rol(5);
        a.dec(CNT);
        a.brne(loop);
        a.or(2, 3);
        a.or(2, 4);
        a.or(2, 5);
        a.tst(2);
        far(a, "eq", "_f_round");
        a.ori(A0, 1);
        a.rjmp("_f_round");
        a.label(aspec);                        // a infinite or NaN
        fraction(a, A1, A2, A3);
        far(a, "ne", "_f_nan");
        a.ldi(T, 255);
        a.cp(EB, T);
        far(a, "eq", "_f_nan");                      // inf / inf, inf / NaN
        a.rjmp("_f_inf");
        a.label(bspec);                        // b infinite or NaN
        fraction(a, B1, B2, B3);
        far(a, "ne", "_f_nan");
        a.rjmp("_f_zero");
        a.label(bzero);
        a.tst(EA);
        far(a, "eq", "_f_nan");                      // 0 / 0
        a.rjmp("_f_inf");
    }

    private static void fromInt(AvrAsm a) {
        a.label("_f_from_int");
        a.clr(ZERO);
        a.mov(T, A0);
        a.or(T, A1);
        a.or(T, A2);
        a.or(T, A3);
        String nz = a.newLabel("fiz");
        a.brne(nz);
        a.ret();                                // 0 is 0.0
        a.label(nz);
        a.mov(SA, A3);
        a.ldi(T, 0x80);
        a.and(SA, T);
        String pos = a.newLabel("fip");
        a.brpl(pos);
        a.com(A0);
        a.com(A1);
        a.com(A2);
        a.com(A3);
        a.subi(A0, 0xFF);
        a.sbci(A1, 0xFF);
        a.sbci(A2, 0xFF);
        a.sbci(A3, 0xFF);
        a.label(pos);
        a.ldi(XL, 158);                          // the top bit at bit 31
        a.clr(XH);
        String norm = a.newLabel("fin"), done = a.newLabel("fid");
        a.label(norm);
        a.sbrc(A3, 7);
        a.rjmp(done);
        a.lsl(A0);
        a.rol(A1);
        a.rol(A2);
        a.rol(A3);
        a.sbiw(XL, 1);
        a.rjmp(norm);
        a.label(done);
        a.rjmp("_f_round");
    }

    private static void toInt(AvrAsm a) {
        a.label("_f_to_int");
        a.call("_fu_a");
        a.ldi(T, 255);
        a.cp(EA, T);
        String special = a.newLabel("fts"), sat = a.newLabel("ftsat"), zero = a.newLabel("ftz"), done = a.newLabel("ftd");
        far(a, "eq", special);
        a.ldi(T, 127);
        a.cp(EA, T);
        far(a, "lo", zero);                            // |x| < 1
        a.ldi(T, 158);
        a.cp(EA, T);
        far(a, "sh", sat);                             // |x| >= 2^31
        // the mantissa as a whole number: A3:A2:A1 to A2:A1:A0, then shifted by e - 150 (e = 127..157)
        a.mov(A0, A1);
        a.mov(A1, A2);
        a.mov(A2, A3);
        a.clr(A3);
        a.mov(CNT, EA);
        a.subi(CNT, 150);
        String left = a.newLabel("ftl"), right = a.newLabel("ftr");
        a.brsh(left);                            // e >= 150: shift left
        a.neg(CNT);                              // 150 - e
        a.label(right);
        a.lsr(A2);
        a.ror(A1);
        a.ror(A0);
        a.dec(CNT);
        a.brne(right);
        a.rjmp(done);
        a.label(left);
        a.tst(CNT);
        a.breq(done);
        a.lsl(A0);
        a.rol(A1);
        a.rol(A2);
        a.rol(A3);
        a.dec(CNT);
        a.rjmp(left);
        a.label(done);
        a.tst(SA);
        String pos = a.newLabel("ftp");
        a.breq(pos);
        a.com(A0);
        a.com(A1);
        a.com(A2);
        a.com(A3);
        a.subi(A0, 0xFF);
        a.sbci(A1, 0xFF);
        a.sbci(A2, 0xFF);
        a.sbci(A3, 0xFF);
        a.label(pos);
        a.ret();
        a.label(special);
        fraction(a, A1, A2, A3);
        a.brne(zero);                            // NaN: 0
        a.label(sat);
        a.tst(SA);
        String smax = a.newLabel("ftmax");
        a.breq(smax);
        a.clr(A0);
        a.clr(A1);
        a.clr(A2);
        a.ldi(A3, 0x80);
        a.ret();
        a.label(smax);
        a.ldi(A0, 0xFF);
        a.ldi(A1, 0xFF);
        a.ldi(A2, 0xFF);
        a.ldi(A3, 0x7F);
        a.ret();
        a.label(zero);
        a.clr(A0);
        a.clr(A1);
        a.clr(A2);
        a.clr(A3);
        a.ret();
    }

    /** _f_cmp: T = 0 equal, 1 a > b, 0xFF a < b, 2 unordered (a NaN); then the six comparisons, 1 or 0 in r22..r25. */
    private static void compare(AvrAsm a) {
        a.label("_f_cmp");
        isNan(a, A0, A1, A2, A3, "_fc_un");
        isNan(a, B0, B1, B2, B3, "_fc_un");
        key(a, A0, A1, A2, A3);
        key(a, B0, B1, B2, B3);
        a.cp(A0, B0);
        a.cpc(A1, B1);
        a.cpc(A2, B2);
        a.cpc(A3, B3);
        String lt = a.newLabel("fclt"), eq = a.newLabel("fceq");
        a.breq(eq);
        a.brlt(lt);
        a.ldi(T, 1);
        a.ret();
        a.label(lt);
        a.ldi(T, 0xFF);
        a.ret();
        a.label(eq);
        a.ldi(T, 0);
        a.ret();
        a.label("_fc_un");
        a.ldi(T, 2);
        a.ret();
        // results: T codes that mean true
        test(a, "_f_lt", 0xFF, -1);
        test(a, "_f_gt", 1, -1);
        test(a, "_f_eq", 0, -1);
        test(a, "_f_le", 0xFF, 0);
        test(a, "_f_ge", 1, 0);
        a.label("_f_ne");
        a.call("_f_cmp");
        a.clr(A1);
        a.clr(A2);
        a.clr(A3);
        a.ldi(A0, 1);
        a.tst(T);
        String ne = a.newLabel("fcne");
        a.brne(ne);
        a.clr(A0);
        a.label(ne);
        a.ret();
    }

    private static void test(AvrAsm a, String label, int yes1, int yes2) {
        a.label(label);
        a.call("_f_cmp");
        a.clr(A1);
        a.clr(A2);
        a.clr(A3);
        a.ldi(A0, 1);
        String yes = a.newLabel("fcy");
        a.cpi(T, yes1);
        a.breq(yes);
        if (yes2 >= 0) {
            a.cpi(T, yes2);
            a.breq(yes);
        }
        a.clr(A0);
        a.label(yes);
        a.ret();
    }

    /** Jumps to nan when the packed float r3..r0 is a NaN (exponent 255, fraction not 0). */
    private static void isNan(AvrAsm a, int r0, int r1, int r2, int r3, String nan) {
        String not = a.newLabel("fnn");
        a.mov(T, r3);
        a.andi(T, 0x7F);
        a.cpi(T, 0x7F);
        a.brne(not);
        a.sbrs(r2, 7);
        a.rjmp(not);
        a.mov(T, r2);
        a.andi(T, 0x7F);
        a.or(T, r1);
        a.or(T, r0);
        a.brne(nan);
        a.label(not);
    }

    /** The float's order as a signed int: negative numbers negated, so -0 and +0 are both 0. */
    private static void key(AvrAsm a, int r0, int r1, int r2, int r3) {
        String pos = a.newLabel("fkp");
        a.sbrs(r3, 7);
        a.rjmp(pos);
        a.andi(r3, 0x7F);
        a.com(r0);
        a.com(r1);
        a.com(r2);
        a.com(r3);
        a.subi(r0, 0xFF);
        a.sbci(r1, 0xFF);
        a.sbci(r2, 0xFF);
        a.sbci(r3, 0xFF);
        a.label(pos);
    }

    /** A conditional jump of any distance: the opposite short branch over an RJMP. */
    private static void far(AvrAsm a, String cond, String label) {
        String skip = a.newLabel("fj");
        switch (cond) {
            case "eq" -> a.brne(skip);
            case "ne" -> a.breq(skip);
            case "sh" -> a.brlo(skip);
            case "lo" -> a.brsh(skip);
            default -> throw new IllegalArgumentException(cond);
        }
        a.rjmp(label);
        a.label(skip);
    }
}
