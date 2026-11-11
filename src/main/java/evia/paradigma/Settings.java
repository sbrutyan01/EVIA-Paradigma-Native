package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User settings (stored with java.util.prefs): Vim keys, external editor, compiler (Clang or GCC), boards. */
final class Settings {
    private Settings() {}

    /** Latest stable LLVM/Clang major version this release of EVIA Paradigma targets. */
    static final int LATEST_CLANG = 23;
    static final String DEFAULT_COMPILER = "clang++ -std=c++20 -O2 -Wall -Wextra -o {exe} {src}";
    static final String DEFAULT_GCC = "g++ -std=c++20 -O2 -Wall -Wextra -o {exe} {src}";
    /** GCC versions from this one on compile everything EVIA Paradigma generates (C++20 with from_chars). */
    static final int MIN_GCC = 13;

    private static final Preferences PREFS = Preferences.userNodeForPackage(Settings.class);
    private static final List<Runnable> listeners = new ArrayList<>();

    static Theme.Mode themeMode() {
        try {
            return Theme.Mode.valueOf(PREFS.get("theme", "SYSTEM"));
        } catch (IllegalArgumentException e) {
            return Theme.Mode.SYSTEM;
        }
    }

    static void setThemeMode(Theme.Mode m) {
        PREFS.put("theme", m.name());
    }

    /** Language of the guide: "en", "ru" or "hy"; by default the language of the system. */
    static String helpLanguage() {
        String saved = PREFS.get("helpLanguage", null);
        if (saved != null) return saved;
        String sys = java.util.Locale.getDefault().getLanguage();
        return sys.equals("ru") || sys.equals("hy") ? sys : "en";
    }

    static void setHelpLanguage(String lang) {
        PREFS.put("helpLanguage", lang);
    }

    static boolean vimKeys() {
        return PREFS.getBoolean("vimKeys", true);
    }

    static void setVimKeys(boolean on) {
        PREFS.putBoolean("vimKeys", on);
        fire();
    }

    static String editorCommand() {
        return PREFS.get("editor", ExternalEditor.defaultCommand());
    }

    /**
     * The compile command. Unless the user chose another one, the toolchain that
     * EVIA Paradigma installed itself (llvm-mingw on Windows) is preferred.
     */
    static String compilerCommand() {
        String saved = PREFS.get("compiler", null);
        String managed = ToolchainInstaller.managedCommand();
        if ((saved == null || saved.equals(DEFAULT_COMPILER)) && managed != null) return managed;
        return saved != null ? saved : DEFAULT_COMPILER;
    }

    static void setCompilerCommand(String command) {
        PREFS.put("compiler", command);
        fire();
    }

    /** Clang or GCC: which compiler family a compile command uses. */
    static ToolchainInstaller.Kind family(String command) {
        List<String> t = split(command);
        String exe = t.isEmpty() ? "" : t.get(0).replace('\\', '/');
        String name = exe.substring(exe.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        if (name.contains("clang")) return ToolchainInstaller.Kind.CLANG;   // "clang++" contains "g++" too
        return name.contains("g++") || name.contains("gcc") ? ToolchainInstaller.Kind.GCC : ToolchainInstaller.Kind.CLANG;
    }

    /** The same command with the other compiler: its managed copy on Windows if installed, else clang++ / g++ from PATH. */
    static String withFamily(String command, ToolchainInstaller.Kind k) {
        if (family(command) == k) return command;
        String managed = ToolchainInstaller.managedCommand(k);
        if (managed != null) return managed;
        List<String> t = split(command);
        if (t.isEmpty()) return k == ToolchainInstaller.Kind.GCC ? DEFAULT_GCC : DEFAULT_COMPILER;
        StringBuilder sb = new StringBuilder(k == ToolchainInstaller.Kind.GCC ? "g++" : "clang++");
        for (String tok : t.subList(1, t.size())) sb.append(' ').append(tok.contains(" ") ? "\"" + tok + "\"" : tok);
        return sb.toString();
    }

    // ================================================================ boards

    /** The board that Build and Upload use for Arduino and AVR code. */
    static Board board() {
        Board b = Board.parse(PREFS.get("board", "uno"));
        return b.isPic32() || b.isFpga() ? Board.UNO : b;
    }

    /** The board for a language: the Arduino/AVR choice, or the PIC32 chip for PIC32 C. */
    static Board board(Target.Lang lang) {
        if (lang == Target.Lang.PIC32) {
            if (override != null && override.isPic32()) return override;
            Board b = Board.parse(PREFS.get("board.pic32", "pic32mx250f128b"));
            return b.isPic32() ? b : Board.PIC32MX250F128B;
        }
        if (lang == Target.Lang.STM32) {
            if (override != null && override.isStm32()) return override;
            Board b = Board.parse(PREFS.get("board.stm32", "bluepill-f103c8"));
            return b.isStm32() ? b : Board.BLUEPILL_F103C8;
        }
        if (lang.fpga()) {
            if (override != null && override.isFpga()) return override;
            Board b = Board.parse(PREFS.get("board.fpga", "nexys-a7-100t"));
            return b.isFpga() ? b : Board.NEXYS_A7_100T;
        }
        if (override != null && override.supports(lang)) return override;
        return Board.forLang(board(), lang);
    }

    private static Board override;

    /** A board for this run only (the command line's --board); null goes back to the saved choice. */
    static void useBoard(Board b) {
        override = b;
    }

    static void setBoard(Board b) {
        if (b.isFpga()) {
            if (b == board(Target.Lang.VHDL)) return;
            PREFS.put("board.fpga", b.key());
        } else if (b.isPic32()) {
            if (b == board(Target.Lang.PIC32)) return;
            PREFS.put("board.pic32", b.key());
        } else if (b.isStm32()) {
            // one STM32 board for both of its languages: STM32 C, and Arduino when that is the language now
            boolean arduino = target().lang() == Target.Lang.ARDUINO;
            if (b == board(Target.Lang.STM32) && (!arduino || b == board())) return;
            PREFS.put("board.stm32", b.key());
            if (arduino) PREFS.put("board", b.key());
        } else {
            if (b == board()) return;
            PREFS.put("board", b.key());
        }
        fire();
    }

    /** The name of a program in a folder: with .exe on Windows. */
    private static String tool(String name) {
        return ExternalEditor.WINDOWS ? name + ".exe" : name;
    }

    /** "Get" beside a tool's field: fetches the tool and writes its path into the field. */
    private static JButton getButton(Component parent, Toolchains.Tool t, JTextField field) {
        JButton b = new JButton(t.downloadable() || t.folder != null ? "Get" : "Where?");
        b.setToolTipText(t.downloadable()
                ? "Download " + t.display + " into EVIA Paradigma's own folder and use it"
                : "Open " + t.display + "'s download page");
        b.addActionListener(e -> {
            // from the EVIA package server when it has the tool, else from the tool's own releases
            java.nio.file.Path exe = PackagesDialog.installTool(parent, t);
            if (exe == null && !PackagesDialog.served(t.folder)) exe = Toolchains.install(parent, t);
            if (exe != null) field.setText(exe.toString());
        });
        return b;
    }

    static String arduinoCli() {
        return tool("arduinoCli", "arduino-cli", "arduino-cli");
    }

    // ---- Python

    /** The minimum Python the generated programs need (str.reconfigure, the walrus-free syntax used). */
    static final int[] MIN_PYTHON = {3, 8};

    private static String detectedPython;

    /** The command that runs a generated Python file, e.g. python3, py or a full path; may carry options ("py -3"). */
    static String pythonCommand() {
        String saved = PREFS.get("python", null);
        if (saved != null && !saved.isBlank()) return saved;
        if (detectedPython == null) detectedPython = detectPython();
        return detectedPython;
    }

    /** The first of py, python3, python that is Python 3.8 or newer; the usual name when none is. */
    static String detectPython() {
        String[] candidates = ExternalEditor.WINDOWS ? new String[]{"py", "python", "python3"} : new String[]{"python3", "python"};
        for (String c : candidates) {
            int[] v = pythonVersion(c);
            if (v != null && atLeast(v, MIN_PYTHON)) return c;
        }
        return candidates[0];
    }

    /** {major, minor, patch} of the Python a command starts, or null when it does not start. */
    static int[] pythonVersion(String command) {
        List<String> cmd = new ArrayList<>(split(command));
        if (cmd.isEmpty()) return null;
        cmd.add("--version");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            Matcher m = Pattern.compile("Python (\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(out);
            if (!m.find()) return null;
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
        } catch (Exception e) {
            return null;
        }
    }

    static boolean atLeast(int[] v, int[] min) {
        return v[0] != min[0] ? v[0] > min[0] : v[1] >= min[1];
    }

    /** Shows which Python a command runs: green when it is new enough, red when it is missing or too old. */
    static void checkPython(String command, JLabel label) {
        label.setText(Text.t("Checking Python..."));
        label.setForeground(Theme.muted());
        Object token = newCheck(label);
        new Thread(() -> {
            int[] v = pythonVersion(command);
            SwingUtilities.invokeLater(() -> {
                if (!isLatestCheck(label, token)) return;
                if (v == null) {
                    label.setText("Python not found - install Python 3 (python.org) or set its command in Settings");
                    label.setForeground(Theme.error());
                } else if (!atLeast(v, MIN_PYTHON)) {
                    label.setText("Python " + v[0] + "." + v[1] + "." + v[2] + " - Python " + MIN_PYTHON[0] + "." + MIN_PYTHON[1]
                            + " or newer is needed");
                    label.setForeground(Theme.error());
                } else {
                    label.setText("Python " + v[0] + "." + v[1] + "." + v[2]);
                    label.setForeground(Theme.ok());
                }
            });
        }, "evia-python-check").start();
    }

    // ---- Java, Kotlin and Android

    /** The oldest JDK that compiles the generated Java (pattern matching for instanceof). */
    static final int MIN_JDK = 17;

    /**
     * The JDK that compiles and runs generated Java and Kotlin: its folder, the one with bin/javac. As set in
     * Settings, else the EVIA package "jdk", else JAVA_HOME, else the javac found on PATH; null when there is none.
     */
    static java.nio.file.Path jdkHome() {
        String saved = PREFS.get("jdk", null);
        if (saved != null && !saved.isBlank()) return jdkFolder(saved);
        java.nio.file.Path pkg = Bundled.tool("jdk", "javac");
        if (pkg == null) pkg = Toolchains.installed(Toolchains.Tool.JDK);
        if (pkg != null) return pkg.getParent().getParent();
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank() && java.nio.file.Files.isRegularFile(jdkProgram(java.nio.file.Path.of(home), "javac"))) {
            return java.nio.file.Path.of(home);
        }
        java.nio.file.Path javac = onPath(ExternalEditor.WINDOWS ? "javac.exe" : "javac");
        if (javac == null) return null;
        try {
            return javac.toRealPath().getParent().getParent();   // /usr/bin/javac is often a link into the JDK
        } catch (java.io.IOException e) {
            return javac.getParent().getParent();
        }
    }

