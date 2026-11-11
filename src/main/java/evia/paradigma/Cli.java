package evia.paradigma;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line mode, for scripts, CI and Vim users:
 * <pre>
 * java -jar evia-paradigma.jar --run program.evia            run with the interpreter (stdin/stdout)
 * java -jar evia-paradigma.jar --gen program.evia [-o x.cpp] [--lang c++20] [--flow structured] [--values safe]
 *                                                             write C, C++, Python, Java or Kotlin (stdout if no -o)
 * java -jar evia-paradigma.jar --android program.evia DIR [--build | --install]   an Android app
 * java -jar evia-paradigma.jar --example out.evia             save the built-in example program
 * </pre>
 * Output and errors are identical to the generated program, so the two can be compared.
 */
final class Cli {
    private Cli() {}

    static int run(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        try {
            switch (args[0]) {
                case "--run" -> {
                    need(args, 2);
                    return runProgram(load(args[1]), out, err);
                }
                case "--gen" -> {
                    need(args, 2);
                    Target t = Target.DEFAULT;
                    String output = null;
                    for (int i = 2; i < args.length; i++) {
                        String opt = args[i];
                        if (i + 1 >= args.length) throw new IllegalArgumentException(opt + " needs a value (see --help)");
                        String v = args[++i];
                        switch (opt) {
                            case "-o" -> output = v;
                            case "--lang" -> {
                                Target.Lang l = Target.langOf(v);
                                if (l == null) throw new IllegalArgumentException("unknown language " + v
                                        + " (c99, c11, c17, c++98, c++11, c++14, c++17, c++20, python, java, kotlin, android, arduino, avr, pic32, stm32, sv, verilog, vhdl)");
                                t = t.withLang(l);
                            }
                            case "--board" -> {
                                Board b = Board.parse(v);
                                if (!b.key().equals(v.trim().toLowerCase().replace('_', '-')) && !b.fqbn.equalsIgnoreCase(v.trim())) {
                                    throw new IllegalArgumentException("unknown board " + v + " (e.g. uno, mega, esp32, pic32mx250f128b, bluepill-f103c8, nucleo-f401re, nexys-a7-100t, arty-a7-35, basys3, olimex-gatemate, ccgm1a1-evb)");
                                }
                                Settings.useBoard(b);
                            }
                            case "--net" -> Settings.usePic32Net(switch (v.toLowerCase()) {
                                case "uart", "at", "wifi" -> false;
                                case "ethernet", "eth", "harmony" -> true;
                                default -> throw new IllegalArgumentException("--net is uart (a WiFi module on UART2) or ethernet (Harmony's TCP/IP)");
                            });
                            case "--flow" -> t = t.withFlow(switch (v.toLowerCase()) {
                                case "structured" -> Target.Flow.STRUCTURED;
                                case "goto" -> Target.Flow.GOTO;
                                default -> throw new IllegalArgumentException("--flow is structured or goto");
                            });
                            case "--values" -> t = t.withValues(switch (v.toLowerCase()) {
                                case "safe" -> Target.Values.SAFE;
                                case "fast" -> Target.Values.FAST;
                                default -> throw new IllegalArgumentException("--values is safe or fast");
                            });
                            default -> throw new IllegalArgumentException("unknown option " + opt + " (see --help)");
                        }
                    }
                    Library program = load(args[1]);
                    String code = CodeGenerator.generate(program, t);
                    if (t.lang().fpga() && output != null) {
                        // the pin constraints go next to the HDL file: x.sv -> x.xdc (Artix-7) or x.ccf (GateMate)
                        Board b = Settings.board(t.lang());
                        HdlGenerator.Result r = HdlGenerator.generate(program, t.lang().hdl(), b.fpga(), b.display, "evia_top");
                        String base = output.contains(".") ? output.substring(0, output.lastIndexOf('.')) : output;
                        Files.writeString(Path.of(base + Fpga.constraintExt(b.fpga())), r.constraints(), StandardCharsets.UTF_8);
                    }
                    if (t.lang() == Target.Lang.STM32 && output != null) {
                        // the linker script goes next to the C file
                        Path ld = Path.of(output).toAbsolutePath().resolveSibling("stm32.ld");
                        Files.writeString(ld, Stm32Runtime.linkerScript(Settings.board(Target.Lang.STM32).stm32), StandardCharsets.UTF_8);
                    }
                    if (output != null) Files.writeString(Path.of(output), code, StandardCharsets.UTF_8);
                    else out.print(code);
                    return 0;
                }
                case "--example" -> {
                    need(args, 2);
                    Library lib = newLibrary();
                    ProgramIO.loadExample(lib);
                    ProgramIO.save(lib, Path.of(args[1]));
                    return 0;
                }
                case "--linux" -> {
                    // write a Linux image workspace: evia-linux.json and build.sh (then: DIR/build.sh all)
                    need(args, 2);
                    Path dir = Path.of(args[1]).toAbsolutePath();
                    LinuxImages.Sys sys = LinuxImages.Sys.BUILDROOT;
                    LinuxImages.Brd brd = LinuxImages.Brd.RASPBERRY_PI_5;
                    String host = null, pkgs = "";
                    Boolean ssh = null, python = null, web = null, full = null;
                    char[] pw = null;
                    for (int i = 2; i < args.length; i++) {
                        String opt = args[i];
                        switch (opt) {
                            case "--no-ssh" -> ssh = false;
                            case "--python" -> python = true;
                            case "--web" -> web = true;
                            case "--no-web" -> web = false;
                            case "--minimal" -> full = false;
                            default -> {
                                if (i + 1 >= args.length) throw new IllegalArgumentException(opt + " needs a value (see --help)");
                                String v = args[++i];
                                switch (opt) {
                                    case "--system" -> sys = LinuxImages.Sys.of(v);
                                    case "--board" -> brd = LinuxImages.Brd.of(v);
                                    case "--hostname" -> host = v;
                                    case "--packages" -> pkgs = v;
                                    case "--password-file" -> pw = Files.readString(Path.of(v), StandardCharsets.UTF_8).strip().toCharArray();
                                    default -> throw new IllegalArgumentException("unknown option " + opt + " (see --help)");
                                }
                            }
                        }
                    }
                    LinuxImages.Config d = LinuxImages.Config.defaults(sys, brd);
                    LinuxImages.Config c = new LinuxImages.Config(sys, brd, host != null ? host : d.hostname(),
                            ssh != null ? ssh : d.ssh(), python != null ? python : d.python(), web != null ? web : d.web(),
                            full != null ? full : d.fullImage(), pkgs);
                    LinuxImages.write(dir, c, pw);
                    out.println(sys.display + " for the " + brd.display + " in " + dir);
                    for (LinuxImages.Check k : LinuxImages.checkHost(sys, dir)) out.println((k.ok() ? "  ok   " : "  !!   ") + k.text());
                    out.println("Build it with:  " + dir.resolve("build.sh") + " all");
                    return 0;
                }
                case "--packages" -> {
                    // what the package server has for this computer, and what is installed
                    java.util.Map<String, String> have = Packages.installed();
                    out.println("Packages from " + Packages.repo() + " for " + Packages.platform() + ", installed in " + Packages.home());
                    for (Packages.Pkg p : Packages.fetchIndex()) {
                        String inst = p.app() ? Packages.runningVersion() + " (running)" : have.getOrDefault(p.id(), "-");
                        out.printf("  %-20s %-14s installed %-20s available %-16s %s%s%n", p.id(), p.group(), inst,
                                p.available() ? p.version() : "-", p.available() ? Packages.mb(p.size()) : "not for this computer",
                                Packages.hasUpdate(p) ? "  UPDATE" : "");
                    }
                    return 0;
                }
                case "--install", "--remove" -> {
                    need(args, 2);
                    java.util.List<Packages.Pkg> idx = args[0].equals("--install") ? Packages.fetchIndex() : java.util.List.of();
                    java.util.List<String> ids = new java.util.ArrayList<>(java.util.Arrays.asList(args).subList(1, args.length));
                    if (ids.equals(java.util.List.of("updates"))) {
                        ids.clear();
                        for (Packages.Pkg p : idx) if (Packages.hasUpdate(p)) ids.add(p.id());
                        if (ids.isEmpty()) out.println("everything installed is up to date");
                    }
                    int rc = 0;
                    for (String id : ids) {
                        if (args[0].equals("--remove")) {
                            Packages.remove(id);
                            out.println("removed " + id);
                            continue;
                        }
                        Packages.Pkg pkg = idx.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
                        if (pkg == null || !pkg.available()) {
                            err.println("evia-paradigma: no package " + id + " for " + Packages.platform() + " (see --packages)");
                            rc = 2;
                            continue;
                        }
                        String[] lastLine = {""};
                        try {
                            Path where = Packages.install(pkg, new Packages.Progress() {
                                @Override public void report(String text, long done, long total) {
                                    String line = total > 0 ? text.replaceAll("\\.\\.\\. .*", "... " + done * 100 / total + " %") : text;
                                    if (!line.equals(lastLine[0])) err.println(line);
                                    lastLine[0] = line;
                                }

                                @Override public boolean cancelled() {
                                    return false;
                                }
                            });
                            out.println("installed " + pkg.id() + " " + pkg.version() + " in " + where);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return 1;
                        }
                    }
                    return rc;
                }
                case "--android" -> {
                    // write the Android Studio project of a program, and optionally build and install the app
                    need(args, 3);
                    Library program = load(args[1]);
                    Path dir = Path.of(args[2]).toAbsolutePath();
                    String base = Path.of(args[1]).getFileName().toString().replaceFirst("\\.evia$", "");
                    String pkg = AndroidBuild.packageFor(base), name = base;
                    Target t = Target.DEFAULT.withLang(Target.Lang.ANDROID);
                    boolean build = false, install = false;
                    String serial = null;
                    for (int i = 3; i < args.length; i++) {
                        String opt = args[i];
                        switch (opt) {
                            case "--build" -> build = true;
                            case "--install" -> build = install = true;
                            default -> {
                                if (i + 1 >= args.length) throw new IllegalArgumentException(opt + " needs a value (see --help)");
                                String v = args[++i];
                                switch (opt) {
                                    case "--package" -> pkg = v;
                                    case "--name" -> name = v;
                                    case "--device" -> serial = v;
                                    case "--flow" -> t = t.withFlow(v.equalsIgnoreCase("goto") ? Target.Flow.GOTO : Target.Flow.STRUCTURED);
                                    case "--values" -> t = t.withValues(v.equalsIgnoreCase("fast") ? Target.Values.FAST : Target.Values.SAFE);
                                    default -> throw new IllegalArgumentException("unknown option " + opt + " (see --help)");
                                }
                            }
                        }
                    }
                    Path src = AndroidBuild.writeProject(program, t, dir, pkg, name);
                    out.println("wrote " + dir + " (" + pkg + ", " + src.getFileName() + ")");
                    if (!build) return 0;
                    try {
                        Path apk = AndroidBuild.build(dir, pkg, name, out::println);
                        if (install) AndroidBuild.install(apk, pkg, serial, out::println);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return 1;
                    }
                    return 0;
                }
                case "--native" -> {
                    // a Linux x86-64 executable straight from the blocks: no C, no compiler, no linker
                    need(args, 2);
                    Target t = Target.DEFAULT;
                    String output = null;
                    boolean lowered = false;
                    for (int i = 2; i < args.length; i++) {
                        String opt = args[i];
                        if (opt.equals("--lowered")) {
                            lowered = true;
                            continue;
                        }
                        if (i + 1 >= args.length) throw new IllegalArgumentException(opt + " needs a value (see --help)");
                        String v = args[++i];
                        switch (opt) {
                            case "-o" -> output = v;
                            case "--values" -> t = t.withValues(v.equalsIgnoreCase("fast") ? Target.Values.FAST : Target.Values.SAFE);
                            default -> throw new IllegalArgumentException("unknown option " + opt + " (see --help)");
                        }
                    }
                    NativeCompiler.Result r = NativeCompiler.compile(load(args[1]), t);
                    if (lowered) {
                        out.print(r.lowered());
                        if (output == null) return 0;
                    }
                    Path exe = Path.of(output != null ? output : Path.of(args[1]).getFileName().toString().replaceFirst("\\.evia$", ""));
                    Files.write(exe, r.executable());
                    exe.toFile().setExecutable(true);
                    if (!lowered) out.println("wrote " + exe + " (" + r.executable().length + " bytes, Linux x86-64)");
                    return 0;
                }
                case "--export-app" -> {
                    // the program as an app for any x86-64 Linux: the executable, a portable .tar.gz and a .deb
                    need(args, 3);
                    Library program = load(args[1]);
                    Path dir = Path.of(args[2]).toAbsolutePath();
                    String base = Path.of(args[1]).getFileName().toString().replaceFirst("\\.evia$", "");
                    String name = base, command = null, version = "1.0", about = "A program made with EVIA Paradigma Native";
                    Target t = Target.DEFAULT;
                    for (int i = 3; i < args.length; i++) {
                        String opt = args[i];
                        if (i + 1 >= args.length) throw new IllegalArgumentException(opt + " needs a value (see --help)");
                        String v = args[++i];
                        switch (opt) {
                            case "--name" -> name = v;
                            case "--command" -> command = v;
                            case "--version" -> version = v;
                            case "--description" -> about = v;
                            case "--values" -> t = t.withValues(v.equalsIgnoreCase("fast") ? Target.Values.FAST : Target.Values.SAFE);
                            default -> throw new IllegalArgumentException("unknown option " + opt + " (see --help)");
                        }
                    }
                    if (command == null) command = AppExport.commandFor(name);
                    byte[] exe = NativeCompiler.compile(program, t).executable();
                    for (Path p : AppExport.export(exe, new AppExport.Options(name, command, version, about, true, true, true), dir)) {
                        out.println("wrote " + p);
                    }
                    return 0;
                }
                case "--android-sdk" -> {
                    // fetch the Android SDK from Google into DIR (default: Settings, or EVIA's own folder)
                    Path sdk = args.length > 1 && !args[1].startsWith("--") ? Path.of(args[1]).toAbsolutePath() : Settings.androidSdk();
                    boolean accept = java.util.Arrays.asList(args).contains("--accept-license");
                    AndroidBuild.Plan plan = AndroidBuild.plan();
                    if (!accept) {
                        for (var l : plan.licenses().entrySet()) out.println("=== " + l.getKey() + " ===\n" + l.getValue().strip() + "\n");
                        out.println("The Android SDK is Google's. To download it (" + Packages.mb(plan.bytes()) + " into " + sdk
                                + "), read the licence above and run this again with --accept-license");
                        return 2;
                    }
                    try {
                        AndroidBuild.installSdk(plan, sdk, (d, total) -> { }, () -> false, out::println);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return 1;
                    }
                    return 0;
                }
                case "--version" -> {
                    out.println(App.name() + " " + App.VERSION + " (Java " + System.getProperty("java.version") + ")");
                    if (System.getProperty("evia.handedOver") != null) {
                        out.println("Updated:   started by the installed " + System.getProperty("evia.handedOver")
                                + " from " + Packages.dir(Packages.APP));
                    }
                    // which tools Build and Upload use: bundled with the installer, set in Settings, or from PATH
                    String bundled = Bundled.describe();
                    out.println("Bundled:   " + (bundled.isEmpty() ? "none (tools come from Settings or PATH)" : bundled.replace("\n", "; ")));
                    out.println("avr-gcc:   " + Settings.avrGcc());
                    out.println("avrdude:   " + Settings.avrdude());
                    out.println("yosys:     " + Settings.fpgaTools().yosys());
                    out.println("xc32-gcc:  " + Settings.xc32());
                    out.println("arm-gcc:   " + Settings.armGcc());
                    out.println("openocd:   " + Settings.openocd());
                    java.nio.file.Path jdk = Settings.jdkHome();
                    out.println("JDK:       " + (jdk == null ? "none found (Java and Kotlin need one)" : jdk + " (Java " + Settings.jdkVersion(jdk) + ")"));
                    out.println("kotlinc:   " + Settings.kotlinc());
                    out.println("Android:   " + Settings.androidSdk());
                    return 0;
                }
                default -> {
                    out.println("""
                            EVIA Paradigma - visual block programming with C, C++, Python, Java and Kotlin generation

                            Usage:
                              java -jar evia-paradigma.jar                           start the editor
                              java -jar evia-paradigma.jar --run  program.evia       run a program
                                                          (or a project folder / .eviaproj)
                              java -jar evia-paradigma.jar --gen  program.evia [options]
                                                                                     generate C, C++, Python, Java or Kotlin
                                  -o FILE              write to FILE (default: standard output)
                                  --lang LANGUAGE      c99, c11, c17, c++98, c++11, c++14, c++17, c++20 (default),
                                                       python (Python 3.8 or newer: run it with python3 program.py),
                                                       java (JDK 17 or newer: java Program.java),
                                                       kotlin (kotlinc program.kt -include-runtime -d program.jar),
                                                       android (the Kotlin activity of an Android app; see --android),
                                                       arduino (a sketch for Arduino AVR, ESP32 and STM32 boards),
                                                       avr (bare-metal C for avr-gcc: ATmega328P, 2560, 32U4),
                                                       pic32 (bare-metal C for xc32-gcc: PIC32MX1xx/2xx, 3xx/4xx, 5xx/6xx/7xx)
                                                       stm32 (bare-metal C for arm-none-eabi-gcc: STM32F103, F401, F411, F407;
                                                       with -o FILE the linker script is written next to it as stm32.ld)
                                                       sv, verilog, vhdl (FPGA: SystemVerilog, Verilog-2001, VHDL; with -o FILE the
                                                       pin constraints are written next to it as .xdc or .ccf)
                                  --board BOARD        the board or chip the code is for (pic32 needs one of the pic32mx... chips,
                                                       e.g. pic32mx250f128b, pic32mx320f128h, pic32mx795f512l; default pic32mx250f128b;
                                                       STM32: bluepill-f103c8 (default), blackpill-f411ce, nucleo-f401re,
                                                       nucleo-f411re, disco-f407vg, disco-f429zi (also for arduino);
                                                       PIC32 boards: pic32-esk2, chipkit-pro-mx7;
                                                       FPGA: nexys-a7-100t (default), arty-a7-35, arty-a7-100, basys3,
                                                       olimex-gatemate, ccgm1a1-evb)
                                  --net WHERE          where the MQTT blocks reach the network on a PIC32: uart (default, a WiFi
                                                       module with AT firmware on UART2, every chip) or ethernet (the chip's own
                                                       port through Harmony's TCP/IP stack: the file is then an application for an
                                                       MPLAB X Harmony project, MX695/MX795 only)
                                  --flow MODE          structured (default): if/else, loops, switch
                                                       goto: every block a label, wires are jumps
                                                       (Python, Java and Kotlin have no goto: a state machine, one
                                                       branch per block)
                                  --values MODE        safe (default): numbers and text, the interpreter's checks
                                                       fast: plain double numbers, text only in Print, no checks
                              java -jar evia-paradigma.jar --android program.evia DIR [options]
                                                                                     an Android app: writes the Android Studio
                                                                                     project into DIR
                                  --package ID         the application id (default am.evia.<program name>)
                                  --name TEXT          the name under the icon (default: the program's)
                                  --flow, --values     as for --gen
                                  --build              also build DIR/build/<name>-debug.apk (Android SDK, JDK, kotlinc)
                                  --install            build, install on the connected phone with adb and start it
                                  --device SERIAL      the phone to install on, when more than one is connected
                              java -jar evia-paradigma.jar --native program.evia [-o FILE] [--values safe|fast]
                                                                                     a Linux x86-64 executable straight from
                                                                                     the blocks: no compiler or linker needed
                              java -jar evia-paradigma.jar --export-app program.evia DIR [--name N] [--command C]
                                  [--version V] [--description D] [--values safe|fast]
                                                                                     the program as an app for any x86-64
                                                                                     Linux: executable, .tar.gz and .deb
                              java -jar evia-paradigma.jar --android-sdk [DIR] [--accept-license]
                                                                                     download the Android SDK from Google
                                                                                     (shows Google's licence first)
                              java -jar evia-paradigma.jar --example out.evia        save the example
                              java -jar evia-paradigma.jar --packages                list the packages of the EVIA server
                              java -jar evia-paradigma.jar --install ID...           install or update packages (or: updates)
                              java -jar evia-paradigma.jar --remove ID               remove an installed package
                              java -jar evia-paradigma.jar --linux DIR [options]     a custom Linux image: writes DIR/build.sh
                                  --system S           buildroot (default), yocto, openwrt (Image Builder), openwrt-source
                                  --board B            rpi5 (Raspberry Pi 5, default) or bbb (BeagleBone Black)
                                  --hostname NAME      the board's host name (default evia-rpi5 / evia-bbb)
                                  --packages "A B"     more packages, by the build system's names
                                  --password-file F    the root password (kept in DIR/secrets; the image gets its hash)
                                  --python --no-ssh --web --no-web --minimal (Yocto: core-image-minimal)
                            Build generated code with Clang %d, e.g.:
                              clang++ -std=c++20 -O2 -o program program.cpp
                              clang -std=c11 -O2 -o program program.c -lm
                              (or g++ / gcc, GCC %d or newer)
                            Boards:
                              arduino-cli compile --fqbn arduino:avr:uno program      (program/program.ino)
                              avr-gcc -mmcu=atmega328p -DF_CPU=16000000UL -Os -o program.elf program.c -lm
                              xc32-gcc -mprocessor=32MX250F128B -Os -o program.elf program.c -lm   (then xc32-bin2hex program.elf)
                              arm-none-eabi-gcc -mcpu=cortex-m3 -mthumb -Os -nostartfiles --specs=nano.specs --specs=nosys.specs
                                  -T stm32.ld -o program.elf program.c -lm   (the full line is in the generated file)
                              openocd -f interface/stlink.cfg -f target/stm32f1x.cfg -c "program program.elf verify reset exit\"""".formatted(Settings.LATEST_CLANG, Settings.MIN_GCC));
                    return args[0].equals("--help") || args[0].equals("-h") ? 0 : 2;
                }
            }
        } catch (FlowException e) {
            out.flush();
            err.println(">> error: " + e.getMessage());
            return 1;
        } catch (IOException | IllegalArgumentException e) {
            err.println("evia-paradigma: " + e.getMessage());
            return 2;
        }
    }

