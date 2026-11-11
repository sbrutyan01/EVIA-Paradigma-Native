package evia.paradigma;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The native back end's own small language, compiled straight to x86-64 by {@link NativeAsm}. The runtime
 * (resources/evia/paradigma/native/*.er) is written in it, and {@link NativeCompiler} lowers block programs to it;
 * nobody else sees it. Two types: int (64-bit) and float (a double); every value is one 64-bit word.
 *
 * <pre>
 * const NAME = 42;                 var NAME: int|float;  (a global)     buffer NAME[SIZE];  (zeroed bytes)
 * string NAME = "text";            (a string object: [length][bytes]; NAME is its address)
 * fn name(a, b: float): float { var x = 0; x = x + 1; if (c) { } else { } while (c) { break; continue; }
 *                               label L1: goto L1; return x; }
 * operators: || &amp;&amp; | ^ &amp; == != &lt; &lt;= &gt; &gt;= &lt;&lt; &gt;&gt; &gt;&gt;&gt; + - * / % ! ~ (unary -)
 * builtins:  load8 load64 loadf store8 store64 storef i2f f2i bits fbits syscall
 * </pre>
 * Calls evaluate and push their arguments left to right (so the first error in them is the one reported, as the
 * interpreter does); the callee finds them above its frame, the last one nearest. Results come back in rax.
 */
final class NativeLang {

    enum Type { INT, FLOAT, VOID }

    static final class CompileError extends RuntimeException {
        CompileError(String file, int line, String msg) {
            super(file + ":" + line + ": " + msg);
        }
    }

    // ================================================================ lexer

    private record Tok(String kind, String text, int line) {}   // kind: id, int, float, str, char, op, eof

    private static List<Tok> lex(String src, String file) {
        List<Tok> out = new ArrayList<>();
        int i = 0, line = 1, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') { line++; i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                if (end < 0) throw new CompileError(file, line, "a comment that never ends");
                for (int k = i; k < end; k++) if (src.charAt(k) == '\n') line++;
                i = end + 2;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int s = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                out.add(new Tok("id", src.substring(s, i), line));
                continue;
            }
            if (Character.isDigit(c)) {
                int s = i;
                if (c == '0' && i + 1 < n && (src.charAt(i + 1) == 'x' || src.charAt(i + 1) == 'X')) {
                    i += 2;
                    while (i < n && (Character.digit(src.charAt(i), 16) >= 0 || src.charAt(i) == '_')) i++;
                    out.add(new Tok("int", src.substring(s, i).replace("_", ""), line));
                    continue;
                }
                boolean fl = false;
                while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                if (i < n && src.charAt(i) == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1))) {
                    fl = true;
                    i++;
                    while (i < n && Character.isDigit(src.charAt(i))) i++;
                }
                if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                    fl = true;
                    i++;
                    if (src.charAt(i) == '+' || src.charAt(i) == '-') i++;
                    while (i < n && Character.isDigit(src.charAt(i))) i++;
                }
                out.add(new Tok(fl ? "float" : "int", src.substring(s, i).replace("_", ""), line));
                continue;
            }
            if (c == '"' || c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && src.charAt(i) != c) {
                    char ch = src.charAt(i++);
                    if (ch == '\\') {
                        char e = src.charAt(i++);
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case '0' -> sb.append('\0');
                            case 'u' -> {
                                sb.append((char) Integer.parseInt(src.substring(i, i + 4), 16));
                                i += 4;
                            }
                            default -> sb.append(e);
                        }
                    } else {
                        sb.append(ch);
                    }
                }
                i++;
                out.add(new Tok(c == '"' ? "str" : "char", sb.toString(), line));
                continue;
            }
            String[] ops = {">>>", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||"};
            String op = null;
            for (String o : ops) if (src.startsWith(o, i)) { op = o; break; }
            if (op == null) op = String.valueOf(c);
            if ("+-*/%<>=!&|^~(){}[],;:".indexOf(op.charAt(0)) < 0) throw new CompileError(file, line, "unexpected '" + c + "'");
            out.add(new Tok("op", op, line));
            i += op.length();
        }
        out.add(new Tok("eof", "", line));
        return out;
    }

    // ================================================================ syntax tree

    sealed interface Ex permits Num, Name, Call, Bin, Un {}
    record Num(long bits, Type type) implements Ex {}
    record Name(String name, int line) implements Ex {}
    record Call(String name, List<Ex> args, int line) implements Ex {}
    record Bin(String op, Ex a, Ex b, int line) implements Ex {}
    record Un(String op, Ex a, int line) implements Ex {}

    sealed interface St permits Var, Assign, If, While, Ret, Do, Brk, Cont, Lbl, Goto {}
    record Var(String name, Type type, Ex init, int line) implements St {}
    record Assign(String name, Ex value, int line) implements St {}
    record If(Ex cond, List<St> then, List<St> other) implements St {}
    record While(Ex cond, List<St> body) implements St {}
    record Ret(Ex value, int line) implements St {}
    record Do(Ex e) implements St {}
    record Brk(int line) implements St {}
    record Cont(int line) implements St {}
    record Lbl(String name) implements St {}
    record Goto(String name, int line) implements St {}

    record Fn(String name, List<String> params, List<Type> types, Type ret, List<St> body, String file, int line) {}

    // ================================================================ the program

    private final Map<String, Long> consts = new HashMap<>();
    private final Map<String, Type> constTypes = new HashMap<>();
    private final Map<String, Type> globals = new HashMap<>();      // 8-byte globals
    private final Set<String> addresses = new HashSet<>();          // buffers and strings: the name is an address
    private final Map<String, Fn> fns = new LinkedHashMap<>();
    private final Map<String, String> strings = new HashMap<>();    // text -> label, for string literals in code
    private final NativeAsm asm = new NativeAsm();
    /** For other back ends (AvrGen): buffer sizes and the text of named strings, as parsed. */
    final Map<String, Long> bufferSize = new LinkedHashMap<>();
    final Map<String, String> stringText = new LinkedHashMap<>();
    /** table NAME = 1, 2, 3; : bytes (in flash on AVR); NAME is their address. */
    final Map<String, byte[]> tables = new LinkedHashMap<>();

    Map<String, Fn> fns() { return fns; }
    Map<String, Long> consts() { return consts; }
    Map<String, Type> constTypes() { return constTypes; }
    Map<String, Type> globals() { return globals; }

    /** Parses one source file into the program (several files make one program). */
    void add(String src, String file) {
        new Parser(lex(src, file), file).program();
    }

    /** Compiles every function; entry is the function the executable starts in. */
    NativeAsm compile(String entry) {
        if (!fns.containsKey(entry)) throw new IllegalStateException("no function " + entry);
        asm.label("_start");
        asm.call("fn_" + entry);
        asm.movImm(NativeAsm.RDI, 0);
        asm.movImm(NativeAsm.RAX, 60);   // exit(0)
        asm.syscall();
        for (Fn f : fns.values()) new Gen(f).function();
        return asm;
    }

    /** A static string object in the data: [length: 8 bytes][UTF-8 bytes], 8-byte aligned. */
    String stringData(String text) {
        String l = strings.get(text);
        if (l != null) return l;
        l = "str_" + strings.size();
        strings.put(text, l);
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        asm.dataAlign(8);
        asm.dataLabel(l);
        asm.data64(b.length);
        asm.dataBytes(b);
        return l;
    }

    // ================================================================ parser

    private final class Parser {
        final List<Tok> t;
        final String file;
        int p;

        Parser(List<Tok> t, String file) {
            this.t = t;
            this.file = file;
        }

        Tok peek() { return t.get(p); }
        Tok next() { return t.get(p++); }
        boolean is(String s) { return peek().text().equals(s) && !peek().kind().equals("str"); }
        CompileError err(String m) { return new CompileError(file, peek().line(), m + " (at '" + peek().text() + "')"); }

        void expect(String s) {
            if (!is(s)) throw err("expected '" + s + "'");
            p++;
        }

        String ident() {
            if (!peek().kind().equals("id")) throw err("expected a name");
            return next().text();
        }

        Type type() {
            String n = ident();
            return switch (n) {
                case "int" -> Type.INT;
                case "float" -> Type.FLOAT;
                default -> throw err("unknown type " + n);
            };
        }

        void program() {
            while (!peek().kind().equals("eof")) {
                String kw = ident();
                switch (kw) {
                    case "const" -> {
                        String name = ident();
                        expect("=");
                        boolean neg = is("-");
                        if (neg) p++;
                        Tok v = next();
                        long bits;
                        Type ty;
                        if (v.kind().equals("float")) {
                            double d = Double.parseDouble(v.text());
                            bits = Double.doubleToRawLongBits(neg ? -d : d);
                            ty = Type.FLOAT;
                        } else {
                            long x = parseInt(v.text());
                            bits = neg ? -x : x;
                            ty = Type.INT;
                        }
                        consts.put(name, bits);
                        constTypes.put(name, ty);
                        expect(";");
                    }
                    case "var" -> {
                        String name = ident();
                        expect(":");
                        Type ty = type();
                        expect(";");
                        globals.put(name, ty);
                        asm.bss("g_" + name, 8);
                    }
                    case "buffer" -> {
                        String name = ident();
                        expect("[");
                        Tok sz = next();
                        if (sz.kind().equals("id") && !consts.containsKey(sz.text())) throw err("unknown constant " + sz.text());
                        long size = sz.kind().equals("id") ? consts.get(sz.text()) : parseInt(sz.text());
                        expect("]");
                        expect(";");
                        addresses.add(name);
                        bufferSize.put(name, size);
                        asm.bss("g_" + name, size);
                    }
                    case "string" -> {
                        String name = ident();
                        expect("=");
                        Tok s = next();
                        if (!s.kind().equals("str")) throw err("expected a string");
                        expect(";");
                        addresses.add(name);
                        stringText.put(name, s.text());
                        String l = stringData(s.text());
                        consts.put("@" + name, 0L);
                        strings.put("\u0000name:" + name, l);
                    }
                    case "table" -> {
                        String name = ident();
                        expect("=");
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                        while (!is(";")) {
                            bytes.write((int) parseInt(next().text()));
                            if (is(",")) p++;
                        }
                        expect(";");
                        addresses.add(name);
                        tables.put(name, bytes.toByteArray());
                        asm.dataLabel("g_" + name);
                        asm.dataBytes(bytes.toByteArray());
                    }
                    case "fn" -> function();
                    default -> throw err("expected const, var, buffer, string, table or fn");
                }
            }
        }

        void function() {
            int line = peek().line();
            String name = ident();
            expect("(");
            List<String> ps = new ArrayList<>();
            List<Type> ts = new ArrayList<>();
            while (!is(")")) {
                ps.add(ident());
                if (is(":")) {
                    p++;
                    ts.add(type());
                } else {
                    ts.add(Type.INT);
                }
                if (is(",")) p++;
            }
            expect(")");
            Type ret = Type.VOID;
            if (is(":")) {
                p++;
                ret = type();
            }
            List<St> body = block();
            if (fns.put(name, new Fn(name, ps, ts, ret, body, file, line)) != null) {
                throw new CompileError(file, line, "function " + name + " defined twice");
            }
        }

        List<St> block() {
            expect("{");
            List<St> out = new ArrayList<>();
            while (!is("}")) out.add(stmt());
            expect("}");
            return out;
        }

        St stmt() {
            int line = peek().line();
            if (peek().kind().equals("id")) {
                switch (peek().text()) {
                    case "var" -> {
                        p++;
                        String n = ident();
                        Type ty = null;
                        if (is(":")) {
                            p++;
                            ty = type();
                        }
                        Ex init = null;
                        if (is("=")) {
                            p++;
                            init = expr();
                        }
                        expect(";");
                        return new Var(n, ty, init, line);
                    }
                    case "if" -> {
                        p++;
                        expect("(");
                        Ex c = expr();
                        expect(")");
                        List<St> th = block();
                        List<St> el = List.of();
                        if (is("else")) {
                            p++;
                            el = is("if") ? List.of(stmt()) : block();
                        }
                        return new If(c, th, el);
                    }
                    case "while" -> {
                        p++;
                        expect("(");
                        Ex c = expr();
                        expect(")");
                        return new While(c, block());
                    }
                    case "return" -> {
                        p++;
                        Ex v = is(";") ? null : expr();
                        expect(";");
                        return new Ret(v, line);
                    }
                    case "break" -> {
                        p++;
                        expect(";");
                        return new Brk(line);
                    }
                    case "continue" -> {
                        p++;
                        expect(";");
                        return new Cont(line);
                    }
                    case "label" -> {
                        p++;
                        String n = ident();
                        expect(":");
                        return new Lbl(n);
                    }
                    case "goto" -> {
                        p++;
                        String n = ident();
                        expect(";");
                        return new Goto(n, line);
                    }
                    default -> {
                        if (t.get(p + 1).text().equals("=") && !t.get(p + 2).text().equals("=")) {
                            String n = ident();
                            p++;
                            Ex v = expr();
                            expect(";");
                            return new Assign(n, v, line);
                        }
                    }
                }
            }
            Ex e = expr();
            expect(";");
            return new Do(e);
        }

        static final String[][] LEVELS = {{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", "<=", ">", ">="},
                {"<<", ">>", ">>>"}, {"+", "-"}, {"*", "/", "%"}};

        Ex expr() {
            return level(0);
        }

        Ex level(int k) {
            if (k == LEVELS.length) return unary();
            Ex a = level(k + 1);
            while (peek().kind().equals("op") && Arrays.asList(LEVELS[k]).contains(peek().text())) {
                Tok op = next();
                a = new Bin(op.text(), a, level(k + 1), op.line());
            }
            return a;
        }

        Ex unary() {
            if (peek().kind().equals("op") && (is("-") || is("!") || is("~"))) {
                Tok op = next();
                Ex a = unary();
                if (op.text().equals("-") && a instanceof Num nm) {
                    return nm.type() == Type.FLOAT ? new Num(nm.bits() ^ Long.MIN_VALUE, Type.FLOAT) : new Num(-nm.bits(), Type.INT);
                }
                return new Un(op.text(), a, op.line());
            }
            return primary();
        }

        Ex primary() {
            Tok tk = next();
            switch (tk.kind()) {
                case "int" -> { return new Num(parseInt(tk.text()), Type.INT); }
                case "float" -> { return new Num(Double.doubleToRawLongBits(Double.parseDouble(tk.text())), Type.FLOAT); }
                case "char" -> { return new Num(tk.text().charAt(0), Type.INT); }
                case "str" -> { return new Call("@str", List.of(new Name(tk.text(), tk.line())), tk.line()); }
                case "id" -> {
                    if (is("(")) {
                        p++;
                        List<Ex> args = new ArrayList<>();
                        while (!is(")")) {
                            args.add(expr());
                            if (is(",")) p++;
                        }
                        expect(")");
                        return new Call(tk.text(), args, tk.line());
                    }
                    return new Name(tk.text(), tk.line());
                }
                default -> {
                    if (tk.text().equals("(")) {
                        Ex e = expr();
                        expect(")");
                        return e;
                    }
                    p--;
                    throw err("expected an expression");
                }
            }
        }
    }

    private static long parseInt(String s) {
        return s.startsWith("0x") || s.startsWith("0X") ? Long.parseUnsignedLong(s.substring(2), 16) : Long.parseLong(s);
    }

    // ================================================================ code generation

    private final class Gen {
        final Fn f;
        final Map<String, Integer> slot = new HashMap<>();      // locals: rbp - 8*(i+1); params: rbp + 16 + 8*i
        final Map<String, Type> types = new HashMap<>();
        final Map<String, String> labels = new HashMap<>();
        final Deque<String[]> loops = new ArrayDeque<>();        // {continue, break}
        int nlocals;
        String retLabel;

        Gen(Fn f) {
            this.f = f;
        }

        CompileError err(int line, String m) {
            return new CompileError(f.file(), line, "in " + f.name() + ": " + m);
        }

        void function() {
            for (int i = 0; i < f.params().size(); i++) {
                slot.put(f.params().get(i), -(i + 1));      // negative: a parameter
                types.put(f.params().get(i), f.types().get(i));
            }
            collect(f.body());
            asm.label("fn_" + f.name());
            asm.push(NativeAsm.RBP);
            asm.mov(NativeAsm.RBP, NativeAsm.RSP);
            if (nlocals > 0) asm.subImm(NativeAsm.RSP, 8 * nlocals);
            retLabel = asm.newLabel("ret");
            stmts(f.body());
            asm.movImm(NativeAsm.RAX, 0);
            asm.label(retLabel);
            asm.mov(NativeAsm.RSP, NativeAsm.RBP);
            asm.pop(NativeAsm.RBP);
            asm.ret();
        }

        /** Every local and label of the function, before any code. */
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
                    case Lbl l -> labels.put(l.name(), asm.newLabel("L_" + l.name() + "_"));
                    default -> { }
                }
            }
        }

        int disp(String name) {
            int s = slot.get(name);
            return s >= 0 ? -8 * (s + 1) : 16 + 8 * (f.params().size() - 1 - (-s - 1));
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
                        else if (ty != it) throw err(v.line(), "var " + v.name() + " is " + ty + " but its value is " + it);
                        asm.store(NativeAsm.RBP, disp(v.name()), NativeAsm.RAX);
                    } else if (ty == null) {
                        throw err(v.line(), "var " + v.name() + " needs a type or a value");
                    } else {
                        asm.movImm(NativeAsm.RAX, 0);
                        asm.store(NativeAsm.RBP, disp(v.name()), NativeAsm.RAX);
                    }
                    Type before = types.put(v.name(), ty);
                    if (before != null && before != ty) throw err(v.line(), "var " + v.name() + " changes its type");
                }
                case Assign a -> {
                    Type vt = expr(a.value());
                    if (slot.containsKey(a.name())) {
                        Type ty = types.get(a.name());
                        if (ty == null) throw err(a.line(), a.name() + " is used before its var");
                        if (ty != vt) throw err(a.line(), a.name() + " is " + ty + ", not " + vt);
                        asm.store(NativeAsm.RBP, disp(a.name()), NativeAsm.RAX);
                    } else if (globals.containsKey(a.name())) {
                        if (globals.get(a.name()) != vt) throw err(a.line(), a.name() + " is " + globals.get(a.name()) + ", not " + vt);
                        asm.leaLabel(NativeAsm.RCX, "g_" + a.name());
                        asm.store(NativeAsm.RCX, 0, NativeAsm.RAX);
                    } else {
                        throw err(a.line(), "unknown variable " + a.name());
                    }
                }
                case If i -> {
                    String els = asm.newLabel("else"), end = asm.newLabel("endif");
                    cond(i.cond(), els);
                    stmts(i.then());
                    if (!i.other().isEmpty()) asm.jmp(end);
                    asm.label(els);
                    stmts(i.other());
                    asm.label(end);
                }
                case While w -> {
                    String top = asm.newLabel("while"), end = asm.newLabel("wend");
                    asm.label(top);
                    cond(w.cond(), end);
                    loops.push(new String[]{top, end});
                    stmts(w.body());
                    loops.pop();
                    asm.jmp(top);
                    asm.label(end);
                }
                case Ret r -> {
                    if (r.value() != null) {
                        Type ty = expr(r.value());
                        if (ty != f.ret()) throw err(r.line(), "returns " + ty + " but the function returns " + f.ret());
                    } else if (f.ret() != Type.VOID) {
                        throw err(r.line(), "return needs a value");
                    }
                    asm.jmp(retLabel);
                }
                case Do d -> expr(d.e());
                case Brk b -> {
                    if (loops.isEmpty()) throw err(b.line(), "break outside a loop");
                    asm.jmp(loops.peek()[1]);
                }
                case Cont c -> {
                    if (loops.isEmpty()) throw err(c.line(), "continue outside a loop");
                    asm.jmp(loops.peek()[0]);
                }
                case Lbl l -> asm.label(labels.get(l.name()));
                case Goto g -> {
                    String l = labels.get(g.name());
                    if (l == null) throw err(g.line(), "no label " + g.name());
                    asm.jmp(l);
                }
            }
        }

        /** Jumps to {@code ifFalse} when the int condition is 0. */
        void cond(Ex c, String ifFalse) {
            Type ty = expr(c);
            if (ty != Type.INT) throw err(lineOf(c), "a condition must be int");
            asm.test(NativeAsm.RAX, NativeAsm.RAX);
            asm.jcc(NativeAsm.E, ifFalse);
        }

        int lineOf(Ex e) {
            return switch (e) {
                case Num n -> 0;
                case Name n -> n.line();
                case Call c -> c.line();
                case Bin b -> b.line();
                case Un u -> u.line();
            };
        }

        /** The value in rax; returns its type. */
        Type expr(Ex e) {
            return switch (e) {
                case Num n -> {
                    asm.movImm(NativeAsm.RAX, n.bits());
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
                Type ty = types.get(id);
                if (ty == null) throw err(n.line(), id + " is used before its var");
                asm.load(NativeAsm.RAX, NativeAsm.RBP, disp(id));
                return ty;
            }
            if (consts.containsKey(id)) {
                asm.movImm(NativeAsm.RAX, consts.get(id));
                return constTypes.get(id);
            }
            if (globals.containsKey(id)) {
                asm.leaLabel(NativeAsm.RAX, "g_" + id);
                asm.load(NativeAsm.RAX, NativeAsm.RAX, 0);
                return globals.get(id);
            }
            if (addresses.contains(id)) {
                String l = strings.get("\u0000name:" + id);
                asm.leaLabel(NativeAsm.RAX, l != null ? l : "g_" + id);
                return Type.INT;
            }
            throw err(n.line(), "unknown name " + id);
        }

        Type unary(Un u) {
            Type ty = expr(u.a());
            switch (u.op()) {
                case "-" -> {
                    if (ty == Type.FLOAT) {
                        asm.movImm(NativeAsm.RCX, Long.MIN_VALUE);
                        asm.xor(NativeAsm.RAX, NativeAsm.RCX);
                    } else {
                        asm.neg(NativeAsm.RAX);
                    }
                }
                case "!" -> {
                    if (ty != Type.INT) throw err(u.line(), "! needs an int");
                    asm.test(NativeAsm.RAX, NativeAsm.RAX);
                    asm.setcc(NativeAsm.E);
                }
                default -> {
                    if (ty != Type.INT) throw err(u.line(), "~ needs an int");
                    asm.not(NativeAsm.RAX);
                }
            }
            return ty;
        }

        Type binary(Bin b) {
            if (b.op().equals("&&") || b.op().equals("||")) {
                String done = asm.newLabel("sc");
                if (expr(b.a()) != Type.INT) throw err(b.line(), b.op() + " needs ints");
                asm.test(NativeAsm.RAX, NativeAsm.RAX);
                asm.setcc(NativeAsm.NE);
                asm.test(NativeAsm.RAX, NativeAsm.RAX);
                asm.jcc(b.op().equals("&&") ? NativeAsm.E : NativeAsm.NE, done);
                if (expr(b.b()) != Type.INT) throw err(b.line(), b.op() + " needs ints");
                asm.test(NativeAsm.RAX, NativeAsm.RAX);
                asm.setcc(NativeAsm.NE);
                asm.label(done);
                return Type.INT;
            }
            Type ta = expr(b.a());
            asm.push(NativeAsm.RAX);
            Type tb = expr(b.b());
            asm.mov(NativeAsm.RCX, NativeAsm.RAX);
            asm.pop(NativeAsm.RAX);
            if (ta != tb) throw err(b.line(), "'" + b.op() + "' between " + ta + " and " + tb);
            if (ta == Type.INT) return intOp(b);
            return floatOp(b);
        }

        Type intOp(Bin b) {
            switch (b.op()) {
                case "+" -> asm.add(NativeAsm.RAX, NativeAsm.RCX);
                case "-" -> asm.sub(NativeAsm.RAX, NativeAsm.RCX);
                case "*" -> asm.imul(NativeAsm.RAX, NativeAsm.RCX);
                case "/" -> {
                    asm.cqo();
                    asm.idiv(NativeAsm.RCX);
                }
                case "%" -> {
                    asm.cqo();
                    asm.idiv(NativeAsm.RCX);
                    asm.mov(NativeAsm.RAX, NativeAsm.RDX);
                }
                case "&" -> asm.and(NativeAsm.RAX, NativeAsm.RCX);
                case "|" -> asm.or(NativeAsm.RAX, NativeAsm.RCX);
                case "^" -> asm.xor(NativeAsm.RAX, NativeAsm.RCX);
                case "<<" -> asm.shlCl(NativeAsm.RAX);
                case ">>" -> asm.sarCl(NativeAsm.RAX);
                case ">>>" -> asm.shrCl(NativeAsm.RAX);
                default -> {
                    asm.cmp(NativeAsm.RAX, NativeAsm.RCX);
                    asm.setcc(switch (b.op()) {
                        case "==" -> NativeAsm.E;
                        case "!=" -> NativeAsm.NE;
                        case "<" -> NativeAsm.L;
                        case "<=" -> NativeAsm.LE;
                        case ">" -> NativeAsm.G;
                        default -> NativeAsm.GE;
                    });
                }
            }
            return Type.INT;
        }

        Type floatOp(Bin b) {
            if (b.op().equals("%")) {
                asm.fmodRaxRcx();
                return Type.FLOAT;
            }
            asm.movqXmmFromReg(0, NativeAsm.RAX);
            asm.movqXmmFromReg(1, NativeAsm.RCX);
            switch (b.op()) {
                case "+" -> asm.addsd(0, 1);
                case "-" -> asm.subsd(0, 1);
                case "*" -> asm.mulsd(0, 1);
                case "/" -> asm.divsd(0, 1);
                case "<" -> { asm.ucomisd(1, 0); asm.setcc(NativeAsm.A); return Type.INT; }
                case "<=" -> { asm.ucomisd(1, 0); asm.setcc(NativeAsm.AE); return Type.INT; }
                case ">" -> { asm.ucomisd(0, 1); asm.setcc(NativeAsm.A); return Type.INT; }
                case ">=" -> { asm.ucomisd(0, 1); asm.setcc(NativeAsm.AE); return Type.INT; }
                case "==" -> {
                    asm.ucomisd(0, 1);
                    asm.setccCl(NativeAsm.NP);
                    asm.setcc(NativeAsm.E);
                    asm.andAlCl();
                    asm.movzxRaxAl();
                    return Type.INT;
                }
                case "!=" -> {
                    asm.ucomisd(0, 1);
                    asm.setccCl(NativeAsm.P);
                    asm.setcc(NativeAsm.NE);
                    asm.orAlCl();
                    asm.movzxRaxAl();
                    return Type.INT;
                }
                default -> throw err(b.line(), "'" + b.op() + "' is not for floats");
            }
            asm.movqRegFromXmm(NativeAsm.RAX, 0);
            return Type.FLOAT;
        }

        Type call(Call c) {
            List<Ex> a = c.args();
            switch (c.name()) {
                case "@str" -> {
                    asm.leaLabel(NativeAsm.RAX, stringData(((Name) a.get(0)).name()));
                    return Type.INT;
                }
                case "load64", "load8", "loadf" -> {
                    need(c, 1);
                    want(a.get(0), Type.INT, c);
                    if (c.name().equals("load8")) asm.load8(NativeAsm.RAX, NativeAsm.RAX, 0);
                    else asm.load(NativeAsm.RAX, NativeAsm.RAX, 0);
                    return c.name().equals("loadf") ? Type.FLOAT : Type.INT;
                }
                case "store64", "store8", "storef" -> {
                    need(c, 2);
                    want(a.get(0), Type.INT, c);
                    asm.push(NativeAsm.RAX);
                    want(a.get(1), c.name().equals("storef") ? Type.FLOAT : Type.INT, c);
                    asm.mov(NativeAsm.RCX, NativeAsm.RAX);
                    asm.pop(NativeAsm.RAX);
                    if (c.name().equals("store8")) asm.store8(NativeAsm.RAX, 0, NativeAsm.RCX);
                    else asm.store(NativeAsm.RAX, 0, NativeAsm.RCX);
                    return Type.VOID;
                }
                case "i2f" -> {
                    need(c, 1);
                    want(a.get(0), Type.INT, c);
                    asm.cvtsi2sd(0, NativeAsm.RAX);
                    asm.movqRegFromXmm(NativeAsm.RAX, 0);
                    return Type.FLOAT;
                }
                case "f2i" -> {
                    need(c, 1);
                    want(a.get(0), Type.FLOAT, c);
                    asm.movqXmmFromReg(0, NativeAsm.RAX);
                    asm.cvttsd2si(NativeAsm.RAX, 0);
                    return Type.INT;
                }
                case "bits" -> {          // float -> its bits as an int
                    need(c, 1);
                    want(a.get(0), Type.FLOAT, c);
                    return Type.INT;
                }
                case "fbits" -> {         // int bits -> float
                    need(c, 1);
                    want(a.get(0), Type.INT, c);
                    return Type.FLOAT;
                }
                case "syscall" -> {
                    if (a.isEmpty() || a.size() > 7) throw err(c.line(), "syscall takes 1 to 7 arguments");
                    for (Ex x : a) {
                        want(x, Type.INT, c);
                        asm.push(NativeAsm.RAX);
                    }
                    int[] regs = {NativeAsm.RAX, NativeAsm.RDI, NativeAsm.RSI, NativeAsm.RDX, NativeAsm.R10, NativeAsm.R8, NativeAsm.R9};
                    for (int i = a.size() - 1; i >= 0; i--) asm.pop(regs[i]);
                    asm.syscall();
                    return Type.INT;
                }
                default -> {
                    Fn callee = fns.get(c.name());
                    if (callee == null) throw err(c.line(), "unknown function " + c.name());
                    if (callee.params().size() != a.size()) {
                        throw err(c.line(), c.name() + " takes " + callee.params().size() + " arguments, not " + a.size());
                    }
                    for (int i = 0; i < a.size(); i++) {
                        want(a.get(i), callee.types().get(i), c);
                        asm.push(NativeAsm.RAX);
                    }
                    asm.call("fn_" + c.name());
                    if (!a.isEmpty()) asm.addImm(NativeAsm.RSP, 8 * a.size());
                    return callee.ret();
                }
            }
        }

        void need(Call c, int n) {
            if (c.args().size() != n) throw err(c.line(), c.name() + " takes " + n + " argument(s)");
        }

        void want(Ex e, Type ty, Call c) {
            Type got = expr(e);
            if (got != ty) throw err(c.line(), c.name() + ": expected " + ty + ", got " + got);
        }
    }
}