    /** A JDK folder from what was typed: the folder itself, or its bin/javac or bin/java. */
    static java.nio.file.Path jdkFolder(String typed) {
        java.nio.file.Path p = java.nio.file.Path.of(typed.strip());
        if (java.nio.file.Files.isRegularFile(p) && p.getParent() != null && p.getParent().getParent() != null) {
            return p.getParent().getParent();
        }
        return p;
    }

    static void setJdkHome(String dir) {
        PREFS.put("jdk", dir == null ? "" : dir.strip());
        fire();
    }

    /** java, javac, keytool ... of a JDK folder. */
    static java.nio.file.Path jdkProgram(java.nio.file.Path home, String program) {
        return home.resolve("bin").resolve(ExternalEditor.WINDOWS ? program + ".exe" : program);
    }

    /** A program of the chosen JDK, or its plain name (found on PATH) when no JDK is known. */
    static String jdkTool(String program) {
        java.nio.file.Path h = jdkHome();
        return h != null ? jdkProgram(h, program).toString() : program;
    }

    /** The major version of the JDK in a folder (17, 21, 25 ...), from its release file; 0 when unknown. */
    static int jdkVersion(java.nio.file.Path home) {
        try {
            for (String l : java.nio.file.Files.readAllLines(home.resolve("release"))) {
                Matcher m = Pattern.compile("^JAVA_VERSION=\"(?:1\\.)?(\\d+)").matcher(l);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        } catch (Exception ignored) {
            // no release file: an unusual layout
        }
        return 0;
    }

    /** kotlinc: as set, else the EVIA package "kotlin", else from PATH. */
    static String kotlinc() {
        String t = tool("kotlinc", "kotlinc", "kotlin");
        if (!t.equals("kotlinc")) return t;
        java.nio.file.Path own = Toolchains.installed(Toolchains.Tool.KOTLINC);
        return own != null ? own.toString() : t;
    }

    static void setKotlinc(String command) {
        PREFS.put("kotlinc", command == null ? "" : command.strip());
        fire();
    }

    /** The Kotlin standard library next to kotlinc (lib/kotlin-stdlib.jar), for an Android app; null if not found. */
    static java.nio.file.Path kotlinStdlib() {
        java.nio.file.Path k = java.nio.file.Path.of(kotlinc());
        if (!k.isAbsolute()) k = onPath(ExternalEditor.WINDOWS ? "kotlinc.bat" : "kotlinc");
        if (k == null) return null;
        try {
            k = k.toRealPath();
        } catch (java.io.IOException ignored) {
            // keep the path as it is
        }
        java.nio.file.Path jar = k.getParent().getParent().resolve("lib").resolve("kotlin-stdlib.jar");
        return java.nio.file.Files.isRegularFile(jar) ? jar : null;
    }

    /** kotlinc's version from build.txt in its folder ("2.4.21"), or null when kotlinc is not found. */
    static String kotlincVersion() {
        java.nio.file.Path lib = kotlinStdlib();
        if (lib == null) return null;
        try {
            String b = java.nio.file.Files.readString(lib.getParent().getParent().resolve("build.txt")).strip();
            return b.replaceFirst("-release.*", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The Android SDK: as set, else ANDROID_HOME or ANDROID_SDK_ROOT, else Android Studio's usual folder,
     * else the one EVIA Paradigma downloads into. May not exist yet.
     */
    static java.nio.file.Path androidSdk() {
        String saved = PREFS.get("androidSdk", null);
        if (saved != null && !saved.isBlank()) return java.nio.file.Path.of(saved);
        for (String env : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Path.of(v))) return java.nio.file.Path.of(v);
        }
        String user = System.getProperty("user.home");
        String local = System.getenv("LOCALAPPDATA");
        java.nio.file.Path studio = ExternalEditor.WINDOWS && local != null ? java.nio.file.Path.of(local, "Android", "Sdk")
                : System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")
                ? java.nio.file.Path.of(user, "Library", "Android", "sdk") : java.nio.file.Path.of(user, "Android", "Sdk");
        if (java.nio.file.Files.isDirectory(studio.resolve("platforms"))) return studio;
        return Packages.home().getParent().resolve("android-sdk");
    }

    static void setAndroidSdk(String dir) {
        PREFS.put("androidSdk", dir == null ? "" : dir.strip());
        fire();
    }

    private static String androidPackageOverride, androidNameOverride;

    /** The Android app's id (its Java package), e.g. am.evia.counter. */
    static String androidPackage() {
        if (androidPackageOverride != null) return androidPackageOverride;
        String p = PREFS.get("android.package", "am.evia.program");
        return AndroidBuild.validPackage(p) ? p : "am.evia.program";
    }

    /** The name under the app's icon. */
    static String androidAppName() {
        if (androidNameOverride != null) return androidNameOverride;
        String n = PREFS.get("android.name", "EVIA Program");
        return n.isBlank() ? "EVIA Program" : n;
    }

    static void setAndroidApp(String pkg, String name) {
        if (pkg != null) PREFS.put("android.package", pkg.strip());
        if (name != null) PREFS.put("android.name", name.strip());
        fire();
    }

    /** For the command line: this run only, nothing saved. */
    static void useAndroidApp(String pkg, String name) {
        if (pkg != null) androidPackageOverride = pkg;
        if (name != null) androidNameOverride = name;
    }

    /** The adb serial of the phone to install on, or "" for the only one connected. */
    static String androidDevice() {
        return PREFS.get("android.device", "");
    }

    static void setAndroidDevice(String serial) {
        PREFS.put("android.device", serial == null ? "" : serial.strip());
        fire();
    }

    /** A program found on PATH, or null. */
    static java.nio.file.Path onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String d : path.split(java.io.File.pathSeparator)) {
            if (d.isBlank()) continue;
            java.nio.file.Path p = java.nio.file.Path.of(d, name);
            if (java.nio.file.Files.isRegularFile(p) && java.nio.file.Files.isExecutable(p)) return p;
        }
        return null;
    }

    /** The board package index that arduino-cli installs the ESP32 core from. */
    static String esp32Index() {
        return PREFS.get("esp32Index", Toolchains.ESP32_INDEX);
    }

    /**
     * A tool's command: what Settings holds, unless that is only the tool's plain name (or nothing),
     * in which case the copy bundled with the application is used when there is one.
     */
    private static String tool(String key, String plain, String toolchain) {
        String saved = PREFS.get(key, null);
        if (saved != null && !saved.isBlank() && !saved.equals(plain)) return saved;
        return Bundled.or(toolchain, plain);
    }

    static String avrGcc() {
        return tool("avrGcc", "avr-gcc", "avr-gcc");
    }

    /** xc32-gcc, Microchip's free XC32 compiler: as set, else where Microchip's installer put it, else from PATH. */
    static String xc32() {
        String saved = PREFS.get("xc32", null);
        if (saved != null && !saved.isBlank() && !saved.equals("xc32-gcc")) return saved;
        java.nio.file.Path found = Xc32Installer.find();
        return found != null ? found.toString() : "xc32-gcc";
    }

    // ---- STM32

    static String armGcc() {
        return tool("armGcc", "arm-none-eabi-gcc", "arm-none-eabi-gcc");
    }

    static String openocd() {
        return tool("openocd", "openocd", "openocd");
    }

    /** OpenOCD through an ST-Link; CPUTAPID 0 also accepts the clone chips of many Blue Pills and ST-Links. */
    static final String DEFAULT_STM32_UPLOAD = "{openocd} -f interface/stlink.cfg -c \"set CPUTAPID 0\" -f {target}"
            + " -c \"program {elf} verify reset exit\"";

    /**
     * The command that loads a program into an STM32 board: {openocd} = OpenOCD, {target} = its chip family,
     * {elf}, {hex} and {bin} = the program, {device} = the serial port (for a serial bootloader, e.g. stm32flash).
     */
    static String stm32Upload() {
        return PREFS.get("stm32Upload", DEFAULT_STM32_UPLOAD);
    }

    /** The board package index that arduino-cli installs the STM32 core from. */
    static String stm32Index() {
        return PREFS.get("stm32Index", Toolchains.STM32_INDEX);
    }

    /** The command that loads a .hex file into the chip; {hex}, {device} and {chip} (e.g. 32MX795F512L) are replaced. */
    static String pic32Upload() {
        return pic32UploadFor(PREFS.get("pic32Upload", "pic32prog {hex}"));
    }