    private static int runProgram(Library lib, PrintStream out, PrintStream err) {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            Block start = Interpreter.prepare(lib);
            new Interpreter().run(start, new Interpreter.Host() {
                @Override public void step(Block b) {}
                @Override public void print(String text) { out.println(text); }
                @Override public String input(String var) {
                    out.print("Enter a value for " + var + ": ");
                    out.flush();
                    try {
                        return in.readLine();
                    } catch (IOException e) {
                        return null;
                    }
                }
                @Override public boolean cancelled() { return false; }
                // the simulated board's pin changes go to stderr, so stdout is exactly the program's output
                @Override public void board(String event) { err.println("board " + event); }
            });
            return 0;
        } catch (FlowException e) {
            out.flush();
            err.println(">> error: " + e.getMessage());
            return 1;
        }
    }

    private static Library newLibrary() {
        Library lib = new Library();
        lib.main = new FlowCanvas(lib, null);
        return lib;
    }

    private static Library load(String file) throws IOException {
        Library lib = newLibrary();
        Path path = Path.of(file);
        // a project folder or *.eviaproj file means its main program
        if (java.nio.file.Files.isDirectory(path) || file.endsWith(Project.EXT)) {
            Project p = java.nio.file.Files.isDirectory(path) ? Project.inFolder(path) : Project.load(path);
            if (p == null) throw new IOException(file + " is not an EVIA Paradigma project (no " + Project.EXT + " file)");
            path = p.mainProgram();
        }
        ProgramIO.load(lib, path);
        return lib;
    }

    private static void need(String[] args, int n) {
        if (args.length < n) throw new IllegalArgumentException("missing file name (see --help)");
    }
}
