package evia.paradigma;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The packages EVIA Paradigma is made of beyond its installer: the application itself (newer versions
 * of it) and the toolchains, each one downloaded from the package repository on its own, installed into
 * the user's data folder, updated in place and removed again, without reinstalling anything.
 *
 * <p>The repository is a folder of static files with an {@code index.json} at its top (see
 * {@code scripts/build-packages.sh}), by default at {@link #DEFAULT_REPO}. Every file is checked against
 * the SHA-256 the index gives before it is used. A package is unpacked next to the old one and only then
 * swapped in, so a broken download never leaves a half-installed tool behind.
 *
 * <p>Layout of the data folder ({@code ~/.evia-paradigma/packages}, {@code %LOCALAPPDATA%\EVIA
 * Paradigma\packages} on Windows):
 * <pre>
 *   installed.properties          id = version of each installed package
 *   app/evia-paradigma-0.9.1.jar  newer versions of the application (the installer's own copy stays as it is)
 *   arm-none-eabi-gcc/bin/...     a toolchain: its bin/ at the top, as {@link Bundled} expects
 * </pre>
 */
final class Packages {
    private Packages() {}

    /** Where the updates come from: EVIA Paradigma Native's own index on the EVIA server (dell). */
    static final String DEFAULT_REPO = "https://evia.am/native/";

    /** The application's jars: evia-paradigma-native-1.2.0.jar. */
    static final String JAR_PREFIX = "evia-paradigma-native-";

    /** The id of the application package. */
    static final String APP = "app";

    /** The repository: the system property evia.packages (for checks and mirrors), else Settings, else the default. */
    static String repo() {
        String r = System.getProperty("evia.packages");
        if (r == null || r.isBlank()) r = java.util.prefs.Preferences.userNodeForPackage(Packages.class).get("nativePackagesRepo", DEFAULT_REPO);
        return r.endsWith("/") ? r : r + "/";
    }

    static void setRepo(String url) {
        String u = url == null || url.isBlank() ? DEFAULT_REPO : url.trim();
        java.util.prefs.Preferences.userNodeForPackage(Packages.class).put("nativePackagesRepo", u);
    }

    /** This computer, as the index names it: linux-x64, linux-arm64, windows-x64, macos-arm64 or macos-x64. */
    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) return "windows-x64";
        if (os.contains("mac")) return arm ? "macos-arm64" : "macos-x64";
        return arm ? "linux-arm64" : "linux-x64";
    }

    /** The data folder the packages live in. */
    static Path home() {
        String override = System.getProperty("evia.packages.home");
        if (override != null && !override.isBlank()) return Path.of(override);
        String base = ExternalEditor.WINDOWS ? System.getenv("LOCALAPPDATA") : null;
        if (base == null) base = System.getProperty("user.home");
        return Path.of(base, ExternalEditor.WINDOWS ? "EVIA Paradigma Native" : ".evia-paradigma-native", "packages");
    }

    /** The folder of an installed package. */
    static Path dir(String id) {
        return home().resolve(id);
    }

    // ================================================================ the index

    /**
     * A package, as the index describes it for this computer.
     *
     * @param group    what it is for, e.g. "Application", "STM32", "AVR", "FPGA"
     * @param url      the file for this computer (absolute), or null when there is none for it
     * @param unpacked how much room it takes once installed (bytes; 0 if the index does not say)
     */
    record Pkg(String id, String name, String group, String about, String version, String url, long size,
               String sha256, long unpacked, boolean recommended) {
        boolean app() {
            return APP.equals(id);
        }

        boolean available() {
            return url != null;
        }
    }

    /** The packages of the repository that this computer can use, in the index's order. */
    static List<Pkg> fetchIndex() throws IOException {
        String base = repo();
        String json = text(base + "index.json");
        Map<String, Object> root;
        try {
            root = Json.obj(Json.parse(json));
        } catch (IllegalArgumentException e) {
            throw new IOException("the package index at " + base + " is not readable: " + e.getMessage());
        }
        if (Json.num(root, "format", 0) != 1) throw new IOException("the package index at " + base + " is a newer format: update EVIA Paradigma");
        String plat = platform();
        List<Pkg> out = new ArrayList<>();
        for (Object o : Json.list(root.get("packages"))) {
            Map<String, Object> p = Json.obj(o);
            String id = Json.str(p, "id", null);
            if (id == null || !id.matches("[a-z0-9][a-z0-9._-]*")) continue;    // also keeps ids out of paths
            Map<String, Object> files = Json.obj(p.get("files"));
            Map<String, Object> f = Json.obj(files.containsKey(plat) ? files.get(plat) : files.get("any"));
            String file = Json.str(f, "file", null);
            String version = Json.str(f, "version", Json.str(p, "version", "?"));
            out.add(new Pkg(id, Json.str(p, "name", id), Json.str(p, "group", ""), Json.str(p, "about", ""), version,
                    file == null ? null : URI.create(base).resolve(file).toString(), Json.num(f, "size", 0),
                    Json.str(f, "sha256", ""), Json.num(f, "unpacked", 0), Boolean.TRUE.equals(p.get("recommended"))));
        }
        return out;
    }

    // ================================================================ what is installed

    private static Path record() {
        return home().resolve("installed.properties");
    }

    /** id -> version of every installed package. */
    static synchronized Map<String, String> installed() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(record(), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return Map.of();
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k));
        return m;
    }

    private static synchronized void remember(String id, String version) throws IOException {
        Properties p = new Properties();
        p.putAll(installed());
        if (version == null) p.remove(id);
        else p.setProperty(id, version);
        Files.createDirectories(home());
        Path tmp = record().resolveSibling("installed.properties.tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "EVIA Paradigma packages: id = installed version");
        }
        Files.move(tmp, record(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** True when the index has something newer (or different) than what is installed; the app only when newer. */
    static boolean hasUpdate(Pkg p) {
        if (!p.available()) return false;
        if (p.app()) return compare(p.version(), runningVersion()) > 0;
        String have = installed().get(p.id());
        return have != null && !have.equals(p.version());
    }

    /** The version running now (a newer installed one when the installer's copy handed over to it). */
    static String runningVersion() {
        return App.VERSION;
    }

    /** Compares versions part by part as numbers: 0.10.0 is newer than 0.9.3. */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Long.compare(p, q);
        }
        return 0;
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ================================================================ the application's newer versions

    /** The newest installed application jar that is newer than {@code than}, or null. */
    static Path newerApp(String than) {
        Path dir = dir(APP);
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches(java.util.regex.Pattern.quote(JAR_PREFIX) + "[0-9][0-9.]*\\.jar"))
                    .filter(p -> !Files.exists(p.resolveSibling(p.getFileName() + ".broken")))
                    .filter(p -> compare(appVersion(p), than) > 0)
                    .max(Comparator.comparing(Packages::appVersion, Packages::compare))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static String appVersion(Path jar) {
        String n = jar.getFileName().toString();
        return n.substring(JAR_PREFIX.length(), n.length() - ".jar".length());
    }

    /** Marks a newer application jar that would not start, so the next start does not try it again. */
    static void markBroken(Path jar) {
        try {
            Files.writeString(jar.resolveSibling(jar.getFileName() + ".broken"), "did not start\n");
        } catch (IOException ignored) {
            // the next start tries it again
        }
    }

    // ================================================================ install and remove

    interface Progress {
        void report(String text, long done, long total);

        boolean cancelled();
    }

    /** Downloads, checks and installs one package; returns the folder (or, for the application, the jar). */
    static Path install(Pkg p, Progress progress) throws IOException, InterruptedException {
        if (!p.available()) throw new IOException(p.name() + " has no file for " + platform());
        progress.report("Downloading " + p.name() + " " + p.version() + "...", 0, p.size());
        Path file = Toolchains.download(p.url(), (done, total) -> progress.report(
                "Downloading " + p.name() + "... " + mb(done) + (total > 0 ? " of " + mb(total) : ""), done, total), progress::cancelled);
        try {
            progress.report("Checking " + p.name() + "...", -1, -1);
            if (!p.sha256().isEmpty()) {
                String got = sha256(file);
                if (!got.equalsIgnoreCase(p.sha256())) {
                    throw new IOException("the download of " + p.name() + " is damaged (SHA-256 " + got + ", the index says "
                            + p.sha256() + "): nothing was changed. Try again.");
                }
            }
            Files.createDirectories(home());
            if (p.app()) {
                Path dir = Files.createDirectories(dir(APP));
                Path jar = dir.resolve(JAR_PREFIX + p.version() + ".jar");
                Files.move(file, jar, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(jar.resolveSibling(jar.getFileName() + ".broken"));
                pruneApps(dir, p.version());
                remember(APP, p.version());
                return jar;
            }
            progress.report("Unpacking " + p.name() + "...", -1, -1);
            Path fresh = home().resolve(p.id() + ".new"), old = home().resolve(p.id() + ".old"), target = dir(p.id());
            deleteTree(fresh);
            Files.createDirectories(fresh);
            Toolchains.unpack(rename(file, p.url()), fresh);
            Path inner = single(fresh);                    // an archive with one top folder: take that folder
            deleteTree(old);
            if (Files.exists(target)) Files.move(target, old);
            Files.move(inner, target);
            if (!inner.equals(fresh)) deleteTree(fresh);
            deleteTree(old);
            if (!ExternalEditor.WINDOWS) executable(target.resolve("bin"));
            remember(p.id(), p.version());
            return target;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Removes an installed package (the application's newer jars, or a toolchain's folder). */
    static void remove(String id) throws IOException {
        deleteTree(dir(id));
        remember(id, null);
    }

    /** Keeps the newest application jar and the one before it (to go back to), deletes the rest. */
    private static void pruneApps(Path dir, String keep) throws IOException {
        List<Path> jars;
        try (Stream<Path> s = Files.list(dir)) {
            jars = s.filter(p -> p.getFileName().toString().matches(java.util.regex.Pattern.quote(JAR_PREFIX) + "[0-9][0-9.]*\\.jar"))
                    .sorted(Comparator.comparing(Packages::appVersion, Packages::compare).reversed()).toList();
        }
        for (int i = 2; i < jars.size(); i++) {
            if (appVersion(jars.get(i)).equals(keep)) continue;
            try {
                Files.deleteIfExists(jars.get(i));
            } catch (IOException e) {
                // still in use (Windows keeps a running jar locked): it goes the next time
            }
        }
    }

    /** The download keeps the archive's own extension, which is how it is unpacked. */
    private static Path rename(Path file, String url) throws IOException {
        String name = url.substring(url.lastIndexOf('/') + 1);
        Path to = file.resolveSibling("evia-pkg-" + System.nanoTime() + "-" + name);
        return Files.move(file, to);
    }

    private static Path single(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> all = s.toList();
            return all.size() == 1 && Files.isDirectory(all.get(0)) ? all.get(0) : dir;
        }
    }

    private static void executable(Path bin) throws IOException {
        if (!Files.isDirectory(bin)) return;
        try (Stream<Path> s = Files.list(bin)) {
            s.filter(Files::isRegularFile).forEach(f -> f.toFile().setExecutable(true));
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static String mb(long bytes) {
        return bytes < 1 << 20 ? (bytes + 1023) / 1024 + " KB" : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static String text(String url) throws IOException {
        if (!url.startsWith("http")) {                       // a local folder, for the checks
            try (InputStream in = URI.create(url).toURL().openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", "EVIA-Paradigma/" + App.VERSION + " (" + platform() + ")");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("the package server answered " + code + " for " + url);
            try (InputStream in = c.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