    /** {@code cmd} with plain pic32prog or ipecmd at the start replaced by the installed program. */
    static String pic32UploadFor(String cmd) {
        // a command that starts with plain pic32prog runs the installed package's copy when there is one
        java.nio.file.Path own = Bundled.tool("pic32prog", "pic32prog");
        if (own != null && (cmd.equals("pic32prog") || cmd.startsWith("pic32prog "))) {
            String exe = own.toString();
            cmd = (exe.contains(" ") ? "\"" + exe + "\"" : exe) + cmd.substring("pic32prog".length());
        }
        // and one that starts with plain ipecmd runs Microchip's, from MPLAB X / IPE
        return IpeInstaller.resolve(cmd);
    }

    // ---- FPGA tools (Vivado, or the open-source flows)

    /**
     * True when PIC32 MQTT should go through the chip's own Ethernet port with Harmony's TCP/IP stack,
     * false (the default) when it should drive a WiFi module with AT firmware on UART2.
     */
    static boolean pic32Ethernet() {
        return PREFS.get("pic32Net", "uart").equals("ethernet");
    }

    /** The language of the interface itself: English, Russian or Armenian. */
    static Text.Lang uiLanguage() {
        return Text.of(PREFS.get("uiLang", "en"));
    }

    static void useUiLanguage(Text.Lang l) {
        PREFS.put("uiLang", l.code);
        Text.use(l);
    }

    /** Chooses how PIC32 MQTT reaches the network; see {@link #pic32Ethernet()}. */
    static void usePic32Net(boolean ethernet) {
        PREFS.put("pic32Net", ethernet ? "ethernet" : "uart");
    }

    static boolean artixOpenSource() {
        return PREFS.get("artixFlow", "vivado").equals("open");
    }

    static Fpga.Tools fpgaTools() {
        return new Fpga.Tools(artixOpenSource(), PREFS.get("vivado", "vivado"), tool("yosys", "yosys", "oss-cad-suite"),
                tool("nextpnrGateMate", "nextpnr-himbaechel", "oss-cad-suite"), tool("gmpack", "gmpack", "oss-cad-suite"),
                tool("openFpgaLoader", "openFPGALoader", "oss-cad-suite"), tool("nextpnrXilinx", "nextpnr-xilinx", "oss-cad-suite"),
                PREFS.get("xilinxChipdb", "chipdb"), PREFS.get("prjxrayDb", "prjxray-db"),
                PREFS.get("fasm2frames", "fasm2frames"), PREFS.get("frames2bit", "xc7frames2bit"));
    }

    static String avrdude() {
        return tool("avrdude", "avrdude", "avr-gcc");
    }

    /** The board's serial port for Upload, e.g. COM3 or /dev/ttyACM0 (empty: ask). */
    static String serialPort() {
        return PREFS.get("serialPort", "");
    }

    static void setSerialPort(String port) {
        PREFS.put("serialPort", port.trim());
    }

    /** The folder of the Linux image last opened in Linux > Linux Images (empty: none yet). */
    static String linuxWorkspace() {
        return PREFS.get("linuxWorkspace", "");
    }

    static void setLinuxWorkspace(String dir) {
        PREFS.put("linuxWorkspace", dir);
    }

    /** True (default): the mouse wheel zooms block diagrams; false: it scrolls and Ctrl+wheel zooms. */
    static boolean wheelZooms() {
        return PREFS.getBoolean("wheelZooms", true);
    }

    /** The language and generation mode used by Generate code (and by projects that don't set their own). */
    static Target target() {
        // the Native edition keeps only the values; its own key, so the two editions do not change each other
        if (App.nativeEdition()) return Target.parse(PREFS.get("target.native", null)).withLang(Target.DEFAULT.lang());
        return Target.parse(PREFS.get("target", null));
    }

    static void setTarget(Target t) {
        if (t.equals(target())) return;
        PREFS.put(App.nativeEdition() ? "target.native" : "target", t.key());
        fire();
    }

