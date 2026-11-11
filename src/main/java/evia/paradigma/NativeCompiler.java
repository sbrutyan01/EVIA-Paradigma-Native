package evia.paradigma;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Compiles a block program straight to a Linux x86-64 executable: no C, no compiler, no linker. The blocks are
 * lowered to the native back end's own small language ({@link NativeLang}), every block a label and every wire a
 * jump, joined with the runtime (resources/evia/paradigma/native), turned into machine code by {@link NativeAsm}
 * and written as an ELF file by {@link NativeElf}.
 *
 * <p>The executable behaves as the interpreter does: the same output, the same error messages on stderr and the
 * same exit code; numbers are written and read exactly as Java writes and reads them.
 */
final class NativeCompiler {

    /** The executable, and the lowered program (for a look under the hood). */
    record Result(byte[] executable, String lowered) {}

    private final Library lib;
    private final boolean FAST;
    private final StringBuilder out = new StringBuilder();
    private final Map<BlockDef, String> className = new LinkedHashMap<>();
    private final Set<String> globals = new TreeSet<>();
    private final Map<BlockDef, List<String>> fields = new HashMap<>();
    /** Child objects of a class (or of the main program, key null): member name -> class. */
    private final Map<BlockDef, LinkedHashMap<String, BlockDef>> children = new HashMap<>();

    /** The AVR chip the program is for, or null for a Linux x86-64 executable. */
    private final AvrChip chip;
    /** Bytes per field and object slot: 8 on Linux, 4 on an AVR chip (whose values are 4 bytes and RAM is small). */
    private final int SLOT;
    /** The AVR chip's clock in Hz (for UART Open with a constant baud rate). */
    private long fcpu = 16000000;

    /** RTOS: the classes with a method named task (one object each, its task started before the main program). */
    private final List<BlockDef> tasks = new ArrayList<>();
    /** The program runs on the RTOS kernel (avr/rtos.er): it has tasks or calls the Rtos class, on an AVR chip. */
    private boolean rtos;
    static final String RTOS_CLASS = "Rtos", TASK_METHOD = "task";
    private static final int STACK_DEFAULT = 256, STACK_MIN = 128, IDLE_STACK = 112;

    private NativeCompiler(Library lib, Target t, AvrChip chip) {
        this.lib = lib;
        this.chip = chip;
        this.FAST = t.fast() || chip != null;
        this.SLOT = chip != null ? 4 : 8;
    }

    /** An AVR program: Intel HEX, its size in flash and RAM, and the lowered program. */
    record AvrResult(String hex, int flashBytes, int ramBytes, String lowered) {}

    /** The program for an AVR chip at a clock of fcpu Hz: fast values (32-bit floats), the board blocks on the chip. */
    static AvrResult compileAvr(Library lib, AvrChip chip, long fcpu) {
        Interpreter.prepare(lib);
        NativeCompiler c = new NativeCompiler(lib, new Target(Target.Lang.CPP20, Target.Flow.STRUCTURED, Target.Values.FAST), chip);
        c.fcpu = fcpu;
        String program = c.lower();
        NativeLang l = new NativeLang();
        l.add(read("avr/float.er"), "avr/float.er");
        l.add(chip.runtime(fcpu, c.rtos ? c.heapNeeded() : -1), chip.key + ".er");
        l.add(read("avr/base.er"), "avr/base.er");
        if (c.rtos) l.add(read("avr/rtos.er"), "avr/rtos.er");
        l.add(program, "program");
        AvrGen g = new AvrGen(l, chip);
        int[] words = g.compile();
        return new AvrResult(AvrAsm.hex(words), words.length * 2, g.ramUsed(), program);
    }

    static Result compile(Library lib, Target t) {
        Interpreter.prepare(lib);
        NativeCompiler c = new NativeCompiler(lib, t, null);
        String program = c.lower();
        NativeLang l = new NativeLang();
        l.add(read("common.er"), "common.er");
        l.add(read(t.fast() ? "fast.er" : "safe.er"), t.fast() ? "fast.er" : "safe.er");
        l.add(program, "program");
        NativeAsm asm = l.compile("main");
        NativeAsm.Linked linked = asm.link(NativeElf.codeAddr(), NativeElf.dataAddr(asm.code.size()), "_start");
        return new Result(NativeElf.write(linked), program);
    }

