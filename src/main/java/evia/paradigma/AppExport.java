package evia.paradigma;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Export App: a built program as something to hand to other people, for any Linux on x86-64. Everything is
 * written here, in Java (tar, gzip and the ar format of .deb files), so nothing else needs to be installed:
 * <ul>
 *   <li>the executable itself: static, system calls only, it runs on any x86-64 Linux as it is;</li>
 *   <li>a portable .tar.gz: the program, a menu entry and an icon, and install.sh / uninstall.sh, which install it
 *       for the current user (~/.local) without root, or for everyone with --system;</li>
 *   <li>a .deb for Ubuntu and Debian: /usr/bin/COMMAND, a menu entry and an icon.</li>
 * </ul>
 * A block program talks through Print and Input, so its menu entry opens a terminal, and the launcher keeps that
 * terminal open when the program ends until Enter is pressed.
 */
final class AppExport {
    private AppExport() {}

    /** What the app is called: its name in menus, its command, version and one line about it. */
    record Options(String name, String command, String version, String description, boolean exe, boolean tarball, boolean deb) {}

    /** A command name Linux and Debian accept: lower case letters, digits, '-' and '.', starting with a letter or digit. */
    static String commandFor(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c < 128 && Character.isLetterOrDigit(c)) sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.length() < 2 ? "evia-app" : sb.toString();
    }

    static boolean validCommand(String c) {
        return c != null && c.matches("[a-z0-9][a-z0-9.+-]+");
    }

    static boolean validVersion(String v) {
        return v != null && v.matches("[0-9][A-Za-z0-9.+~]*");
    }

    /** Writes the chosen forms of the app into dir and returns the files written. */
    static List<Path> export(byte[] executable, Options o, Path dir) throws IOException {
        if (!validCommand(o.command())) throw new IOException("'" + o.command() + "' is not a usable command name: lower-case"
                + " letters, digits and '-', at least two characters, e.g. " + commandFor(o.name()));
        if (!validVersion(o.version())) throw new IOException("'" + o.version() + "' is not a version: it starts with a digit, e.g. 1.0");
        Files.createDirectories(dir);
        List<Path> written = new ArrayList<>();
        if (o.exe()) {
            Path p = dir.resolve(o.command());
            Files.write(p, executable);
            p.toFile().setExecutable(true, false);
            written.add(p);
        }
        if (o.tarball()) {
            Path p = dir.resolve(o.command() + "-" + o.version() + "-linux-x86_64.tar.gz");
            Files.write(p, tarball(executable, o));
            written.add(p);
        }
        if (o.deb()) {
            Path p = dir.resolve(o.command() + "_" + o.version() + "_amd64.deb");
            Files.write(p, deb(executable, o));
            written.add(p);
        }
        return written;
    }

    // ================================================================ the pieces

    private static byte[] icon() {
        try (InputStream in = AppExport.class.getResourceAsStream("icons/icon-256.png")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Runs the program in its terminal, then waits for Enter so the window does not vanish with the output.
     * {@code program} is the shell word for the program, quoted as it needs to be.
     */
    private static String launcher(String program) {
        return "#!/bin/sh\n"
                + "# Made with EVIA Paradigma Native: runs the program, then keeps the terminal open.\n"
                + program + " \"$@\"\n"
                + "code=$?\n"
                + "printf '\\n[the program has ended (exit code %s): press Enter to close] ' \"$code\"\n"
                + "read dummy\n"
                + "exit $code\n";
    }

    private static String desktop(Options o, String exec, String icon) {
        return "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=" + o.name().replace('\n', ' ') + "\n"
                + "Comment=" + o.description().replace('\n', ' ') + "\n"
                + "Exec=" + exec + "\n"
                + "Icon=" + icon + "\n"
                + "Terminal=true\n"
                + "Categories=Utility;\n";
    }

    // ================================================================ the portable .tar.gz

    private static byte[] tarball(byte[] exe, Options o) throws IOException {
        String top = o.command() + "-" + o.version() + "-linux-x86_64/";
        String c = o.command();
        Tar tar = new Tar();
        tar.dir(top);
        tar.file(top + c, exe, 0755);
        tar.file(top + "launch.sh", launcher("\"$(dirname \"$(readlink -f \"$0\")\")/" + c + "\"")
                .getBytes(StandardCharsets.UTF_8), 0755);
        byte[] png = icon();
        if (png != null) tar.file(top + c + ".png", png, 0644);
        tar.file(top + "install.sh", text("""
                #!/bin/sh
                # Installs %NAME% for you (~/.local), or for everyone with --system (needs sudo).
                # Afterwards: the command %CMD%, and %NAME% in the applications menu (it opens a terminal).
                set -e
                here=$(dirname "$(readlink -f "$0")")
                if [ "$1" = "--system" ]; then
                    bin=/usr/local/bin; share=/usr/local/share; lib=/usr/local/lib/%CMD%
                else
                    bin=$HOME/.local/bin; share=$HOME/.local/share; lib=$HOME/.local/lib/%CMD%
                fi
                mkdir -p "$bin" "$lib" "$share/applications" "$share/icons/hicolor/256x256/apps"
                cp "$here/%CMD%" "$bin/%CMD%"
                chmod 755 "$bin/%CMD%"
                cat > "$lib/launch.sh" <<LAUNCH
                #!/bin/sh
                "$bin/%CMD%" "\\$@"
                code=\\$?
                printf '\\n[the program has ended (exit code %s): press Enter to close] ' "\\$code"
                read dummy
                exit \\$code
                LAUNCH
                chmod 755 "$lib/launch.sh"
                [ -f "$here/%CMD%.png" ] && cp "$here/%CMD%.png" "$share/icons/hicolor/256x256/apps/%CMD%.png"
                sed "s|^Exec=.*|Exec=$lib/launch.sh|" "$here/%CMD%.desktop" > "$share/applications/%CMD%.desktop"
                command -v update-desktop-database > /dev/null 2>&1 && update-desktop-database -q "$share/applications" || true
                echo "Installed %NAME%: $bin/%CMD%"
                case ":$PATH:" in *":$bin:"*) ;; *) echo "($bin is not on your PATH yet: log in again, or run $bin/%CMD%)";; esac
                """, o), 0755);
        tar.file(top + "uninstall.sh", text("""
                #!/bin/sh
                # Removes what install.sh installed (--system: the copy for everyone).
                if [ "$1" = "--system" ]; then
                    bin=/usr/local/bin; share=/usr/local/share; lib=/usr/local/lib/%CMD%
                else
                    bin=$HOME/.local/bin; share=$HOME/.local/share; lib=$HOME/.local/lib/%CMD%
                fi
                rm -f "$bin/%CMD%" "$share/applications/%CMD%.desktop" "$share/icons/hicolor/256x256/apps/%CMD%.png"
                rm -rf "$lib"
                echo "Removed %NAME%."
                """, o), 0755);
        tar.file(top + c + ".desktop", desktop(o, "launch.sh", c).getBytes(StandardCharsets.UTF_8), 0644);
        tar.file(top + "README.txt", text("""
                %NAME% %VER%
                %DESC%

                A program for Linux on 64-bit Intel and AMD processors (x86-64). It needs nothing else: no libraries,
                no Java, no runtime. Made with EVIA Paradigma Native.

                Run it here:            ./%CMD%
                Install it for you:     ./install.sh             (the command %CMD% and a menu entry, no root)
                Install for everyone:   sudo ./install.sh --system
                Remove it:              ./uninstall.sh  (or: sudo ./uninstall.sh --system)
                """, o), 0644);
        return gzip(tar.finish());
    }

    private static byte[] text(String template, Options o) {
        return template.replace("%NAME%", o.name()).replace("%CMD%", o.command()).replace("%VER%", o.version())
                .replace("%DESC%", o.description()).getBytes(StandardCharsets.UTF_8);
    }

    // ================================================================ the .deb

    private static byte[] deb(byte[] exe, Options o) throws IOException {
        String c = o.command();
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, Integer> modes = new LinkedHashMap<>();
        files.put("usr/bin/" + c, exe);
        modes.put("usr/bin/" + c, 0755);
        files.put("usr/lib/" + c + "/launch.sh", launcher("/usr/bin/" + c).getBytes(StandardCharsets.UTF_8));
        modes.put("usr/lib/" + c + "/launch.sh", 0755);
        files.put("usr/share/applications/" + c + ".desktop", desktop(o, "/usr/lib/" + c + "/launch.sh", c).getBytes(StandardCharsets.UTF_8));
        byte[] png = icon();
        if (png != null) files.put("usr/share/icons/hicolor/256x256/apps/" + c + ".png", png);

        Tar data = new Tar();
        List<String> dirs = new ArrayList<>();
        long size = 0;
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            String path = f.getKey();
            for (int i = path.indexOf('/'); i > 0; i = path.indexOf('/', i + 1)) {
                String d = "./" + path.substring(0, i + 1);
                if (!dirs.contains(d)) {
                    dirs.add(d);
                    data.dir(d);
                }
            }
            data.file("./" + path, f.getValue(), modes.getOrDefault(path, 0644));
            size += f.getValue().length;
            sums.append(md5(f.getValue())).append("  ").append(path).append('\n');
        }
        String control = "Package: " + c + "\n"
                + "Version: " + o.version() + "\n"
                + "Architecture: amd64\n"
                + "Maintainer: " + o.name() + " <" + c + "@localhost>\n"
                + "Installed-Size: " + Math.max(1, (size + 1023) / 1024) + "\n"
                + "Section: misc\n"
                + "Priority: optional\n"
                + "Description: " + o.description().replace('\n', ' ') + "\n"
                + " " + o.name() + ", made with EVIA Paradigma Native: a program that needs no libraries.\n";
        Tar ctl = new Tar();
        ctl.file("./control", control.getBytes(StandardCharsets.UTF_8), 0644);
        ctl.file("./md5sums", sums.toString().getBytes(StandardCharsets.UTF_8), 0644);

        ByteArrayOutputStream ar = new ByteArrayOutputStream();
        ar.writeBytes("!<arch>\n".getBytes(StandardCharsets.US_ASCII));
        arMember(ar, "debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII));
        arMember(ar, "control.tar.gz", gzip(ctl.finish()));
        arMember(ar, "data.tar.gz", gzip(data.finish()));
        return ar.toByteArray();
    }

    private static void arMember(ByteArrayOutputStream ar, String name, byte[] content) {
        String header = String.format("%-16s%-12d%-6d%-6d%-8s%-10d`\n", name, System.currentTimeMillis() / 1000, 0, 0, "100644", content.length);
        ar.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        ar.writeBytes(content);
        if (content.length % 2 == 1) ar.write('\n');
    }

    private static String md5(byte[] b) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("MD5").digest(b)) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] gzip(byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream z = new GZIPOutputStream(out)) {
            z.write(b);
        }
        return out.toByteArray();
    }

    /** A tar archive (POSIX ustar), owned by root, dated now. */
    private static final class Tar {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final long now = System.currentTimeMillis() / 1000;

        void dir(String name) {
            header(name.endsWith("/") ? name : name + "/", 0, 0755, '5');
        }

        void file(String name, byte[] content, int mode) {
            header(name, content.length, mode, '0');
            out.writeBytes(content);
            pad();
        }

        private void header(String name, long size, int mode, char type) {
            byte[] h = new byte[512];
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            if (n.length > 100) {   // ustar: a prefix of up to 155 bytes, split at a '/'
                int cut = name.lastIndexOf('/', Math.min(name.length() - 2, 155));
                byte[] prefix = name.substring(0, cut).getBytes(StandardCharsets.UTF_8);
                n = name.substring(cut + 1).getBytes(StandardCharsets.UTF_8);
                System.arraycopy(prefix, 0, h, 345, Math.min(prefix.length, 155));
            }
            System.arraycopy(n, 0, h, 0, Math.min(n.length, 100));
            octal(h, 100, 8, mode);
            octal(h, 108, 8, 0);
            octal(h, 116, 8, 0);
            octal(h, 124, 12, size);
            octal(h, 136, 12, now);
            for (int i = 148; i < 156; i++) h[i] = ' ';
            h[156] = (byte) type;
            byte[] magic = "ustar\u000000".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(magic, 0, h, 257, magic.length);
            System.arraycopy("root".getBytes(StandardCharsets.US_ASCII), 0, h, 265, 4);
            System.arraycopy("root".getBytes(StandardCharsets.US_ASCII), 0, h, 297, 4);
            long sum = 0;
            for (byte x : h) sum += x & 0xFF;
            octal(h, 148, 7, sum);
            out.writeBytes(h);
        }

        private static void octal(byte[] h, int at, int len, long v) {
            String s = String.format("%0" + (len - 1) + "o", v);
            byte[] b = s.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(b, 0, h, at, len - 1);
            h[at + len - 1] = 0;
        }

        private void pad() {
            while (out.size() % 512 != 0) out.write(0);
        }

        byte[] finish() {
            out.writeBytes(new byte[1024]);
            return out.toByteArray();
        }
    }
}