    /** The Native edition's target: null for an executable for Linux x86-64, else the AVR chip to build for. */
    static AvrChip nativeChip() {
        String k = PREFS.get("native.chip", "");
        try {
            return k.isEmpty() ? null : AvrChip.of(k);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void setNativeChip(AvrChip chip) {
        if (chip == nativeChip()) return;
        PREFS.put("native.chip", chip == null ? "" : chip.key());
        fire();
    }

    /** The AVR chip's clock in Hz (16 MHz unless set). */
    static long nativeClock() {
        return PREFS.getLong("native.clock", 16_000_000L);
    }

    static void setNativeClock(long hz) {
        if (hz == nativeClock()) return;
        PREFS.putLong("native.clock", hz);
        fire();
    }

    /**
     * How a .hex gets to the chip: "bootloader" (over the serial port, built in: STK500 v1 for Optiboot, v2 on the
     * Mega 2560) or an avrdude programmer ("usbasp", "stk500v1 -b 19200" for Arduino as ISP, "avrispmkII").
     */
    static String nativeUpload() {
        return PREFS.get("native.upload", "bootloader");
    }

    static void setNativeUpload(String how) {
        PREFS.put("native.upload", how);
    }

    /**
     * The compile command for a target, derived from the C++ compiler setting: the matching
     * C compiler for C (clang++ -> clang, g++ -> gcc, c++ -> cc), the target's -std= flag,
     * and -lm for C's math library.
     */
    static String commandFor(Target t) {
        return commandFor(compilerCommand(), t);
    }

    static String commandFor(String cxxCommand, Target t) {
        List<String> tokens = split(cxxCommand);
        if (tokens.isEmpty()) return cxxCommand;
        List<String> out = new ArrayList<>();
        String exe = tokens.get(0);
        if (t.isC()) {
            int slash = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
            String dir = exe.substring(0, slash + 1), name = exe.substring(slash + 1);
            if (name.contains("clang++")) name = name.replace("clang++", "clang");
            else if (name.contains("g++")) name = name.replace("g++", "gcc");
            else if (name.contains("c++")) name = name.replace("c++", "cc");
            exe = dir + name;
        }
        out.add(exe);
        boolean std = false;
        for (String tok : tokens.subList(1, tokens.size())) {
            if (tok.startsWith("-std=")) {
                out.add("-std=" + t.lang().std);
                std = true;
            } else {
                out.add(tok);
            }
        }
        if (!std) out.add(1, "-std=" + t.lang().std);
        if (t.isC() && !out.contains("-lm")) out.add("-lm");
        StringBuilder sb = new StringBuilder();
        for (String tok : out) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(tok.contains(" ") ? "\"" + tok + "\"" : tok);
        }
        return sb.toString();
    }

    static void onChange(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    /** Something outside Settings changed what the tools are (a package was installed or removed). */
    static void changed() {
        fire();
    }

    private static void fire() {
        for (Runnable r : List.copyOf(listeners)) r.run();
    }

    /** Splits a command line into tokens; "double quotes" group a token containing spaces. */
    static List<String> split(String cmd) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, any = false;
        for (char c : cmd.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (any) out.add(cur.toString());
        return out;
    }

    // ================================================================ compiler version

    /** What --version reports for the compiler of a command, e.g. "Clang 23.1.4". */
    record CompilerInfo(String name, int major, String version, String target) {
        boolean isClang() { return name.equals("Clang"); }
        /** Clang that targets MSVC needs Visual Studio's C++ library; EVIA Paradigma avoids that. */
        boolean needsVisualStudio() { return target != null && target.contains("windows-msvc"); }
        boolean isLatestClang() { return isClang() && major >= LATEST_CLANG; }
        String display() { return name + " " + version; }
    }

    private static final Pattern CLANG = Pattern.compile("clang version (\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Pattern GCC = Pattern.compile("(?:g\\+\\+|gcc)[^\\n]*?(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /** Runs "&lt;compiler&gt; --version"; returns null if the compiler can't be started. */
    static CompilerInfo detectCompiler(String command) {
        List<String> tokens = split(command);
        if (tokens.isEmpty()) return null;
        String exe = tokens.get(0);
        try {
            Process p = new ProcessBuilder(List.of(exe, "--version"))
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && sb.length() < 4000) sb.append(line).append('\n');
            }
            p.waitFor(10, TimeUnit.SECONDS);
            String out = sb.toString();
            Matcher m;
            Matcher t = Pattern.compile("(?m)^Target:\\s*(\\S+)").matcher(out);
            String target = t.find() ? t.group(1) : null;
            if ((m = CLANG.matcher(out)).find()) return info("Clang", m, target);
            if ((m = GCC.matcher(out)).find()) return info("GCC", m, target);
            return new CompilerInfo(exe, 0, "(unknown version)", target);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compiles the C++ runtime with {@code command} to make sure the compiler and its
     * C++ standard library work. Returns null on success, otherwise the compiler output.
     */
    static String testCompile(String command) {
        return testCompile(command, false);
    }

    /** Test-compiles a tiny program: the C++ runtime, or for C a program using the C library and math. */
    static String testCompile(String command, boolean c) {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("evia-check");
            java.nio.file.Path src = dir.resolve(c ? "check.c" : "check.cpp");
            java.nio.file.Path exe = dir.resolve(ExternalEditor.WINDOWS ? "check.exe" : "check");
            java.nio.file.Files.writeString(src, c
                    ? "#include <math.h>\n#include <stdio.h>\nint main(void) { printf(\"%g\\n\", fmod(7.5, 2)); return 0; }\n"
                    : CppRuntime.SOURCE + "\nint main() { evia_rt::print(evia_rt::S(\"ok\")); }\n");
            List<String> cmd = new ArrayList<>();
            for (String t : split(command)) cmd.add(t.replace("{src}", src.toString()).replace("{exe}", exe.toString()));
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "compiler timed out";
            }
            return p.exitValue() == 0 ? null : out.isBlank() ? "exit code " + p.exitValue() : out.strip();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static CompilerInfo info(String name, Matcher m, String target) {
        String v = m.group(1) + "." + m.group(2) + (m.group(3) != null ? "." + m.group(3) : "");
        return new CompilerInfo(name, Integer.parseInt(m.group(1)), v, target);
    }

    // ================================================================ dialog

    /** The tabs of the Settings window, one per kind of target; File > Settings opens each directly. */
    enum Page {
        GENERAL, DESKTOP, JVM, ANDROID, ARDUINO, ESP32, AVR, PIC32, STM32, FPGA;

        String title() {
            return switch (this) {
                case GENERAL -> Text.t("General");
                case DESKTOP -> Text.t("C, C++ and Python");
                case JVM -> Text.t("Java and Kotlin");
                case ANDROID -> Text.t("Android");
                case ARDUINO -> Text.t("Arduino");
                case ESP32 -> Text.t("ESP32");
                case AVR -> Text.t("AVR C (bare metal)");
                case PIC32 -> Text.t("PIC32");
                case STM32 -> Text.t("STM32");
                case FPGA -> Text.t("FPGA");
            };
        }

        /** The page for what the toolbar is set to: the code window's Settings button opens it. */
        static Page of(Target t) {
            Target.Lang l = t.lang();
            if (l.fpga()) return FPGA;
            if (l == Target.Lang.PIC32) return PIC32;
            if (l == Target.Lang.STM32) return STM32;
            if (l == Target.Lang.AVR) return AVR;
            if (l == Target.Lang.ARDUINO) return board().isEsp32() ? ESP32 : board().isStm32() ? STM32 : ARDUINO;
            if (l == Target.Lang.ANDROID) return ANDROID;
            if (l.jvm()) return JVM;
            return DESKTOP;
        }
    }

    /** Settings at the page for the current language. */
    static void showDialog(Component parent) {
        showDialog(parent, Page.of(target()));
    }

    /** A page of settings: label, field and buttons in three columns, notes under the field. */
    private static final class Form extends JPanel {
        private final GridBagConstraints g = new GridBagConstraints();

        Form() {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            g.anchor = GridBagConstraints.WEST;
            g.insets = new Insets(4, 4, 4, 4);
            g.gridy = -1;
        }

        Form row(String label, Component field, Component... buttons) {
            g.gridy++; g.gridwidth = 1; g.fill = GridBagConstraints.NONE;
            g.gridx = 0; add(new JLabel(label), g);
            g.gridx = 1; add(field, g);
            if (buttons.length > 0) {
                JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                for (Component c : buttons) b.add(c);
                g.gridx = 2; add(b, g);
            }
            return this;
        }

        /** A field and its buttons given together, e.g. the serial port list and Scan. */
        Form row(String label, Component[] fieldAndButtons) {
            return row(label, fieldAndButtons[0], java.util.Arrays.copyOfRange(fieldAndButtons, 1, fieldAndButtons.length));
        }

        /** Under the field: a hint or a status line. */
        Form note(Component c) {
            g.gridy++; g.gridx = 1; g.gridwidth = 2; g.fill = GridBagConstraints.NONE;
            add(c, g);
            return this;
        }

        /** Across the whole width, e.g. a check box. */
        Form full(Component c) {
            g.gridy++; g.gridx = 0; g.gridwidth = 3; g.fill = GridBagConstraints.NONE;
            add(c, g);
            return this;
        }

        Form gap() {
            g.gridy++; g.gridx = 0; g.gridwidth = 3; g.fill = GridBagConstraints.HORIZONTAL;
            add(new JSeparator(), g);
            return this;
        }

        /** Scrolls when the page is taller than the window; its rows stay at the top. */
        JComponent done() {
            g.gridy++; g.gridx = 0; g.gridwidth = 3; g.weighty = 1; g.weightx = 1; g.fill = GridBagConstraints.BOTH;
            add(Box.createGlue(), g);
            JScrollPane sp = new JScrollPane(this);
            sp.setBorder(null);
            sp.getVerticalScrollBar().setUnitIncrement(16);
            return sp;
        }
    }

    static void showDialog(Component parent, Page page) {
        // ---- General
        JComboBox<Text.Lang> uiLang = new JComboBox<>(Text.Lang.values());
        uiLang.setSelectedItem(uiLanguage());
        JCheckBox vim = new JCheckBox(Text.t("Vim keys in code editors (NORMAL / INSERT / VISUAL modes)"), vimKeys());
        JCheckBox wheel = new JCheckBox("<html>Mouse wheel zooms block diagrams<br><font size='-2' color='#777777'>"
                + "Shift+wheel scrolls sideways, Ctrl+wheel up and down. Off: the wheel scrolls and Ctrl+wheel zooms.</font></html>",
                wheelZooms());
        JTextField editor = new JTextField(editorCommand(), 42);
        JButton detectEditor = new JButton(Text.t("Detect"));
        detectEditor.addActionListener(e -> editor.setText(ExternalEditor.defaultCommand()));

        // ---- C, C++ and Python
        JTextField compiler = new JTextField(compilerCommand(), 42);
        JLabel compilerStatus = new JLabel(" ");
        JComboBox<String> familyBox = new JComboBox<>(new String[]{"Clang (LLVM)", "GCC (GNU)"});
        familyBox.setToolTipText(Text.t("Which compiler builds generated C and C++ (the C compiler follows: clang / gcc)"));
        familyBox.setSelectedIndex(family(compiler.getText()) == ToolchainInstaller.Kind.GCC ? 1 : 0);
        JButton install = new JButton();
        Runnable familyChanged = () -> {
            ToolchainInstaller.Kind k = familyBox.getSelectedIndex() == 1 ? ToolchainInstaller.Kind.GCC : ToolchainInstaller.Kind.CLANG;
            install.setText((ToolchainInstaller.managed(k) == null ? "Install " : "Update ") + k.display + "...");
            install.setToolTipText(k == ToolchainInstaller.Kind.GCC
                    ? "Download the latest WinLibs GCC (MinGW-w64, no Visual Studio needed)"
                    : "Download the latest llvm-mingw (Clang with its own C++ library, no Visual Studio needed)");
        };
        familyChanged.run();
        familyBox.addActionListener(e -> {
            ToolchainInstaller.Kind k = familyBox.getSelectedIndex() == 1 ? ToolchainInstaller.Kind.GCC : ToolchainInstaller.Kind.CLANG;
            compiler.setText(withFamily(compiler.getText(), k));
            familyChanged.run();
            checkCompiler(compiler.getText(), compilerStatus);
        });
        JButton defCompiler = new JButton(Text.t("Default"));
        defCompiler.setToolTipText(Text.t("The default command for the chosen compiler"));
        defCompiler.addActionListener(e -> {
            ToolchainInstaller.Kind k = familyBox.getSelectedIndex() == 1 ? ToolchainInstaller.Kind.GCC : ToolchainInstaller.Kind.CLANG;
            String managed = ToolchainInstaller.managedCommand(k);
            compiler.setText(managed != null ? managed : k == ToolchainInstaller.Kind.GCC ? DEFAULT_GCC : DEFAULT_COMPILER);
            checkCompiler(compiler.getText(), compilerStatus);
        });
        install.addActionListener(e -> {
            ToolchainInstaller.Kind k = familyBox.getSelectedIndex() == 1 ? ToolchainInstaller.Kind.GCC : ToolchainInstaller.Kind.CLANG;
            if (ToolchainInstaller.install(p(compiler), k)) {
                compiler.setText(ToolchainInstaller.managedCommand(k));
                familyChanged.run();
                checkCompiler(compiler.getText(), compilerStatus);
            }
        });
        JButton check = new JButton(Text.t("Check"));
        check.addActionListener(e -> checkCompiler(compiler.getText(), compilerStatus));
        JTextField python = new JTextField(pythonCommand(), 42);
        python.setToolTipText(Text.t("The command that runs generated Python, e.g. python3, py or a full path"));
        JLabel pythonStatus = new JLabel(" ");
        JButton detectPython = new JButton(Text.t("Detect"));
        detectPython.addActionListener(e -> {
            python.setText(detectPython());
            checkPython(python.getText(), pythonStatus);
        });
        JButton checkPythonBtn = new JButton(Text.t("Check"));
        checkPythonBtn.addActionListener(e -> checkPython(python.getText(), pythonStatus));

        // ---- boards: one serial port, shared by every board page
        DefaultComboBoxModel<String> ports = new DefaultComboBoxModel<>();
        java.util.Map<String, String> portInfo = new java.util.HashMap<>();
        if (!serialPort().isEmpty()) ports.addElement(serialPort());
        ports.setSelectedItem(serialPort());
        JLabel portStatus = small(" ");
        List<JComboBox<String>> portBoxes = new ArrayList<>();
        List<JButton> scanButtons = new ArrayList<>();
        Runnable scanAll = () -> scanPorts(ports, portInfo, portStatus, scanButtons);
        java.util.function.Supplier<Component[]> portRow = () -> {
            JComboBox<String> box = portBox(ports, portInfo);
            JButton scan = new JButton(Text.t("Scan"));
            scan.setToolTipText(Text.t("Look for the serial ports of the boards plugged in now"));
            scan.addActionListener(e -> scanAll.run());
            portBoxes.add(box);
            scanButtons.add(scan);
            return new Component[]{box, scan};
        };

        JTextField cli = new JTextField(arduinoCli(), 30);
        // Arduino and ESP32 each have a list; the one changed last is the board the Arduino language builds for
        Board current = board();
        JComboBox<Board> arduinoBoard = new JComboBox<>(java.util.Arrays.stream(Board.values()).filter(Board::isArduinoAvr).toArray(Board[]::new));
        arduinoBoard.setSelectedItem(current.isArduinoAvr() ? current : Board.UNO);
        JComboBox<Board> espBoard = new JComboBox<>(java.util.Arrays.stream(Board.values()).filter(Board::isEsp32).toArray(Board[]::new));
        espBoard.setSelectedItem(current.isEsp32() ? current : Board.ESP32);
        Board[] chosen = {current};
        arduinoBoard.addActionListener(e -> chosen[0] = (Board) arduinoBoard.getSelectedItem());
        espBoard.addActionListener(e -> chosen[0] = (Board) espBoard.getSelectedItem());
        JTextField esp32 = new JTextField(esp32Index(), 30);
        esp32.setCaretPosition(0);
        esp32.setToolTipText(Text.t("Espressif's board package index; arduino-cli installs the ESP32 core from it"));
        JButton esp32Install = new JButton(Text.t("Install"));
        esp32Install.setToolTipText(Text.t("Install or update the ESP32 core (esp32:esp32) with arduino-cli"));
        esp32Install.addActionListener(e -> Toolchains.installEsp32Core(p(esp32Install),
                cli.getText().trim().isEmpty() ? "arduino-cli" : cli.getText().trim(), esp32.getText()));
        // the ESP32 page has its own arduino-cli field, kept in step with the Arduino page's
        JTextField cli2 = new JTextField(30);
        cli2.setDocument(cli.getDocument());

        JComboBox<Board> stmBoard = new JComboBox<>(java.util.Arrays.stream(Board.values()).filter(Board::isStm32).toArray(Board[]::new));
        stmBoard.setSelectedItem(current.isStm32() ? current : board(Target.Lang.STM32));
        stmBoard.addActionListener(e -> chosen[0] = (Board) stmBoard.getSelectedItem());
        JTextField armGcc = new JTextField(armGcc(), 30);
        JTextField openocd = new JTextField(openocd(), 30);
        JTextField stm32Up = new JTextField(stm32Upload(), 30);
        stm32Up.setCaretPosition(0);
        JButton stm32UpDefault = new JButton(Text.t("Default"));
        stm32UpDefault.addActionListener(e -> stm32Up.setText(DEFAULT_STM32_UPLOAD));
        JTextField stm32Idx = new JTextField(stm32Index(), 30);
        stm32Idx.setCaretPosition(0);
        stm32Idx.setToolTipText(Text.t("STMicroelectronics' board package index; arduino-cli installs the STM32 core from it"));
        JButton stm32Install = new JButton(Text.t("Install"));
        stm32Install.setToolTipText(Text.t("Install or update the STM32 core (STMicroelectronics:stm32) with arduino-cli"));
        stm32Install.addActionListener(e -> Toolchains.installStm32Core(p(stm32Install),
                cli.getText().trim().isEmpty() ? "arduino-cli" : cli.getText().trim(), stm32Idx.getText()));
        JTextField cli3 = new JTextField(30);
        cli3.setDocument(cli.getDocument());

        JTextField avrGcc = new JTextField(avrGcc(), 30);
        JTextField avrdude = new JTextField(avrdude(), 30);

        JComboBox<Board> picChip = new JComboBox<>(java.util.Arrays.stream(Board.values()).filter(Board::isPic32).toArray(Board[]::new));
        picChip.setSelectedItem(board(Target.Lang.PIC32));
        JTextField xc32 = new JTextField(xc32(), 30);
        JTextField pic32Up = new JTextField(pic32Upload(), 30);
        JButton getXc32 = new JButton(Text.t("Get XC32..."));
        getXc32.setToolTipText(Text.t("Download Microchip's free XC32 from microchip.com and install it"));
        getXc32.addActionListener(e -> {
            java.nio.file.Path gcc = Xc32Installer.install(p(getXc32));
            if (gcc != null) xc32.setText(gcc.toString());
        });
        // the programmer: pic32prog (PICkit2/3, bootloaders) or Microchip's ipecmd (starter kits, PICkit 4/5, ICD, Snap)
        String[] progLabels = new String[IpeInstaller.PRESETS.length + 1];
        progLabels[0] = Text.t("pic32prog: PICkit2, PICkit3 (PICkit2 mode), bootloaders");
        for (int i = 0; i < IpeInstaller.PRESETS.length; i++) progLabels[i + 1] = "ipecmd: " + Text.t(IpeInstaller.PRESETS[i][0]);
        JComboBox<String> pic32Prog = new JComboBox<>(progLabels);
        pic32Prog.setSelectedIndex(-1);
        String cur = PREFS.get("pic32Upload", "pic32prog {hex}");
        if (cur.equals("pic32prog {hex}")) pic32Prog.setSelectedIndex(0);
        for (int i = 0; i < IpeInstaller.PRESETS.length; i++) if (cur.equals(IpeInstaller.PRESETS[i][1])) pic32Prog.setSelectedIndex(i + 1);
        pic32Prog.addActionListener(e -> {
            int i = pic32Prog.getSelectedIndex();
            if (i < 0) return;
            pic32Up.setText(IpeInstaller.resolve(i == 0 ? "pic32prog {hex}" : IpeInstaller.PRESETS[i - 1][1]));
            if (i == 0 && Bundled.tool("pic32prog", "pic32prog") != null) pic32Up.setText(pic32UploadFor("pic32prog {hex}"));
        });
        JButton getIpe = new JButton(Text.t("Get MPLAB IPE..."));
        getIpe.setToolTipText(Text.t("Install Microchip's MPLAB IPE (ipecmd) from the MPLAB X installer you download at microchip.com"));
        getIpe.addActionListener(e -> {
            java.nio.file.Path exe = IpeInstaller.install(p(getIpe));
            if (exe != null && pic32Up.getText().trim().startsWith("ipecmd")) pic32Up.setText(IpeInstaller.resolve(pic32Up.getText().trim()));
        });
        JComboBox<String> pic32Net = new JComboBox<>(new String[] {
                "WiFi module on UART2 (every chip)", "Ethernet, Harmony TCP/IP (MX695/795)"});
        pic32Net.setSelectedIndex(pic32Ethernet() ? 1 : 0);

        JComboBox<Board> fpgaBoard = new JComboBox<>(java.util.Arrays.stream(Board.values()).filter(Board::isFpga).toArray(Board[]::new));
        fpgaBoard.setSelectedItem(board(Target.Lang.VHDL));
        JComboBox<String> artixFlow = new JComboBox<>(new String[] {"Vivado", "Open source (openXC7)"});
        artixFlow.setSelectedIndex(artixOpenSource() ? 1 : 0);
        Fpga.Tools ft = fpgaTools();
        JTextField vivado = new JTextField(ft.vivado(), 30), yosys = new JTextField(ft.yosys(), 30),
                gatemate = new JTextField(ft.nextpnrGateMate(), 30), gmpack = new JTextField(ft.gmpack(), 30),
                loader = new JTextField(ft.loader(), 30), nextpnrXilinx = new JTextField(ft.nextpnrXilinx(), 30),
                chipdb = new JTextField(ft.chipdbDir(), 30), prjxray = new JTextField(ft.prjxrayDb(), 30),
                fasm2frames = new JTextField(ft.fasm2frames(), 30), frames2bit = new JTextField(ft.frames2bit(), 30);
        JButton getOss = new JButton(Text.t("Get FPGA tools"));
        getOss.setToolTipText(Text.t("Download the OSS CAD Suite (yosys, nextpnr, gmpack, openFPGALoader) and fill in the fields below"));
        getOss.addActionListener(e -> {
            java.nio.file.Path exe = PackagesDialog.installTool(p(getOss), Toolchains.Tool.OSS_CAD);
            if (exe == null && !PackagesDialog.served(Toolchains.Tool.OSS_CAD.folder)) exe = Toolchains.install(p(getOss), Toolchains.Tool.OSS_CAD);
            if (exe == null) return;
            java.nio.file.Path bin = exe.getParent();
            yosys.setText(bin.resolve(tool("yosys")).toString());
            gatemate.setText(bin.resolve(tool("nextpnr-himbaechel")).toString());
            gmpack.setText(bin.resolve(tool("gmpack")).toString());
            loader.setText(bin.resolve(tool("openFPGALoader")).toString());
            nextpnrXilinx.setText(bin.resolve(tool("nextpnr-xilinx")).toString());
        });

        // ---- the pages
        JTabbedPane tabs = new JTabbedPane();
        Form general = new Form()
                .row(Text.t("Interface language:"), uiLang)
                .note(small(Text.t("The menus, buttons and block names. Messages from the compilers and the generated code stay in English.")))
                .full(vim)
                .full(wheel)
                .row(Text.t("External editor (Vim):"), editor, detectEditor)
                .note(small("{file} = file to edit. gVim: gvim -f {file}   Terminal Vim on Windows: cmd /c start \"Vim\" /wait vim {file}"));

        Form desktop = new Form();
        desktop.row(Text.t("Compiler:"), familyBox)
                .row(Text.t("Command (C++):"), compiler, ToolchainInstaller.supported()
                        ? new Component[]{defCompiler, check, install} : new Component[]{defCompiler, check})
                .note(small("{src} = generated file, {exe} = program. For C, the matching C compiler (clang, gcc) and -std= are used."))
                .note(small("Clang " + LATEST_CLANG + " is the latest stable; GCC " + MIN_GCC + " or newer works too"
                        + (ToolchainInstaller.supported() ? ". Windows: Install sets up llvm-mingw or WinLibs GCC, no Visual Studio needed." : ".")))
                .note(compilerStatus)
                .gap()
                .row(Text.t("Python:"), python, detectPython, checkPythonBtn, getButton(desktop, Toolchains.Tool.PYTHON, python))
                .note(small("Language Python 3 runs the generated file with this command (Python " + MIN_PYTHON[0] + "." + MIN_PYTHON[1]
                        + " or newer, standard library only)."))
                .note(pythonStatus);

        // ---- Java and Kotlin
        java.nio.file.Path jdkNow = jdkHome();
        JTextField jdk = new JTextField(jdkNow == null ? "" : jdkNow.toString(), 42);
        jdk.setToolTipText(Text.t("The JDK's folder (the one with bin/javac), or empty to find one by itself"));
        JLabel jdkStatus = new JLabel(" ");
        JButton detectJdk = new JButton(Text.t("Detect"));
        detectJdk.addActionListener(e -> {
            String before = PREFS.get("jdk", "");
            PREFS.put("jdk", "");
            java.nio.file.Path found = jdkHome();
            PREFS.put("jdk", before);
            jdk.setText(found == null ? "" : found.toString());
            checkJdk(jdk.getText(), jdkStatus);
        });
        JButton checkJdkBtn = new JButton(Text.t("Check"));
        checkJdkBtn.addActionListener(e -> checkJdk(jdk.getText(), jdkStatus));
        JTextField kotlinc = new JTextField(kotlinc(), 42);
        kotlinc.setToolTipText(Text.t("kotlinc: its full path, or kotlinc to take it from PATH"));
        JLabel kotlinStatus = new JLabel(" ");
        JButton checkKotlinBtn = new JButton(Text.t("Check"));
        checkKotlinBtn.addActionListener(e -> checkKotlinc(kotlinc.getText(), kotlinStatus));

        // ---- Android
        JTextField sdk = new JTextField(androidSdk().toString(), 42);
        JLabel sdkStatus = new JLabel(" ");
        JButton getSdk = new JButton(Text.t("Get Android SDK..."));
        getSdk.setToolTipText(Text.t("Download the platform, build-tools and platform-tools from Google, after you accept Google's licence"));
        getSdk.addActionListener(e -> {
            if (AndroidSdkDialog.install(p(sdk), java.nio.file.Path.of(sdk.getText().strip()))) checkSdk(sdk.getText(), sdkStatus);
        });
        JButton checkSdkBtn = new JButton(Text.t("Check"));
        checkSdkBtn.addActionListener(e -> checkSdk(sdk.getText(), sdkStatus));
        JTextField appId = new JTextField(androidPackage(), 30);
        appId.setToolTipText(Text.t("Lower-case words with dots, e.g. am.evia.counter; a phone keeps one app per id"));
        JTextField appName = new JTextField(androidAppName(), 30);
        DefaultComboBoxModel<String> phoneModel = new DefaultComboBoxModel<>();
        JComboBox<String> phone = new JComboBox<>(phoneModel);
        phone.setEditable(true);
        phone.setPrototypeDisplayValue("emulator-5554  -  Pixel 9 Pro XL");
        phoneModel.addElement(androidDevice());
        JLabel phoneStatus = new JLabel(" ");
        JButton scanPhones = new JButton(Text.t("Scan"));
        scanPhones.addActionListener(e -> scanPhones(phoneModel, phone, phoneStatus, scanPhones));

        Form jvm = new Form();
        jvm.row(Text.t("JDK:"), jdk, detectJdk, checkJdkBtn, getButton(jvm, Toolchains.Tool.JDK, jdk))
                .note(small("Language Java compiles the generated file with this JDK's javac and runs it with its java (JDK "
                        + MIN_JDK + " or newer). Kotlin and Android apps use it too. Empty: JAVA_HOME, or the javac on PATH."))
                .note(jdkStatus)
                .gap()
                .row("kotlinc:", kotlinc, checkKotlinBtn, getButton(jvm, Toolchains.Tool.KOTLINC, kotlinc))
                .note(small("Language Kotlin builds a runnable jar with kotlinc (-include-runtime) and runs it with the JDK above."
                        + " Get downloads JetBrains' latest Kotlin compiler."))
                .note(kotlinStatus);

        Form android = new Form();
        android.row(Text.t("Android SDK:"), sdk, checkSdkBtn, getSdk)
                .note(small("Language Android builds an app (an APK) with the SDK's aapt2, R8 and apksigner and kotlinc: no Gradle and no"
                        + " Android Studio needed. Get Android SDK downloads platform " + AndroidBuild.PLATFORM + ", build-tools "
                        + AndroidBuild.BUILD_TOOLS + " and platform-tools (adb) from Google, about 140 MB. An SDK of Android Studio works too."))
                .note(sdkStatus)
                .gap()
                .row(Text.t("Application id:"), appId)
                .row(Text.t("App name:"), appName)
                .note(small("The id names the app on the phone (installing again replaces the app with the same id); the name is shown"
                        + " under its icon. The project folder opens in Android Studio as well (Gradle, Android Gradle plugin " + AndroidBuild.AGP + ")."))
                .gap()
                .row(Text.t("Phone:"), phone, scanPhones)
                .note(small("Install on Phone uses adb: connect the phone by USB with USB debugging on (Settings > About phone: tap"
                        + " Build number seven times; then Developer options > USB debugging), or start an emulator. Empty: the only one connected."))
                .note(phoneStatus);

        Form arduino = new Form();
        arduino.row(Text.t("Board:"), arduinoBoard)
                .note(small("Uno, Nano, Mega 2560, Leonardo, Micro and Pro Mini: Language Arduino builds a sketch with arduino-cli (core arduino:avr)."))
                .row(Text.t("Serial port:"), portRow.get())
                .note(portStatus)
                .row(Text.t("arduino-cli:"), cli, getButton(arduino, Toolchains.Tool.ARDUINO_CLI, cli))
                .note(small("The arduino:avr core is downloaded by arduino-cli the first time a sketch is built."));

        Form esp = new Form();
        esp.row(Text.t("Board:"), espBoard)
                .note(small("ESP32, -S2, -S3, -C3, -C6 and -H2: Language Arduino with core esp32:esp32. MQTT uses the chip's own WiFi (not on the H2)."))
                .row(Text.t("Serial port:"), portRow.get())
                .row(Text.t("arduino-cli:"), cli2, getButton(esp, Toolchains.Tool.ARDUINO_CLI, cli2))
                .row(Text.t("ESP32 core:"), esp32, esp32Install)
                .note(small("The ESP32 boards need their core once: Install runs arduino-cli core install esp32:esp32 from this address."));

        Form avr = new Form();
        avr.note(small("<html>Language AVR C writes bare-metal C for the chip of the board chosen on the Arduino page,<br>"
                        + "built with avr-gcc and uploaded with avrdude. Leonardo / Micro: press reset just before uploading.</html>"))
                .row(Text.t("Serial port:"), portRow.get())
                .row(Text.t("avr-gcc:"), avrGcc, getButton(avr, Toolchains.Tool.AVR_GCC, avrGcc))
                .row(Text.t("avrdude:"), avrdude);

        Form pic = new Form();
        pic.row(Text.t("Chip:"), picChip)
                .row(Text.t("Serial port:"), portRow.get())
                .row(Text.t("xc32-gcc:"), xc32, getXc32)
                .note(small("Get XC32 downloads Microchip's free XC32 " + Xc32Installer.VERSION
                        + " from microchip.com and runs Microchip's installer (it asks for an administrator password)."))
                .row(Text.t("Programmer:"), pic32Prog)
                .row(Text.t("PIC32 upload:"), pic32Up, getButton(pic, Toolchains.Tool.PIC32PROG, new JTextField()))
                .note(small("Get installs pic32prog from the EVIA server: PICkit2, PICkit3 (PICkit2 mode), the chipKIT and Microchip bootloaders."))
                .row(Text.t("MPLAB IPE:"), new JLabel(IpeInstaller.find() == null ? Text.t("not installed") : IpeInstaller.find().toString()), getIpe)
                .note(small("ipecmd (Microchip's MPLAB IPE) programs the starter kits' on-board programmer (PIC32MX Ethernet Starter Kit II), PICkit 4/5, ICD 4/5 and Snap. {chip} = the chip above."))
                .note(small("PIC32 C builds with Microchip's free XC32 and loads the .hex with this command ({hex} = file, {device} = serial port, {chip} = chip)."))
                .row(Text.t("PIC32 MQTT over:"), pic32Net)
                .note(small("<html>The MQTT blocks reach the network through a WiFi module with AT firmware on UART2, which every PIC32MX can do,<br>"
                        + "or through the chip's own Ethernet port on the MX695/MX795, which needs Harmony's TCP/IP stack: the generated file is then<br>"
                        + "an application for an MPLAB X Harmony project instead of a self-contained program. See the guide.</html>"));

        Form stm = new Form();
        stm.row(Text.t("Board:"), stmBoard)
                .note(small("<html>Blue Pill, Black Pill, Nucleo-F401RE/F411RE and the F4DISCOVERY. Language STM32 C writes bare-metal C for the chip<br>"
                        + "(registers only, built with arm-none-eabi-gcc); Language Arduino builds a sketch with STMicroelectronics' core.<br>"
                        + "Both upload with OpenOCD through an ST-Link: built into the Nucleo and Discovery, a separate ST-Link V2 for the Pills.</html>"))
                .row(Text.t("Serial port:"), portRow.get())
                .row("arm-none-eabi-gcc:", armGcc, getButton(stm, Toolchains.Tool.ARM_GCC, armGcc))
                .row("OpenOCD:", openocd, getButton(stm, Toolchains.Tool.OPENOCD, openocd))
                .row(Text.t("STM32 upload:"), stm32Up, stm32UpDefault)
                .note(small("{openocd} = OpenOCD above, {target} = the chip family's OpenOCD file, {elf} {hex} {bin} = the program, {device} = serial port."))
                .gap()
                .row(Text.t("arduino-cli:"), cli3, getButton(stm, Toolchains.Tool.ARDUINO_CLI, cli3))
                .row(Text.t("STM32 core:"), stm32Idx, stm32Install)
                .note(small("Language Arduino needs the STM32 core once: Install runs arduino-cli core install STMicroelectronics:stm32."));

        Form fpga = new Form();
        fpga.row(Text.t("Board:"), fpgaBoard, getOss)
                .row(Text.t("Artix-7 flow:"), artixFlow)
                .row("Vivado:", vivado).row("yosys:", yosys).row("nextpnr-xilinx:", nextpnrXilinx)
                .row(Text.t("Chip database folder:"), chipdb).row(Text.t("prjxray-db folder:"), prjxray)
                .row("fasm2frames:", fasm2frames).row("xc7frames2bit:", frames2bit)
                .row("nextpnr-himbaechel:", gatemate).row("gmpack:", gmpack).row("openFPGALoader:", loader)
                .note(small("<html>Artix-7 builds with Vivado (batch mode) or with the open-source openXC7 tools (yosys, nextpnr-xilinx, Project X-Ray);<br>"
                        + "the GateMate always uses yosys, nextpnr-himbaechel and gmpack. openFPGALoader loads the bitstream. VHDL needs yosys with its ghdl plugin.</html>"));

        Map<Page, JComponent> pages = new java.util.EnumMap<>(Page.class);
        pages.put(Page.GENERAL, general.done());
        pages.put(Page.DESKTOP, desktop.done());
        pages.put(Page.JVM, jvm.done());
        pages.put(Page.ANDROID, android.done());
        pages.put(Page.ARDUINO, arduino.done());
        pages.put(Page.ESP32, esp.done());
        pages.put(Page.AVR, avr.done());
        pages.put(Page.PIC32, pic.done());
        pages.put(Page.STM32, stm.done());
        pages.put(Page.FPGA, fpga.done());
        for (Page pg : Page.values()) tabs.addTab(pg.title(), pages.get(pg));
        tabs.setSelectedIndex(page.ordinal());
        tabs.setPreferredSize(new Dimension(900, 520));

        checkCompiler(compiler.getText(), compilerStatus);
        checkPython(python.getText(), pythonStatus);
        checkJdk(jdk.getText(), jdkStatus);
        checkKotlinc(kotlinc.getText(), kotlinStatus);
        checkSdk(sdk.getText(), sdkStatus);
        scanAll.run();

        int r = JOptionPane.showConfirmDialog(parent, tabs, Text.t("Settings") + " - EVIA Paradigma",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        PREFS.put("editor", editor.getText().trim());
        PREFS.put("compiler", compiler.getText().trim().isEmpty() ? DEFAULT_COMPILER : compiler.getText().trim());
        PREFS.putBoolean("vimKeys", vim.isSelected());
        PREFS.put("board", chosen[0].key());
        PREFS.put("board.pic32", ((Board) picChip.getSelectedItem()).key());
        PREFS.put("board.stm32", ((Board) stmBoard.getSelectedItem()).key());
        PREFS.put("board.fpga", ((Board) fpgaBoard.getSelectedItem()).key());
        // the port typed or picked on the page in front, else the one all the lists share
        String port = ports.getSelectedItem() == null ? "" : ports.getSelectedItem().toString().trim();
        for (JComboBox<String> box : portBoxes) {
            if (SwingUtilities.isDescendingFrom(box, tabs.getSelectedComponent())) port = portOf(box);
        }
        PREFS.put("serialPort", port);
        putTool("arduinoCli", cli, "arduino-cli", "arduino-cli");
        if (!python.getText().trim().equals(pythonCommand())) PREFS.put("python", python.getText().trim());
        java.nio.file.Path autoJdk;
        {
            String before = PREFS.get("jdk", "");
            PREFS.put("jdk", "");
            autoJdk = jdkHome();
            PREFS.put("jdk", before);
        }
        String jdkTyped = jdk.getText().trim();
        PREFS.put("jdk", jdkTyped.isEmpty() || (autoJdk != null && jdkFolder(jdkTyped).equals(autoJdk)) ? "" : jdkTyped);
        putTool("kotlinc", kotlinc, "kotlinc", "kotlin");
        String sdkTyped = sdk.getText().trim();
        PREFS.put("androidSdk", sdkTyped);
        if (AndroidBuild.validPackage(appId.getText().trim())) PREFS.put("android.package", appId.getText().trim());
        else if (!appId.getText().trim().equals(androidPackage())) {
            JOptionPane.showMessageDialog(parent, "'" + appId.getText().trim() + "' is not a valid application id: lower-case words"
                    + " joined by dots, at least two (e.g. am.evia.counter). The id stays " + androidPackage() + ".",
                    Text.t("Android"), JOptionPane.WARNING_MESSAGE);
        }
        PREFS.put("android.name", appName.getText().trim().isEmpty() ? "EVIA Program" : appName.getText().trim());
        String phoneTyped = phone.getEditor().getItem() == null ? "" : phone.getEditor().getItem().toString().trim();
        PREFS.put("android.device", phoneTyped.contains(" ") ? phoneTyped.substring(0, phoneTyped.indexOf(' ')) : phoneTyped);
        PREFS.put("esp32Index", esp32.getText().trim().isEmpty() ? Toolchains.ESP32_INDEX : esp32.getText().trim());
        putTool("avrGcc", avrGcc, "avr-gcc", "avr-gcc");
        putTool("armGcc", armGcc, "arm-none-eabi-gcc", "arm-none-eabi-gcc");
        putTool("openocd", openocd, "openocd", "openocd");
        PREFS.put("stm32Upload", stm32Up.getText().trim().isEmpty() ? DEFAULT_STM32_UPLOAD : stm32Up.getText().trim());
        PREFS.put("stm32Index", stm32Idx.getText().trim().isEmpty() ? Toolchains.STM32_INDEX : stm32Idx.getText().trim());
        putTool("avrdude", avrdude, "avrdude", "avr-gcc");
        java.nio.file.Path foundXc32 = Xc32Installer.find();
        String xc = xc32.getText().trim();
        PREFS.put("xc32", xc.isEmpty() || (foundXc32 != null && xc.equals(foundXc32.toString())) ? "xc32-gcc" : xc);
        String up = pic32Up.getText().trim();
        java.nio.file.Path ownProg = Bundled.tool("pic32prog", "pic32prog");
        if (ownProg != null) {
            String exe = ownProg.toString(), quoted = exe.contains(" ") ? "\"" + exe + "\"" : exe;
            if (up.startsWith(quoted)) up = "pic32prog" + up.substring(quoted.length());   // stays "pic32prog": follows updates
        }
        java.nio.file.Path ipe = IpeInstaller.find();
        if (ipe != null) {
            String exe = ipe.toString(), quoted = exe.contains(" ") ? "\"" + exe + "\"" : exe;
            if (up.startsWith(quoted)) up = "ipecmd" + up.substring(quoted.length());       // stays "ipecmd": follows MPLAB X updates
        }
        PREFS.put("pic32Upload", up.isEmpty() ? "pic32prog {hex}" : up);
        Text.Lang beforeLang = uiLanguage();
        useUiLanguage((Text.Lang) uiLang.getSelectedItem());
        PREFS.put("pic32Net", pic32Net.getSelectedIndex() == 1 ? "ethernet" : "uart");
        PREFS.put("artixFlow", artixFlow.getSelectedIndex() == 1 ? "open" : "vivado");
        putTool("vivado", vivado, "vivado");
        putTool("yosys", yosys, "yosys", "oss-cad-suite");
        putTool("nextpnrXilinx", nextpnrXilinx, "nextpnr-xilinx", "oss-cad-suite");
        putTool("xilinxChipdb", chipdb, "chipdb");
        putTool("prjxrayDb", prjxray, "prjxray-db");
        putTool("fasm2frames", fasm2frames, "fasm2frames");
        putTool("frames2bit", frames2bit, "xc7frames2bit");
        putTool("nextpnrGateMate", gatemate, "nextpnr-himbaechel", "oss-cad-suite");
        putTool("gmpack", gmpack, "gmpack", "oss-cad-suite");
        putTool("openFpgaLoader", loader, "openFPGALoader", "oss-cad-suite");
        PREFS.putBoolean("wheelZooms", wheel.isSelected());
        fire();
        if (uiLanguage() != beforeLang) {
            JOptionPane.showMessageDialog(parent,
                    Text.t("The interface is in %s the next time EVIA Paradigma starts.", uiLanguage().display),
                    Text.t("Interface language"), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // ---------------------------------------------------------------- serial port list

    /** An editable list of ports: pick a scanned one, or type one that a scan can't see. Every board page shares one list. */
    private static JComboBox<String> portBox(DefaultComboBoxModel<String> model, java.util.Map<String, String> info) {
        JComboBox<String> box = new JComboBox<>(model);
        box.setEditable(true);
        box.setPrototypeDisplayValue("/dev/cu.usbmodem14101XX");
        box.setToolTipText(ExternalEditor.WINDOWS ? "e.g. COM3 (Device Manager, Ports)" : "e.g. /dev/ttyACM0, /dev/ttyUSB0 or /dev/cu.usbmodem1101");
        // the list shows what is on each port; the box itself keeps only the port's name
        box.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                Object text = index >= 0 && info.get(value) != null ? value + "  -  " + info.get(value) : value;
                return super.getListCellRendererComponent(list, text, index, sel, focus);
            }
        });
        return box;
    }

    /** The port in the box, typed or picked. */
    private static String portOf(JComboBox<String> box) {
        Object v = box.isEditable() ? box.getEditor().getItem() : box.getSelectedItem();
        return v == null ? "" : v.toString().trim();
    }

    /** Fills the shared list with the ports there are now, keeping the one already chosen. */
    private static void scanPorts(DefaultComboBoxModel<String> model, java.util.Map<String, String> info, JLabel status,
                                  List<JButton> scans) {
        for (JButton b : scans) b.setEnabled(false);
        status.setText(Text.t("Looking for serial ports..."));
        new Thread(() -> {
            List<SerialPorts.Port> found = SerialPorts.scan();
            SwingUtilities.invokeLater(() -> {
                String current = model.getSelectedItem() == null ? "" : model.getSelectedItem().toString().trim();
                info.clear();
                model.removeAllElements();
                for (SerialPorts.Port pt : found) {
                    model.addElement(pt.name());
                    if (!pt.description().isEmpty()) info.put(pt.name(), pt.description());
                }
                boolean known = found.stream().anyMatch(pt -> pt.name().equals(current));
                if (!current.isEmpty() && !known) model.insertElementAt(current, 0);
                // an empty choice takes the first board found; a chosen port stays chosen
                model.setSelectedItem(current.isEmpty() && !found.isEmpty() ? found.get(0).name() : current);
                if (found.isEmpty()) {
                    status.setText(Text.t("No serial ports found: is the board plugged in?"));
                } else {
                    status.setText(Text.t("Found %d serial port(s).", found.size())
                            + (!current.isEmpty() && !known ? " " + Text.t("%s is not connected now.", current) : ""));
                }
                for (JButton b : scans) b.setEnabled(true);
            });
        }, "evia-port-scan").start();
    }

    private static void putTool(String key, JTextField field, String fallback) {
        PREFS.put(key, field.getText().trim().isEmpty() ? fallback : field.getText().trim());
    }

    /** Saves a tool's field; left at the bundled copy (or empty) it is stored as the plain name, so it follows updates. */
    private static void putTool(String key, JTextField field, String plain, String toolchain) {
        String v = field.getText().trim();
        PREFS.put(key, v.isEmpty() || v.equals(Bundled.or(toolchain, plain)) ? plain : v);
    }

    static void checkCompiler(String command, JLabel label) {
        checkCompiler(command, label, false);
    }

    static void checkCompiler(String command, JLabel label, boolean c) {
        label.setText(Text.t("Checking compiler..."));
        label.setForeground(Theme.muted());
        Object token = newCheck(label);
        new Thread(() -> {
            CompilerInfo ci = detectCompiler(command);
            String problem = ci == null ? null : testCompile(command, c);
            SwingUtilities.invokeLater(() -> {
                if (!isLatestCheck(label, token)) return;   // a newer check started meanwhile
                describe(ci, label);
                if (problem != null) {
                    boolean noStdlib = ToolchainInstaller.looksLikeMissingToolchain(problem);
                    label.setText(ci.display() + (noStdlib
                            ? "  - found, but it has no C++ standard library"
                            + (ToolchainInstaller.supported() ? ": click \"Install " + family(command).display + "...\"" : "")
                            : "  - test compile failed"));
                    label.setToolTipText("<html><pre>" + problem.replace("&", "&amp;").replace("<", "&lt;") + "</pre></html>");
                    label.setForeground(Theme.error());
                } else {
                    label.setToolTipText(null);
                }
            });
        }, "evia-compiler-check").start();
    }

    /** Marks the newest check shown on a label, so a slower earlier check can't overwrite it. */
    static Object newCheck(JComponent label) {
        Object token = new Object();
        label.putClientProperty("evia.check", token);
        return token;
    }

    static boolean isLatestCheck(JComponent label, Object token) {
        return label.getClientProperty("evia.check") == token;
    }

    private static Component p(Component c) {
        return SwingUtilities.getWindowAncestor(c) != null ? SwingUtilities.getWindowAncestor(c) : c;
    }

    /** Shows a compiler's status: green for Clang 23+, orange for older or other compilers, red if missing. */
    static void describe(CompilerInfo ci, JLabel label) {
        if (ci == null) {
            label.setText(ToolchainInstaller.supported()
                    ? "Compiler not found - click \"Install ...\" next to the compiler"
                    : "Compiler not found - install Clang " + LATEST_CLANG + " (clang++) or GCC " + MIN_GCC + "+ (g++) and put it on PATH");
            label.setForeground(Theme.error());
        } else if (ci.needsVisualStudio()) {
            label.setText(ci.display() + " (" + ci.target() + ") - needs Visual Studio's C++ library"
                    + (ToolchainInstaller.supported() ? ": click \"Install Clang...\" for llvm-mingw" : ""));
            label.setForeground(Theme.error());
        } else if (ci.isLatestClang()) {
            label.setText(ci.display() + "  (latest stable)");
            label.setForeground(Theme.ok());
        } else if (ci.isClang()) {
            label.setText(ci.display() + "  - latest stable is Clang " + LATEST_CLANG);
            label.setForeground(Theme.warn());
        } else if (ci.name().equals("GCC") && ci.major() >= MIN_GCC) {
            label.setText(ci.display() + "  (GNU)");
            label.setForeground(Theme.ok());
        } else if (ci.name().equals("GCC")) {
            label.setText(ci.display() + "  - GCC " + MIN_GCC + " or newer is needed for C++20");
            label.setForeground(Theme.warn());
        } else {
            label.setText(ci.display() + "  - Clang " + LATEST_CLANG + " is the recommended compiler");
            label.setForeground(Theme.warn());
        }
    }

    /** Shows which JDK a folder holds: green for JDK 17 or newer, red when there is none or it is too old. */
    static void checkJdk(String typed, JLabel label) {
        label.setText(Text.t("Checking the JDK..."));
        label.setForeground(Theme.muted());
        Object token = newCheck(label);
        new Thread(() -> {
            java.nio.file.Path home = typed.isBlank() ? null : jdkFolder(typed);
            if (typed.isBlank()) {
                String before = PREFS.get("jdk", "");
                if (before.isBlank()) home = jdkHome();
            }
            final java.nio.file.Path h = home;
            boolean ok = h != null && java.nio.file.Files.isRegularFile(jdkProgram(h, "javac"));
            int v = ok ? jdkVersion(h) : 0;
            SwingUtilities.invokeLater(() -> {
                if (!isLatestCheck(label, token)) return;
                if (!ok) {
                    label.setText("No JDK there (no bin/javac) - click Get, or install a JDK " + MIN_JDK + "+ and Detect");
                    label.setForeground(Theme.error());
                } else if (v != 0 && v < MIN_JDK) {
                    label.setText("JDK " + v + " - JDK " + MIN_JDK + " or newer is needed");
                    label.setForeground(Theme.error());
                } else {
                    label.setText("JDK " + (v == 0 ? "" : v + "  ") + "(" + h + ")");
                    label.setForeground(Theme.ok());
                }
            });
        }, "evia-jdk-check").start();
    }

    /** Shows the version of kotlinc, from build.txt next to it (starting kotlinc takes seconds). */
    static void checkKotlinc(String command, JLabel label) {
        label.setText(Text.t("Checking kotlinc..."));
        label.setForeground(Theme.muted());
        Object token = newCheck(label);
        new Thread(() -> {
            java.nio.file.Path k = command.isBlank() ? null : java.nio.file.Path.of(command.strip());
            if (k != null && !k.isAbsolute()) k = onPath(ExternalEditor.WINDOWS ? command.strip() + ".bat" : command.strip());
            String version = null;
            if (k != null && java.nio.file.Files.isRegularFile(k)) {
                try {
                    java.nio.file.Path real = k.toRealPath();
                    version = java.nio.file.Files.readString(real.getParent().getParent().resolve("build.txt")).strip()
                            .replaceFirst("-release.*", "");
                } catch (Exception e) {
                    version = "";
                }
            }
            final String v = version;
            SwingUtilities.invokeLater(() -> {
                if (!isLatestCheck(label, token)) return;
                if (v == null) {
                    label.setText("kotlinc not found - click Get to download it");
                    label.setForeground(Theme.error());
                } else {
                    label.setText("Kotlin " + v);
                    label.setForeground(Theme.ok());
                }
            });
        }, "evia-kotlinc-check").start();
    }

    /** Shows what an SDK folder has: the platform and build-tools a build needs, and adb. */
    static void checkSdk(String folder, JLabel label) {
        java.nio.file.Path sdk = java.nio.file.Path.of(folder.strip());
        java.nio.file.Path jar = AndroidBuild.platformJar(sdk), bt = AndroidBuild.buildTools(sdk), adb = AndroidBuild.adb(sdk);
        if (jar == null || bt == null) {
            label.setText("No Android SDK here" + (jar == null ? " (no platform)" : " (no build-tools)") + " - click Get Android SDK...");
            label.setForeground(Theme.error());
        } else {
            label.setText("Platform " + jar.getParent().getFileName() + ", build-tools " + bt.getFileName()
                    + (adb == null ? ", no adb (Install on Phone needs platform-tools)" : ", adb"));
            label.setForeground(adb == null ? Theme.warn() : Theme.ok());
        }
    }

    /** Fills the phone list with what adb sees. */
    private static void scanPhones(DefaultComboBoxModel<String> model, JComboBox<String> box, JLabel status, JButton scan) {
        scan.setEnabled(false);
        status.setText(Text.t("Looking for phones..."));
        new Thread(() -> {
            List<String[]> found = AndroidBuild.devices();
            SwingUtilities.invokeLater(() -> {
                Object current = box.getEditor().getItem();
                model.removeAllElements();
                model.addElement("");
                for (String[] d : found) model.addElement(d[0] + (d[2].isEmpty() ? "" : "  -  " + d[2]) + (d[1].equals("device") ? "" : "  (" + d[1] + ")"));
                model.setSelectedItem(current == null ? "" : current);
                status.setText(found.isEmpty() ? Text.t("No phones found: is USB debugging on, and adb installed (Get Android SDK)?")
                        : Text.t("Found %d phone(s) or emulator(s). Empty uses the only one connected.", found.size()));
                status.setForeground(Theme.muted());
                scan.setEnabled(true);
            });
        }, "evia-phone-scan").start();
    }

    private static JLabel small(String s) {
        // long hints wrap inside the page instead of running past its right edge
        if (s.startsWith("<html>")) s = s.replace("<br>", " ").replace("<html>", "").replace("</html>", "");
        if (s.length() > 90) {
            s = "<html><body style='width:560px'>" + s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</body></html>";
        }
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(11f));
        l.setForeground(Theme.muted());
        return l;
    }
}
