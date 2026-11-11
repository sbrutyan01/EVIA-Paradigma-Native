package evia.paradigma;

import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.io.File;

/**
 * Entry point.
 * <ul>
 *   <li>{@code --run}, {@code --gen}, ... : command-line mode (see {@link Cli})</li>
 *   <li>a {@code .evia} file : open it in the editor (Windows/Linux file associations pass it this way)</li>
 *   <li>nothing : start the editor with the example program</li>
 * </ul>
 */
public final class App {
    /** Released version, shown by --version and in the About dialog. */
    public static final String VERSION = "1.3.0";

    /**
     * EVIA Paradigma Native: the same editor, but no code generation. Build makes an executable straight from the
     * blocks (NativeCompiler). Its installers set -Devia.edition=native; "--edition native" does the same.
     */
    static boolean nativeEdition() {
        return true;   // EVIA Paradigma Native: a project of its own, forked from EVIA Paradigma 0.11.0
    }

    /** The name of this edition, for titles and dialogs. */
    static String name() {
        return nativeEdition() ? "EVIA Paradigma Native" : "EVIA Paradigma";
    }

    public static void main(String[] args) {
        if (args.length >= 2 && args[0].equals("--edition")) {
            System.setProperty("evia.edition", args[1]);
            args = java.util.Arrays.copyOfRange(args, 2, args.length);
        }
        if (handOver(args)) return;   // a newer version installed from evia.am/native starts instead
        if (args.length > 0 && args[0].startsWith("-")) {
            System.setProperty("java.awt.headless", "true");
            System.exit(Cli.run(args));
        }
        File toOpen = args.length > 0 ? new File(args[0]) : null;
        SwingUtilities.invokeLater(() -> {
            Theme.init();
            MainFrame f = new MainFrame();
            f.setVisible(true);
            if (toOpen != null) f.openFile(toOpen);
            // the first start of an installed copy: pick the toolchains; later starts: a quiet check for updates
            // no toolchains to fetch (the compiler is built in): a quiet check for a newer version on evia.am
            PackagesDialog.checkInBackground(f::notice);
            // macOS delivers double-clicked documents through an event instead of arguments
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
                Desktop.getDesktop().setOpenFileHandler(e -> {
                    if (!e.getFiles().isEmpty()) SwingUtilities.invokeLater(() -> f.openFile(e.getFiles().get(0)));
                });
            }
        });
    }

    /**
     * Self-update: when a newer version of the application has been installed as a package (Settings,
     * Packages and Updates), the copy that came with the installer starts that one instead, in a class
     * loader of its own, on the same Java runtime. True when it did; if the newer one fails to start it is
     * marked broken (and skipped from then on) and this version starts as usual.
     */
    private static boolean handOver(String[] args) {
        if (System.getProperty("evia.handedOver") != null || "0".equals(System.getenv("EVIA_UPDATES"))) return false;
        java.nio.file.Path jar = Packages.newerApp(VERSION);
        if (jar == null) return false;
        System.setProperty("evia.handedOver", VERSION);
        try {
            java.net.URLClassLoader loader = new java.net.URLClassLoader("evia-" + Packages.appVersion(jar),
                    new java.net.URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> app = Class.forName("evia.paradigma.App", true, loader);
            app.getMethod("main", String[].class).invoke(null, (Object) args);
            return true;
        } catch (Throwable t) {
            System.err.println("EVIA Paradigma " + Packages.appVersion(jar) + " did not start (" + t + "); starting " + VERSION);
            Packages.markBroken(jar);
            Thread.currentThread().setContextClassLoader(App.class.getClassLoader());
            return false;
        }
    }
}
