# Random int tests for the AVR back end: writes OUT_PREFIX.er (a program) and OUT_PREFIX.exp (what it must print).
#   random_ints.py SEED OUT_PREFIX [STATEMENTS]  - 32-bit ints exactly as Java computes them
import random, sys
seed = int(sys.argv[1]); out = sys.argv[2]
R = random.Random(seed)
def w(x): x &= 0xFFFFFFFF; return x - (1 << 32) if x & 0x80000000 else x
def tdiv(a, b): q = abs(a) // abs(b); return w(q if (a < 0) == (b < 0) else -q)
def tmod(a, b): return w(a - tdiv(a, b) * b)
CONSTS = [0, 1, 2, -1, 7, 10, 127, 128, 254, 255, 256, 1000, -128, -255, -256, 65535, 65536, 0x7FFFFFFF, -0x80000000, 0x12345678]
env = {}
def const():
    return R.choice(CONSTS) if R.random() < 0.6 else w(R.getrandbits(R.choice([4, 8, 16, 32])))
def lit(v): return "(%d)" % v if v >= 0 else "(0 - %d)" % (-v) if v != -0x80000000 else "(0x80000000)"
def gen(d):
    r = R.random()
    if d == 0 or r < 0.25:
        if R.random() < 0.5 and env:
            n = R.choice(sorted(env)); return n, env[n]
        v = const(); return lit(v), v
    if r < 0.32:
        e, v = gen(d - 1)
        op = R.choice(["-", "!"])
        return ("(-%s)" % e, w(-v)) if op == "-" else ("(!%s)" % e, int(v == 0))
    if r < 0.38:
        e1, v1 = gen(d - 1); e2, v2 = gen(d - 1); e3, v3 = gen(d - 1)
        fn = R.choice(["f2", "f3", "f4", "f5"])
        if fn == "f2": return "f2(%s, %s)" % (e1, e2), w(v1 * 5 - v2)
        if fn == "f3": return "f3(%s, %s, %s)" % (e1, e2, e3), w(v1 * 3 + v2 - v3)
        if fn == "f4": return "f4(%s, %s, %s, %s)" % (e1, e2, e3, e1), w(((v1 - v2) ^ v3) + v1 * 7)
        return "f5(%s, %s, %s, %s, %s)" % (e1, e2, e3, e2, e1), w(v1 - v2 + v3 - v2 + v1 * 2)
    if r < 0.42:
        e, v = gen(d - 1)
        return "load8(buf + (%s & 15))" % e, ((v & 15) * 17 + 3) & 255
    op = R.choice(["+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>", "==", "!=", "<", "<=", ">", ">=", "&&", "||"])
    e1, v1 = gen(d - 1)
    if op in ("<<", ">>", ">>>"):
        k = R.randrange(32)
        if R.random() < 0.5: e2 = lit(k)
        else: e2 = "(%s & 31)" % e1; k = v1 & 31
        val = {"<<": w(v1 << k), ">>": w(v1 >> k), ">>>": w((v1 & 0xFFFFFFFF) >> k)}[op]
        return "(%s %s %s)" % (e1, op, e2), val
    e2, v2 = gen(d - 1)
    if op in ("/", "%"):
        if v2 == 0 or (v1 == -0x80000000 and v2 == -1): return gen(d)
        return "(%s %s %s)" % (e1, op, e2), tdiv(v1, v2) if op == "/" else tmod(v1, v2)
    f = {"+": lambda: w(v1 + v2), "-": lambda: w(v1 - v2), "*": lambda: w(v1 * v2), "&": lambda: w(v1 & v2),
         "|": lambda: w(v1 | v2), "^": lambda: w(v1 ^ v2), "==": lambda: int(v1 == v2), "!=": lambda: int(v1 != v2),
         "<": lambda: int(v1 < v2), "<=": lambda: int(v1 <= v2), ">": lambda: int(v1 > v2), ">=": lambda: int(v1 >= v2),
         "&&": lambda: int(v1 != 0 and v2 != 0), "||": lambda: int(v1 != 0 or v2 != 0)}
    return "(%s %s %s)" % (e1, op, e2), f[op]()
lines, expect = [], []
for name in ["a", "b", "c", "d"]:
    e, v = gen(2); lines.append("    var %s = %s;" % (name, e)); env[name] = v
e, v = gen(2); lines.append("    g = %s;" % e); env["g"] = v
for i in range(int(sys.argv[3]) if len(sys.argv) > 3 else 60):
    kind = R.random()
    if kind < 0.6:
        e, v = gen(R.randint(1, 4)); lines.append("    hex32(%s);" % e); expect.append("%08X " % (v & 0xFFFFFFFF))
    elif kind < 0.85:
        e, v = gen(R.randint(1, 3)); k = R.choice(CONSTS + [const()]); op = R.choice(["==", "!=", "<", "<=", ">", ">="])
        t = {"==": v == k, "!=": v != k, "<": v < k, "<=": v <= k, ">": v > k, ">=": v >= k}[op]
        c = "%s %s %s" % (e, op, lit(k))
        if R.random() < 0.3:
            e2, v2 = gen(1); c = "(%s) && %s" % (c, e2); t = t and v2 != 0
        lines.append("    if (%s) { out_byte('T'); } else { out_byte('F'); }" % c); expect.append("T" if t else "F")
    else:
        n = R.choice(sorted(env)); e, v = gen(R.randint(1, 3))
        if n == "g": lines.append("    g = %s;" % e)
        else: lines.append("    %s = %s;" % (n, e))
        env[n] = v
prog = """var g: int;
buffer buf[16];
fn hex32(v) {
    var i = 28;
    while (i >= 0) {
        var d = (v >>> i) & 15;
        if (d < 10) { out_byte('0' + d); } else { out_byte('A' + d - 10); }
        i = i - 4;
    }
    out_byte(' ');
}
fn f2(x, y): int { return x * 5 - y; }
fn f3(x, y, z): int { return x * 3 + y - z; }
fn f4(x, y, z, q): int { return ((x - y) ^ z) + q * 7; }
fn f5(x, y, z, q, r): int { return x - y + z - q + r * 2; }
fn main() {
    runtime_init();
    var i = 0;
    while (i < 16) { store8(buf + i, i * 17 + 3); i = i + 1; }
""" + "\n".join(lines) + """
    out_byte(10);
    halt();
}
"""
open(out + ".er", "w").write(prog)
open(out + ".exp", "w").write("".join(expect) + "\n")
