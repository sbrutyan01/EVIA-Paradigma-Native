package evia.paradigma;

import java.util.Locale;

/**
 * The AVR chips the native AVR compiler knows: memory, interrupt vectors, the registers the runtime drives, and the
 * pins as the usual boards number them (Arduino NG/8 for the ATmega8, MightyCore's standard pinout for the ATmega16
 * and ATmega32, the Arduino Mega 2560). {@link #runtime} writes the chip's part of the runtime in the back end's
 * language: register addresses, the pin tables, timer set-up and PWM.
 *
 * <p>Pins are coded as in EVIA Paradigma's AVR C runtime: the port in the high four bits (1 = A ... 12 = L) and the
 * bit in the low four; the PWM output of a pin is Arduino's timer number (TIMER0A = 1 ... TIMER5C = 18), 0 for none.
 */
enum AvrChip {
    ATMEGA8("atmega8", "ATmega8", 8192, 512, 0x60, 0x45F, false, 2, 19, 9, 13, 14, 6,
            new int[]{0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25,
                    0x30, 0x31, 0x32, 0x33, 0x34, 0x35},
            new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 4, 6, 0, 0, 0, 0, 0, 0, 0, 0}),
    ATMEGA16("atmega16", "ATmega16", 16384, 512, 0x60, 0x45F, true, 2, 21, 9, 0, 24, 8,
            mightyPins(), mightyTimers()),
    ATMEGA32("atmega32", "ATmega32", 32768, 512, 0x60, 0x85F, true, 2, 21, 11, 0, 24, 8,
            mightyPins(), mightyTimers()),
    ATMEGA2560("atmega2560", "ATmega2560 (Arduino Mega)", 262144, 8192, 0x200, 0x21FF, true, 3, 57, 23, 13, 54, 16,
            new int[]{
                    0x50, 0x51, 0x54, 0x55, 0x75, 0x53, 0x83, 0x84, 0x85, 0x86, 0x24, 0x25, 0x26, 0x27, 0xA1, 0xA0,
                    0x81, 0x80, 0x43, 0x42, 0x41, 0x40, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x37, 0x36,
                    0x35, 0x34, 0x33, 0x32, 0x31, 0x30, 0x47, 0x72, 0x71, 0x70, 0xC7, 0xC6, 0xC5, 0xC4, 0xC3, 0xC2,
                    0xC1, 0xC0, 0x23, 0x22, 0x21, 0x20, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0xB0, 0xB1,
                    0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7},
            new int[]{
                    0, 0, 10, 11, 2, 9, 12, 13, 14, 8, 7, 3, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 18, 17, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

    /** MightyCore's standard pinout: D0-D7 = PB0-PB7, D8-D15 = PD0-PD7, D16-D23 = PC0-PC7, D24-D31 = PA0-PA7 (A0-A7). */
    private static int[] mightyPins() {
        int[] p = new int[32];
        for (int i = 0; i < 8; i++) {
            p[i] = 0x20 | i;
            p[8 + i] = 0x40 | i;
            p[16 + i] = 0x30 | i;
            p[24 + i] = 0x10 | i;
        }
        return p;
    }

    /** OC0 on D3 (PB3), OC1B on D12 (PD4), OC1A on D13 (PD5), OC2 on D15 (PD7). */
    private static int[] mightyTimers() {
        int[] t = new int[32];
        t[3] = 1;
        t[12] = 4;
        t[13] = 3;
        t[15] = 6;
        return t;
    }

    final String key, display;
    final int flash, bootloader, ramStart, ramEnd, pcBytes, vectors, timer0Ovf, ledBuiltin, a0, analogPins;
    final boolean longJumps;
    final int[] pins, pinTimers;

    AvrChip(String key, String display, int flash, int bootloader, int ramStart, int ramEnd, boolean longJumps,
            int pcBytes, int vectors, int timer0Ovf, int ledBuiltin, int a0, int analogPins, int[] pins, int[] pinTimers) {
        this.key = key;
        this.display = display;
        this.flash = flash;
        this.bootloader = bootloader;
        this.ramStart = ramStart;
        this.ramEnd = ramEnd;
        this.longJumps = longJumps;
        this.pcBytes = pcBytes;
        this.vectors = vectors;
        this.timer0Ovf = timer0Ovf;
        this.ledBuiltin = ledBuiltin;
        this.a0 = a0;
        this.analogPins = analogPins;
        this.pins = pins;
        this.pinTimers = pinTimers;
    }

    String display() { return display; }
    int flashBytes() { return flash; }
    int bootloaderBytes() { return bootloader; }
    int ramStart() { return ramStart; }
    int ramEnd() { return ramEnd; }
    boolean longJumps() { return longJumps; }
    int pcBytes() { return pcBytes; }
    int vectors() { return vectors; }
    int timer0Ovf() { return timer0Ovf; }

    static AvrChip of(String s) {
        String k = s.trim().toLowerCase(Locale.ROOT).replace("-", "");
        for (AvrChip c : values()) if (c.key.equals(k) || c.key.equals("at" + k) || c.name().equalsIgnoreCase(k)) return c;
        if (k.equals("mega") || k.equals("mega2560")) return ATMEGA2560;
        throw new IllegalArgumentException("unknown AVR chip " + s + " (atmega8, atmega16, atmega32, atmega2560)");
    }

    boolean mega2560() {
        return this == ATMEGA2560;
    }

    /** The value of a board name in a program: HIGH, LOW, LED_BUILTIN, A0..A15; null for any other name. */
    Double constant(String name) {
        switch (name) {
            case "HIGH": return 1.0;
            case "LOW": return 0.0;
            case "LED_BUILTIN": return (double) ledBuiltin;
            default:
                if (name.matches("A(\\d|1[0-5])")) {
                    int n = Integer.parseInt(name.substring(1));
                    return n < analogPins ? (double) (a0 + n) : null;
                }
                return null;
        }
    }

    /** The chip's part of the runtime, for a clock of fcpu Hz. */
    String runtime(long fcpu) {
        StringBuilder s = new StringBuilder("// ---- the " + display + " at " + fcpu / 1000000.0 + " MHz\n");
        s.append("const F_CPU = ").append(fcpu).append(";\n");
        s.append("const MAX_DEPTH = ").append(switch (this) { case ATMEGA8, ATMEGA16 -> 24; case ATMEGA32 -> 64; default -> 400; }).append(";\n");
        s.append("const HEAP = ").append(switch (this) { case ATMEGA8, ATMEGA16 -> 256; case ATMEGA32 -> 768; default -> 3072; }).append(";\n");
        s.append("const A0_PIN = ").append(a0).append(";\n");
        s.append("const UBRR_115200 = ").append(ubrr(fcpu, 115200)).append(";\n");
        if (mega2560()) {
            s.append("""
                    const UDR = 0xC6;
                    const UCSRA = 0xC0;
                    const UCSRB = 0xC1;
                    const UCSRC = 0xC2;
                    const UBRRL = 0xC4;
                    const UBRRH = 0xC5;
                    const URSEL = 0;
                    const ADMUX = 0x7C;
                    const ADCSRA = 0x7A;
                    const ADCSRB = 0x7B;
                    const ADCL = 0x78;
                    const ADCH = 0x79;
                    """);
        } else {
            s.append("""
                    const UDR = 0x2C;
                    const UCSRA = 0x2B;
                    const UCSRB = 0x2A;
                    const UCSRC = 0x40;
                    const UBRRL = 0x29;
                    const UBRRH = 0x40;
                    const URSEL = 0x80;
                    const ADMUX = 0x27;
                    const ADCSRA = 0x26;
                    const ADCSRB = 0;
                    const ADCL = 0x24;
                    const ADCH = 0x25;
                    """);
        }
        // millis: timer 0 overflows every 64 * 256 clock cycles
        long us = 64L * 256 * 1000000 / fcpu;
        s.append("const MILLIS_INC = ").append(us / 1000).append(";\n");
        s.append("const FRACT_INC = ").append((us % 1000) >> 3).append(";\n");
        s.append("table PIN_CODES = ").append(join(pins)).append(";\n");
        s.append("table PIN_TIMERS = ").append(join(pinTimers)).append(";\n");
        s.append("const PIN_COUNT = ").append(pins.length).append(";\n\n");
        // port registers: the address of PINx by port number (1 = A ... 12 = L); DDRx and PORTx follow it
        int[][] ports = mega2560()
                ? new int[][]{{1, 0x20}, {2, 0x23}, {3, 0x26}, {4, 0x29}, {5, 0x2C}, {6, 0x2F}, {7, 0x32}, {8, 0x100},
                        {10, 0x103}, {11, 0x106}, {12, 0x109}}
                : this == ATMEGA8 ? new int[][]{{2, 0x36}, {3, 0x33}, {4, 0x30}}
                : new int[][]{{1, 0x39}, {2, 0x36}, {3, 0x33}, {4, 0x30}};
        int[] pinAddr = new int[26];
        for (int[] p : ports) {
            pinAddr[2 * p[0]] = p[1] & 0xFF;
            pinAddr[2 * p[0] + 1] = p[1] >> 8;
        }
        s.append("table PORT_PIN = ").append(join(pinAddr)).append(";\n\n");
        s.append("""
                /** DDR (which 0), PORT (1) or PIN (2) of a port; 0 when the chip has no such port. */
                fn port_reg(port, which): int {
                    var pin = loadp16(PORT_PIN + 2 * port);
                    if (pin == 0) { return 0; }
                    if (which == 2) { return pin; }
                    return pin + which + 1;
                }

                """);
        // timers: millis on timer 0, PWM on the others (8-bit phase correct, prescaler 64, as Arduino does)
        s.append("fn chip_init() {\n");
        switch (this) {
            case ATMEGA8 -> s.append("""
                        store8(0x53, 3);        // TCCR0: clock / 64
                        store8(0x59, load8(0x59) | 1);   // TIMSK: TOIE0
                        store8(0x4F, 1);        // TCCR1A: WGM10, 8-bit phase correct PWM
                        store8(0x4E, 3);        // TCCR1B: clock / 64
                        store8(0x45, 0x44);     // TCCR2: WGM20, clock / 64
                    """);
            case ATMEGA16, ATMEGA32 -> s.append("""
                        store8(0x53, 0x4B);     // TCCR0: fast PWM, clock / 64
                        store8(0x59, load8(0x59) | 1);   // TIMSK: TOIE0
                        store8(0x4F, 1);        // TCCR1A: WGM10
                        store8(0x4E, 3);        // TCCR1B: clock / 64
                        store8(0x45, 0x44);     // TCCR2: WGM20, clock / 64
                    """);
            default -> s.append("""
                        store8(0x44, 3);        // TCCR0A: fast PWM
                        store8(0x45, 3);        // TCCR0B: clock / 64
                        store8(0x6E, 1);        // TIMSK0: TOIE0
                        store8(0x80, 1);        // timer 1: TCCR1A WGM10, TCCR1B clock / 64
                        store8(0x81, 3);
                        store8(0xB0, 1);        // timer 2: WGM20, clock / 64
                        store8(0xB1, 4);
                        store8(0x90, 1);        // timers 3, 4, 5
                        store8(0x91, 3);
                        store8(0xA0, 1);
                        store8(0xA1, 3);
                        store8(0x120, 1);
                        store8(0x121, 3);
                    """);
        }
        s.append("    sei();\n}\n\n");
        // PWM channels by timer code: TCCR register (2 bytes), COM bit, OCR register (2 bytes)
        int[][] ch = switch (this) {   // {timer code, TCCR register, COM bit, OCR register}
            case ATMEGA8 -> new int[][]{{3, 0x4F, 7, 0x4A}, {4, 0x4F, 5, 0x48}, {6, 0x45, 5, 0x43}};
            case ATMEGA16, ATMEGA32 -> new int[][]{{1, 0x53, 5, 0x5C}, {3, 0x4F, 7, 0x4A}, {4, 0x4F, 5, 0x48}, {6, 0x45, 5, 0x43}};
            default -> new int[][]{{1, 0x44, 7, 0x47}, {2, 0x44, 5, 0x48}, {3, 0x80, 7, 0x88}, {4, 0x80, 5, 0x8A},
                    {7, 0xB0, 7, 0xB3}, {8, 0xB0, 5, 0xB4}, {9, 0x90, 7, 0x98}, {10, 0x90, 5, 0x9A}, {11, 0x90, 3, 0x9C},
                    {12, 0xA0, 7, 0xA8}, {13, 0xA0, 5, 0xAA}, {14, 0xA0, 3, 0xAC}, {16, 0x120, 7, 0x128},
                    {17, 0x120, 5, 0x12A}, {18, 0x120, 3, 0x12C}};
        };
        int[] pwm = new int[19 * 5];
        for (int[] c : ch) {
            int o = 5 * c[0];
            pwm[o] = c[1] & 0xFF;
            pwm[o + 1] = c[1] >> 8;
            pwm[o + 2] = c[2];
            pwm[o + 3] = c[3] & 0xFF;
            pwm[o + 4] = c[3] >> 8;
        }
        s.append("table PWM = ").append(join(pwm)).append(";\n\n");
        s.append("""
                /** Connects (duty >= 0) or disconnects (-1) a timer output, by Arduino's timer number. */
                fn pwm_set(timer, duty) {
                    var t = PWM + 5 * timer;
                    var tccr = loadp16(t);
                    if (tccr == 0) { return; }
                    pwm_channel(tccr, loadp8(t + 2), loadp16(t + 3), duty);
                }
                """);
        return s.toString();
    }

    /** The baud-rate register for double speed (U2X), rounded as the runtime rounds it. */
    static long ubrr(long fcpu, long baud) {
        return (fcpu + 4 * baud) / (8 * baud) - 1;
    }

    private static String join(int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) sb.append(i == 0 ? "" : ", ").append("0x").append(Integer.toHexString(v[i]));
        return sb.toString();
    }
}