    static String read(String name) {
        try (InputStream in = NativeCompiler.class.getResourceAsStream("native/" + name)) {
            if (in == null) throw new IllegalStateException("native/" + name + " is missing from the application");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ================================================================ the program

    private void line(String s) {
        out.append(s).append('\n');
    }

    private String lower() {
        Set<String> used = new HashSet<>();
        for (BlockDef d : lib.defs()) {
            String n = "c_" + sanitize(d.name);
            if (!used.add(n)) n = n + "_" + d.id;
            used.add(n);
            className.put(d, n);
        }
        collectNames(lib.main.getBlocks(), null);
        for (BlockDef d : lib.defs()) {
            LinkedHashSet<String> f = new LinkedHashSet<>();
            for (BlockDef.Field x : d.fields) f.add(x.name());
            for (Method m : d.methods) f.addAll(m.params);
            fieldsOf.put(d, f);
            for (Method m : d.methods) collectNames(m.body.getBlocks(), d);
            for (Expr e : d.fieldInits) collectExpr(e, d, null);
            fields.put(d, new ArrayList<>(f));
        }
        children.put(null, objectMembers(lib.main, "o", new LinkedHashSet<>(), null));
        for (BlockDef d : lib.defs()) {
            LinkedHashMap<String, BlockDef> all = new LinkedHashMap<>();
            Set<String> named = new LinkedHashSet<>();
            for (Method m : d.methods) all.putAll(objectMembers(m.body, "o_" + sanitize(m.name) + "_", named, null));
            children.put(d, all);
        }

        if (chip != null) findTasks();

        String type = FAST ? "float" : "int";
        line("// ---- program variables");
        for (String g : globals) line("var v_" + g + ": " + type + ";");
        for (String m : children.get(null).keySet()) line("var m_" + m + ": int;");
        line("");
        line("fn main() {");
        line("    runtime_init();");
        if (!FAST) for (String g : globals) line("    v_" + g + " = UNDEF;");
        if (rtos) {
            rtosSetup();
        } else {
            line("    program_run();");
            line("    exit_program(0);");
            line("}");
        }
        for (BlockDef d : lib.defs()) classCode(d);
        // the main program's objects, created on first use
        for (Map.Entry<String, BlockDef> o : children.get(null).entrySet()) {
            line("");
            line("fn get_main_" + o.getKey() + "(): int {");
            line("    if (m_" + o.getKey() + " == 0) { m_" + o.getKey() + " = " + className.get(o.getValue()) + "_new(); }");
            line("    return m_" + o.getKey() + ";");
            line("}");
        }
        line("");
        line("fn program_run() {");
        Block start = null;
        for (Block b : lib.main.getBlocks()) if (b.type == BlockType.START) start = b;
        new Graph(null, null).write(start);
        line("}");
        return out.toString();
    }

    private final Map<BlockDef, LinkedHashSet<String>> fieldsOf = new HashMap<>();

    // ================================================================ RTOS

    private void findTasks() {
        for (BlockDef d : lib.defs()) {
            for (Method m : d.methods) {
                if (!m.name.equals(TASK_METHOD)) continue;
                if (!m.params.isEmpty()) {
                    throw new FlowException(d.name + "." + TASK_METHOD + " is a task: it can't have parameters"
                            + " (give the class fields instead)");
                }
                tasks.add(d);
            }
        }
        boolean calls = false;
        for (Block b : allBlocks()) if (b.type == BlockType.CUSTOM && isRtos(b)) calls = true;
        rtos = calls || !tasks.isEmpty();
    }

    private List<Block> allBlocks() {
        List<Block> all = new ArrayList<>(lib.main.getBlocks());
        for (BlockDef d : lib.defs()) for (Method m : d.methods) all.addAll(m.body.getBlocks());
        return all;
    }

    private static boolean isRtos(Block b) {
        return b.def != null && b.def.name.equals(RTOS_CLASS);
    }

    /** A number field's initial value (a plain number), or dflt. */
    private static int numberField(BlockDef d, String name, int dflt) {
        for (int i = 0; i < d.fields.size(); i++) {
            if (!d.fields.get(i).name().equals(name)) continue;
            if (d.fieldInits.get(i) instanceof Expr.Num n) return (int) Math.round(n.value());
            throw new FlowException(d.name + "." + name + " must start as a plain number (the RTOS reads it when"
                    + " the program is built)");
        }
        return dflt;
    }

    /**
     * The heap the objects take (RTOS: RAM goes to the task stacks instead): every object is a member of the main
     * program, of a task or of another object, so their number is fixed. -1 if a class contains itself.
     */
    int heapNeeded() {
        int sum = 8;
        for (BlockDef d : tasks) sum += objectBytes(d, new HashSet<>());
        for (BlockDef d : children.get(null).values()) sum += objectBytes(d, new HashSet<>());
        return sum < 0 ? -1 : sum;
    }

    private int objectBytes(BlockDef d, Set<BlockDef> path) {
        if (!path.add(d)) return Integer.MIN_VALUE / 4;
        int n = (Math.max(SLOT * (fields.get(d).size() + children.get(d).size()), 8) + 3) & ~3;
        for (BlockDef c : children.get(d).values()) n += objectBytes(c, path);
        path.remove(d);
        return n;
    }

    /** The rest of main(): the tasks' objects and stacks, then the main program as task 0. */
    private void rtosSetup() {
        if (tasks.size() > 14) throw new FlowException("the RTOS runs at most 14 tasks; this program has " + tasks.size());
        StringBuilder decls = new StringBuilder();
        line("    os_init();");
        for (int i = 0; i < tasks.size(); i++) {
            BlockDef d = tasks.get(i);
            int prio = numberField(d, "priority", 1), stack = numberField(d, "stack", STACK_DEFAULT);
            if (prio < 1 || prio > 250) throw new FlowException(d.name + ".priority must be 1 to 250 (the main program has 1)");
            if (stack < STACK_MIN) throw new FlowException(d.name + ".stack must be at least " + STACK_MIN + " bytes");
            decls.append("var os_o_").append(i + 1).append(": int;\n");
            decls.append("buffer os_stack_").append(i + 1).append("[").append(stack).append("];\n");
            line("    os_o_" + (i + 1) + " = " + className.get(d) + "_new();");
            line("    os_add(" + prio + ", os_stack_" + (i + 1) + ", os_stack_" + (i + 1) + " + " + (stack - 1) + ");");
        }
        decls.append("buffer os_stack_idle[").append(IDLE_STACK).append("];\n");
        line("    os_add(0, os_stack_idle, os_stack_idle + " + (IDLE_STACK - 1) + ");");
        line("    os_yield();");
        line("    program_run();");
        line("    os_exit();");
        line("}");
        line("");
        line("// ---- RTOS tasks");
        out.append(decls);
        line("fn os_run_task(id) {");
        for (int i = 0; i < tasks.size(); i++) {
            line("    if (id == " + (i + 1) + ") { " + className.get(tasks.get(i)) + "_m_" + TASK_METHOD + "(os_o_" + (i + 1) + "); return; }");
        }
        line("    os_idle();");
        line("}");
    }

    private int offset(BlockDef d, String field) {
        int i = fields.get(d).indexOf(field);
        if (i < 0) throw new IllegalStateException("no field " + field + " in " + d.name);
        return SLOT * i;
    }

    private int childOffset(BlockDef d, String member) {
        int i = new ArrayList<>(children.get(d).keySet()).indexOf(member);
        return SLOT * (fields.get(d).size() + i);
    }

    private void classCode(BlockDef d) {
        String cn = className.get(d);
        int size = SLOT * (fields.get(d).size() + children.get(d).size());
        line("");
        line("// ---- class " + comment(d.name));
        line("fn " + cn + "_new(): int {");
        line("    var self = alloc(" + Math.max(size, 8) + ");");
        Method ctor = d.constructor();
        Graph w = new Graph(ctor != null ? ctor : d.methods.get(0), d);
        for (int i = 0; i < d.fields.size(); i++) {
            BlockDef.Field f = d.fields.get(i);
            Expr e = d.fieldInits.get(i);
            int off = offset(d, f.name());
            if (FAST) {
                line("    storef(self + " + off + ", " + w.fastNum(e, null) + ");");
            } else if (e instanceof Expr.Num || e instanceof Expr.Str) {
                line("    store64(self + " + off + ", " + w.expr(e, null) + ");");
            } else {
                line("    err_prefix = " + string("initial value of field " + d.name + "." + f.name() + ": ") + ";");
                line("    store64(self + " + off + ", " + w.expr(e, null) + ");");
                line("    err_prefix = 0;");
            }
        }
        if (ctor != null) {
            // the interpreter runs the constructor without binding its parameters: they keep their fields' values
            StringBuilder args = new StringBuilder("self");
            for (String p : ctor.params) args.append(", ").append(FAST ? "loadf" : "load64").append("(self + ").append(offset(d, p)).append(")");
            line("    " + cn + "_m_" + ctor.name + "(" + args + ");");
        }
        line("    return self;");
        line("}");
        for (Map.Entry<String, BlockDef> o : children.get(d).entrySet()) {
            int off = childOffset(d, o.getKey());
            line("");
            line("fn " + cn + "_get_" + o.getKey() + "(self): int {");
            line("    var o = load64(self + " + off + ");");
            line("    if (o == 0) {");
            line("        o = " + className.get(o.getValue()) + "_new();");
            line("        store64(self + " + off + ", o);");
            line("    }");
            line("    return o;");
            line("}");
        }
        for (Method m : d.methods) {
            StringBuilder sig = new StringBuilder("fn " + cn + "_m_" + m.name + "(self");
            for (String p : m.params) sig.append(", p_").append(p).append(FAST ? ": float" : "");
            line("");
            line("// " + comment(m.qualified()) + "; exits: " + String.join(", ", m.exitNames()));
            line(sig + "): int {");
            line("    enter();");
            for (String p : m.params) line("    " + (FAST ? "storef" : "store64") + "(self + " + offset(d, p) + ", p_" + p + ");");
            new Graph(m, d).write(m.enter());
            line("}");
        }
    }

    // ================================================================ a graph: every block a label, every wire a jump

    private final class Graph {
        final Method owner;      // null: the main program
        final BlockDef cls;
        int temp;

        Graph(Method owner, BlockDef cls) {
            this.owner = owner;
            this.cls = cls;
        }

        void stmt(String s) {
            line("    " + s);
        }

        void write(Block entry) {
            stmt("var rt_x = 0;");
            Set<Block> seen = new LinkedHashSet<>();
            Deque<Block> todo = new ArrayDeque<>(List.of(entry));
            while (!todo.isEmpty()) {
                Block b = todo.pop();
                if (!seen.add(b)) continue;
                for (Block n : b.next) if (n != null) todo.push(n);
            }
            stmt("goto B" + entry.id + ";");
            for (Block b : seen) block(b);
            if (owner == null) line("label done:");
        }

        void block(Block b) {
            Interpreter.Compiled c = b.compiled();
            line("label B" + b.id + ":   // " + comment(describe(b)));
            switch (b.type) {
                case START, ENTER -> go(b, 0);
                case SET -> {
                    assign(c.var(), value(c.expr(), b), b);
                    go(b, 0);
                }
                case PRINT -> {
                    print(c.expr(), b);
                    go(b, 0);
                }
                case INPUT -> {
                    if (rtos) stmt("os_console();");
                    assign(c.var(), (FAST ? "input_num(" : "input(") + string(c.var()) + ")", b);
                    if (rtos) stmt("os_console_done();");
                    go(b, 0);
                }
                case DELAY -> {
                    stmt((rtos ? "os_sleep(" : FAST ? "delay(" : "delay_v(") + value(c.expr(), b) + ");");
                    go(b, 0);
                }
                case MILLIS -> {
                    assign(c.var(), FAST ? "millis()" : "millis_v()", b);
                    go(b, 0);
                }
                case IF -> {
                    stmt("if (" + cond(c.expr(), b) + ") { " + target(b, 0) + " }");
                    stmt(target(b, 1));
                }
                case END -> stmt(owner == null ? "goto done;" : "return 0;");
                case EXIT -> {
                    stmt("leave();");
                    stmt("return " + owner.exitNames().indexOf(c.var()) + ";   // exit '" + comment(c.var()) + "'");
                }
                case CUSTOM -> {
                    if (!rtos || !isRtos(b) || !rtosCall(b, c)) call(b, c);
                    if (b.next.length == 1) {
                        go(b, 0);
                    } else {
                        for (int i = 0; i < b.next.length; i++) stmt("if (rt_x == " + i + ") { " + target(b, i) + " }");
                        stmt("fail(\"bad exit index\");");
                    }
                }
                case FOR, WHILE, DO_WHILE -> loop(b, c);
                case PIN_MODE, DIGITAL_WRITE, DIGITAL_READ, ANALOG_READ, ANALOG_WRITE, UART_OPEN, UART_WRITE, UART_READ -> {
                    if (chip == null) throw boardOnly(b);
                    boardBlock(b, c);
                    go(b, 0);
                }
                default -> {
                    if (chip != null && b.type.isBoard()) {
                        throw new FlowException(b, "the " + b.type.title + " block is not in the native AVR compiler yet:"
                                + " pins, PWM, Analog Read, Delay, Millis and the UART are");
                    }
                    if (b.type == BlockType.SENSOR) {
                        throw new FlowException(b, "the Phone Sensor block reads a phone's sensors: choose Language Android"
                                + " (Run simulates them on the computer)");
                    }
                    throw new FlowException(b, "the " + b.type.title + " block runs on a microcontroller: choose Language"
                            + " Arduino or AVR C (Run simulates the board on the computer)");
                }
            }
        }

        FlowException boardOnly(Block b) {
            return new FlowException(b, "the " + b.type.title + " block runs on a microcontroller: choose an AVR chip"
                    + " as what Build makes (Run simulates the board on the computer)");
        }

        /** Pins, PWM, the ADC and the UART of an AVR chip, through its runtime. */
        void boardBlock(Block b, Interpreter.Compiled c) {
            switch (b.type) {
                case PIN_MODE -> stmt("pin_mode(" + pinOf(c.expr(), b) + ", "
                        + (c.var().equals("OUTPUT") ? 1 : c.var().equals("INPUT_PULLUP") ? 2 : 0) + ");");
                case DIGITAL_WRITE -> stmt((usesPwm() ? "digital_write_pwm(" : "digital_write(") + pinOf(c.expr(), b) + ", "
                        + fastNum(c.args().get(0), b) + " != 0.0);");
                case ANALOG_WRITE -> stmt("analog_write(" + pinOf(c.expr(), b) + ", f2i(" + fastNum(c.args().get(0), b) + "));");
                case DIGITAL_READ -> assign(c.var(), "i2f(digital_read(" + pinOf(c.expr(), b) + "))", b);
                case ANALOG_READ -> assign(c.var(), "i2f(analog_read(" + pinOf(c.expr(), b) + "))", b);
                case UART_OPEN -> stmt(c.expr() instanceof Expr.Num n
                        ? "uart_ubrr(" + AvrChip.ubrr(fcpu, (long) n.value()) + ");"
                        : "uart_init(f2i(" + fastNum(c.expr(), b) + "));");
                case UART_WRITE -> stmt("uart_put(f2i(" + fastNum(c.expr(), b) + ") & 255);");
                case UART_READ -> assign(c.var(), "i2f(uart_read())", b);
                default -> throw boardOnly(b);
            }
        }

        /** Analog Write anywhere: Digital Write must then disconnect a pin's PWM first. */
        boolean usesPwm() {
            for (FlowCanvas cv : lib.canvases()) for (Block x : cv.getBlocks()) if (x.type == BlockType.ANALOG_WRITE) return true;
            return false;
        }

        String pinOf(Expr e, Block b) {
            return "f2i(" + fastNum(e, b) + ")";
        }

        /**
         * A loop block has three labels: B (arriving from before it), B_next (a wire back from its body) and, for a
         * for loop, B_test. This is how the interpreter runs them.
         */
        void loop(Block l, Interpreter.Compiled c) {
            String id = "B" + l.id;
            boolean emptyBody = l.next[0] == null || l.next[0] == l;
            String body = emptyBody ? "goto " + id + "_next;" : target(l, 0);
            String test = c.expr() == null ? null : cond(c.expr(), l);
            switch (l.type) {
                case FOR -> {
                    if (c.init() != null) assign(c.init().var(), value(c.init().expr(), l), l);
                    stmt("goto " + id + "_test;");
                    line("label " + id + "_next:");
                    if (c.step() != null) assign(c.step().var(), value(c.step().expr(), l), l);
                    line("label " + id + "_test:");
                    testThen(l, test, body);
                }
                case WHILE -> {
                    line("label " + id + "_next:");
                    testThen(l, test, body);
                }
                default -> {
                    stmt(body);
                    line("label " + id + "_next:");
                    testThen(l, test, body);
                }
            }
        }

        void testThen(Block l, String test, String body) {
            if (test == null) {
                stmt(body);
                return;
            }
            stmt("if (" + test + ") { " + body + " }");
            stmt(target(l, 1));
        }

        /** The jump along output port of b, or what the interpreter does when it is not connected. */
        String target(Block b, int port) {
            Block tg = b.next[port];
            if (tg == null) return missing(b, port);
            boolean back = tg.type.isLoop() && (tg.loopBody.contains(b) || b == tg);
            return "goto B" + tg.id + (back ? "_next" : "") + ";";
        }

        void go(Block b, int port) {
            stmt(target(b, port));
        }

        String missing(Block b, int port) {
            if (owner != null) {
                return "fail(" + string("method " + owner.qualified() + ": this path ends without reaching an Exit block") + ");";
            }
            String msg = b.def != null
                    ? "(warning: exit '" + b.portName(port) + "' of " + b.method().qualified() + " is not connected - program ends)"
                    : "(warning: " + b.title() + " block has no outgoing connection - program ends)";
            return "out_line(" + string(msg) + "); goto done;";
        }

        /**
         * A call of the Rtos class's sleep, yield, lock, unlock, wait or signal: the kernel, not the flowchart.
         * False for its other methods (they run as written).
         */
        boolean rtosCall(Block b, Interpreter.Compiled c) {
            String m = b.method().name;
            List<String> args = new ArrayList<>();
            for (Expr e : c.args()) args.add(value(e, b));
            int want = switch (m) {
                case "sleep", "lock", "unlock", "signal" -> 1;
                case "yield" -> 0;
                case "wait" -> 2;
                default -> -1;
            };
            if (want < 0) return false;
            if (args.size() != want) {
                throw new FlowException(b, "Rtos." + m + " takes " + want + " value" + (want == 1 ? "" : "s"));
            }
            List<String> exits = b.method().exitNames();
            switch (m) {
                case "sleep" -> stmt("os_sleep(" + args.get(0) + ");");
                case "yield" -> stmt("os_yield();");
                case "lock" -> stmt("os_lock(" + args.get(0) + ");");
                case "unlock" -> stmt("os_unlock(" + args.get(0) + ");");
                case "signal" -> stmt("os_signal(" + args.get(0) + ");");
                default -> {
                    int got = Math.max(0, exits.indexOf("got")), timeout = Math.max(0, exits.indexOf("timeout"));
                    stmt("if (os_wait(" + args.get(0) + ", " + args.get(1) + ") != 0) { rt_x = " + got + "; } else { rt_x = "
                            + timeout + "; }");
                    return true;
                }
            }
            stmt("rt_x = 0;");
            return true;
        }

        /** The object first (created on first use), then the arguments from left to right, then the call. */
        void call(Block b, Interpreter.Compiled c) {
            String member = !b.objectName().isEmpty() ? "obj_" + b.objectName()
                    : owner == null ? "o" + b.id : "o_" + sanitize(owner.name) + "_" + b.id;
            int n = temp++;
            String obj = "t" + n + "_o";
            stmt("var " + obj + " = " + (owner == null ? "get_main_" + member + "()"
                    : className.get(cls) + "_get_" + member + "(self)") + ";");
            List<String> args = new ArrayList<>();
            for (int i = 0; i < c.args().size(); i++) {
                String a = "t" + n + "_a" + i;
                stmt("var " + a + " = " + value(c.args().get(i), b) + ";");
                args.add(a);
            }
            stmt("rt_x = " + className.get(b.def) + "_m_" + b.method().name + "(" + obj
                    + (args.isEmpty() ? "" : ", " + String.join(", ", args)) + ");");
        }

        void assign(String var, String code, Block b) {
            if (var.startsWith("this.")) {
                if (cls == null) throw new FlowException(b, "'this' can only be used inside a class method");
                stmt((FAST ? "storef" : "store64") + "(self + " + offset(cls, var.substring(5)) + ", " + code + ");");
            } else {
                stmt("v_" + var + " = " + code + ";");
            }
        }

        String value(Expr e, Block b) {
            return FAST ? fastNum(e, b) : expr(e, b);
        }

        void print(Expr e, Block b) {
            if (!FAST) {
                stmt("print(" + expr(e, b) + ");");
                return;
            }
            F f = fast(e, b);
            List<F> parts = f.kind == TEXT ? f.parts : List.of(f);
            if (rtos) {     // the numbers first (they may call methods), then the line in one go
                List<String> nums = new ArrayList<>();
                for (F p : parts) {
                    if (p.kind == TEXT) continue;
                    String t = "t" + temp++ + "_p";
                    stmt("var " + t + " = " + numCode(p) + ";");
                    nums.add(t);
                }
                stmt("os_console();");
                int k = 0;
                for (F p : parts) stmt(p.kind == TEXT ? "out_str(" + string(p.text) + ");" : "out_num(" + nums.get(k++) + ");");
                stmt("out_byte(10);");
                stmt("os_console_done();");
                return;
            }
            for (F p : parts) stmt(p.kind == TEXT ? "out_str(" + string(p.text) + ");" : "out_num(" + numCode(p) + ");");
            stmt("out_byte(10);");
        }

        String cond(Expr e, Block b) {
            return FAST ? boolCode(fast(e, b), b) : "truthy(" + expr(e, b) + ")";
        }

        // ============================================================ safe values: int words

        String expr(Expr e, Block b) {
            return switch (e) {
                case Expr.Num n -> hex(Double.doubleToRawLongBits(n.value()));
                case Expr.Str s -> "box_str(" + string(s.value()) + ")";
                case Expr.Var v -> read(v.name(), b);
                case Expr.Unary u -> (u.op().equals("-") ? "v_neg(" : "v_not(") + expr(u.operand(), b) + ")";
                case Expr.Binary x -> {
                    String l = expr(x.left(), b), r = expr(x.right(), b);
                    yield switch (x.op()) {
                        case "&&" -> "B(truthy(" + l + ") && truthy(" + r + "))";
                        case "||" -> "B(truthy(" + l + ") || truthy(" + r + "))";
                        case "+" -> "v_add(" + l + ", " + r + ")";
                        case "-" -> "v_sub(" + l + ", " + r + ")";
                        case "*" -> "v_mul(" + l + ", " + r + ")";
                        case "/" -> "v_div(" + l + ", " + r + ")";
                        case "%" -> "v_mod(" + l + ", " + r + ")";
                        case "==" -> "v_eq(" + l + ", " + r + ")";
                        case "!=" -> "v_ne(" + l + ", " + r + ")";
                        case "<" -> "v_lt(" + l + ", " + r + ")";
                        case "<=" -> "v_le(" + l + ", " + r + ")";
                        case ">" -> "v_gt(" + l + ", " + r + ")";
                        case ">=" -> "v_ge(" + l + ", " + r + ")";
                        default -> throw new FlowException(b, "unknown operator " + x.op());
                    };
                }
            };
        }

        String read(String name, Block b) {
            if (name.startsWith("this.")) {
                if (cls == null) throw new FlowException(b, "'this' can only be used inside a class method");
                return (FAST ? "loadf" : "load64") + "(self + " + offset(cls, name.substring(5)) + ")";
            }
            Double board = chip != null ? chip.constant(name) : BoardSim.constant(name);
            if (board != null) return FAST ? floatLit(board) : hex(Double.doubleToRawLongBits(board));
            if (FAST) return "v_" + name;
            return "rd(v_" + name + ", " + string(name) + ")";
        }

        // ============================================================ fast values: floats

        static final int NUM = 0, BOOL = 1, TEXT = 2;

        record F(String code, int kind, List<F> parts, String text) {}

        F fast(Expr e, Block b) {
            return switch (e) {
                case Expr.Num n -> new F(floatLit(n.value()), NUM, null, null);
                case Expr.Str s -> new F(null, TEXT, List.of(new F(null, TEXT, List.of(), s.value())), s.value());
                case Expr.Var v -> new F(read(v.name(), b), NUM, null, null);
                case Expr.Unary u -> {
                    F x = fast(u.operand(), b);
                    needNumber(x, b);
                    yield u.op().equals("-") ? new F("(-" + numCode(x) + ")", NUM, null, null)
                            : new F("(!" + boolCode(x, b) + ")", BOOL, null, null);
                }
                case Expr.Binary x -> {
                    F l = fast(x.left(), b), r = fast(x.right(), b);
                    String op = x.op();
                    if (op.equals("+") && (l.kind == TEXT || r.kind == TEXT)) {
                        List<F> parts = new ArrayList<>();
                        parts.addAll(l.kind == TEXT ? l.parts : List.of(l));
                        parts.addAll(r.kind == TEXT ? r.parts : List.of(r));
                        yield new F(null, TEXT, parts, null);
                    }
                    needNumber(l, b);
                    needNumber(r, b);
                    yield switch (op) {
                        case "&&", "||" -> new F("(" + boolCode(l, b) + " " + op + " " + boolCode(r, b) + ")", BOOL, null, null);
                        case "+", "-", "*", "/", "%" -> new F("(" + numCode(l) + " " + op + " " + numCode(r) + ")", NUM, null, null);
                        case "==", "!=", "<", "<=", ">", ">=" -> new F("(" + numCode(l) + " " + op + " " + numCode(r) + ")", BOOL, null, null);
                        default -> throw new FlowException(b, "unknown operator " + op);
                    };
                }
            };
        }

        String numCode(F f) {
            return f.kind == BOOL ? "b2f(" + f.code + ")" : f.code;
        }

        String fastNum(Expr e, Block b) {
            F f = fast(e, b);
            needNumber(f, b);
            return numCode(f);
        }

        String boolCode(F f, Block b) {
            needNumber(f, b);
            return f.kind == BOOL ? f.code : "(" + f.code + " != 0.0)";
        }

        void needNumber(F f, Block b) {
            if (f.kind != TEXT) return;
            String sample = f.parts.isEmpty() || f.parts.get(0).text == null ? "text" : "\"" + f.parts.get(0).text + "\"";
            throw new FlowException(b, "fast values only hold numbers; " + sample
                    + " can only be printed. Choose Values: Safe to use text in variables, arguments, conditions or comparisons");
        }
    }

    // ================================================================ names

    private LinkedHashMap<String, BlockDef> objectMembers(FlowCanvas c, String anonPrefix, Set<String> named, Object unused) {
        LinkedHashMap<String, BlockDef> r = new LinkedHashMap<>();
        for (Block b : c.getBlocks()) {
            if (b.def == null) continue;
            if (b.objectName().isEmpty()) r.put(anonPrefix + b.id, b.def);
            else if (named.add(b.objectName())) r.put("obj_" + b.objectName(), b.def);
        }
        return r;
    }

    private void collectNames(List<Block> blocks, BlockDef owner) {
        for (Block b : blocks) {
            Interpreter.Compiled c = b.compiled();
            boolean assigns = switch (b.type) {
                case SET, INPUT, DIGITAL_READ, ANALOG_READ, MILLIS, UART_READ, SPI_TRANSFER, I2C_READ, SENSOR -> true;
                default -> false;
            };
            if (assigns && c.var() != null) addName(c.var(), owner, b);
            if (c.expr() != null) collectExpr(c.expr(), owner, b);
            if (c.args() != null) for (Expr a : c.args()) collectExpr(a, owner, b);
            for (Interpreter.Assign a : new Interpreter.Assign[]{c.init(), c.step()}) {
                if (a == null) continue;
                addName(a.var(), owner, b);
                collectExpr(a.expr(), owner, b);
            }
        }
    }

    private void collectExpr(Expr e, BlockDef owner, Block b) {
        switch (e) {
            case Expr.Var v -> {
                if (!BoardSim.isConstant(v.name())) addName(v.name(), owner, b);
            }
            case Expr.Unary u -> collectExpr(u.operand(), owner, b);
            case Expr.Binary x -> {
                collectExpr(x.left(), owner, b);
                collectExpr(x.right(), owner, b);
            }
            case Expr.Num n -> { }
            case Expr.Str s -> { }
        }
    }

    private void addName(String name, BlockDef owner, Block b) {
        if (name.startsWith("this.")) {
            if (owner == null) throw new FlowException(b, "'this' can only be used inside a class method");
            fieldsOf.get(owner).add(name.substring(5));
        } else {
            globals.add(name);
        }
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) sb.append(ch < 128 && Character.isLetterOrDigit(ch) || ch == '_' ? ch : '_');
        return sb.isEmpty() ? "x" : sb.toString();
    }

    private static String hex(long bits) {
        return "0x" + Long.toHexString(bits);
    }

    /**
     * A float in the low-level language: its exact bits (no decimal rounding on the way). On an AVR chip floats are
     * IEEE singles, so the literal is the double rounded to the nearest single, as avr-gcc rounds its constants.
     */
    private String floatLit(double d) {
        if (chip != null) return "fbits(0x" + Integer.toHexString(Float.floatToRawIntBits((float) d)) + ")";
        return "fbits(" + hex(Double.doubleToRawLongBits(d)) + ")";
    }

    private static String string(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String describe(Block b) {
        if (b.def != null) {
            String obj = b.objectName().isEmpty() ? b.def.name : b.objectName() + " : " + b.def.name;
            return "[" + obj + "] ." + (b.methodName() == null ? "?" : b.methodName()) + "(" + b.getCode() + ")";
        }
        return ("[" + b.type.title + "] " + b.getCode()).strip();
    }

    private static String comment(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
