# Random float tests for the AVR back end (AvrFloat and out_num), against IEEE float32 done in Python.
#   random_floats.py SEED OUT_PREFIX COUNT  - writes OUT_PREFIX.er and .exp; results too small to be normal are left out (the chips flush them to zero)
import random, struct, math, sys
seed = int(sys.argv[1]); out = sys.argv[2]; N = int(sys.argv[3])
R = random.Random(seed)
def f32(x):
    if x != x: return x
    try: return struct.unpack('<f', struct.pack('<f', x))[0]
    except OverflowError: return math.copysign(math.inf, x)
def bits(x):
    if x != x: return 0x7FC00000
    return struct.unpack('<I', struct.pack('<f', x))[0]
def val(b): return struct.unpack('<f', struct.pack('<I', b))[0]
TINY = 2.0 ** -126
def ok(x): return x != x or x == 0 or abs(x) >= TINY * 2 or math.isinf(x)   # no results near the subnormal range
def rnd():
    r = R.random()
    if r < 0.08: return f32(R.choice([0.0, -0.0, 1.0, -1.0, 0.5, 10.0, 0.1, 3.0, math.inf, -math.inf, math.nan, 255.0, 1e6, 8388608.0, 16777216.0, 2147483648.0, -2147483648.0, 1e15, 0.001]))
    if r < 0.3: return f32(R.choice([1, -1]) * R.randint(0, 100000) / R.choice([1, 2, 4, 10, 100, 1000]))
    e = R.randint(-30, 30) if r < 0.8 else R.randint(-120, 120)
    return f32(R.choice([1, -1]) * R.random() * 2.0 ** e)
def lit(x): return "fbits(0x%08X)" % bits(x)
def tdiv_trunc(x):
    if x != x: return 0
    if x >= 2147483647: return 2147483647
    if x <= -2147483648: return -2147483648
    return int(x)
# out_num, as avr/base.er does it, in float32
C1E15 = f32(1e15); C0001 = f32(0.001)
def is_whole(x):
    if x >= 8388608.0: return True
    return f32(float(tdiv_trunc(x))) == x
def out_num(x):
    if x != x: return "NaN"
    s = ""
    if x < 0: s = "-"; x = -x
    if x - x != 0: return s + "Infinity"
    if is_whole(x) and x < C1E15: return s + str(int(x))
    e = 0
    if x >= 10000000.0 or x < C0001:
        while x >= 10.0: x = f32(x / 10.0); e += 1
        while x < 1.0: x = f32(x * 10.0); e -= 1
    y = f32(f32(x * 1000000.0) + 0.5)
    D = str(tdiv_trunc(y)) if y < 8388608.0 else str(int(y))
    nd = [int(c) for c in reversed(D)]
    whole = len(nd) - 6
    if e != 0 and whole >= 2:
        s += "1.0"; e += 1
    else:
        s += "0" if whole <= 0 else D[:len(D) - 6]
        s += "."
        frac = [nd[i] if i < len(nd) else 0 for i in range(6)]
        nz = [i for i in range(6) if frac[i] != 0]
        if not nz: s += "0"
        else: s += "".join(str(frac[i]) for i in range(5, min(nz) - 1, -1))
    if e != 0: s += "E" + ("-" if e < 0 else "") + str(abs(e))
    return s
lines, expect = [], []
for i in range(N):
    kind = R.random()
    if kind < 0.35:
        x = rnd()
        if not ok(x): continue
        lines.append("    out_num(%s); out_byte(10);" % lit(x)); expect.append(out_num(x) + "\n")
    elif kind < 0.8:
        x, y = rnd(), rnd(); op = R.choice("+-*/%")
        if not ok(x) or not ok(y): continue
        if op == "%":
            r = math.nan if (x != x or y != y or math.isinf(x) or y == 0) else x if math.isinf(y) else math.fmod(x, y)
        else:
            try: r = {"+": x + y, "-": x - y, "*": x * y, "/": x / y if y != 0 else (math.nan if x == 0 or x != x else math.copysign(math.inf, x) * math.copysign(1, y))}[op]
            except (OverflowError, ValueError): r = math.nan
            if op in "+-" and math.isinf(x) and math.isinf(y): r = x + y if op == "+" else x - y
        r = f32(r)
        if not ok(r): continue
        lines.append("    x = %s; y = %s; hex32(bits(x %s y));" % (lit(x), lit(y), op))
        expect.append("%08X \n" % bits(r))
    elif kind < 0.9:
        x, y = rnd(), rnd(); op = R.choice(["<", "<=", ">", ">=", "==", "!="])
        if R.random() < 0.3: y = x
        if not ok(x) or not ok(y): continue
        t = {"<": x < y, "<=": x <= y, ">": x > y, ">=": x >= y, "==": x == y, "!=": x != y}[op]
        lines.append("    x = %s; y = %s; if (x %s y) { out_byte('T'); } else { out_byte('F'); } out_byte(10);" % (lit(x), lit(y), op))
        expect.append(("T" if t else "F") + "\n")
    elif kind < 0.95:
        x = rnd()
        if not ok(x): continue
        lines.append("    x = %s; hex32(f2i(x));" % lit(x)); expect.append("%08X \n" % (tdiv_trunc(x) & 0xFFFFFFFF))
    else:
        n = R.choice([0, 1, -1, 16777217, -16777219, 2147483647, -2147483648, R.getrandbits(31) - (1 << 30), R.randint(-1000, 1000)])
        lines.append("    hex32(bits(i2f(%s)));" % ("(%d)" % n if n >= 0 else "(0 - %d)" % -n if n != -2147483648 else "(0x80000000)"))
        expect.append("%08X \n" % bits(f32(float(n))))
prog = """fn hex32(v) {
    var i = 28;
    while (i >= 0) {
        var d = (v >>> i) & 15;
        if (d < 10) { out_byte('0' + d); } else { out_byte('A' + d - 10); }
        i = i - 4;
    }
    out_byte(' ');
    out_byte(10);
}
fn main() {
    runtime_init();
    var x = 0.0;
    var y = 0.0;
""" + "\n".join(lines) + """
    halt();
}
"""
open(out + ".er", "w").write(prog)
open(out + ".exp", "w").write("".join(expect))
