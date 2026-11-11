package evia.paradigma;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * A project folder:
 * <pre>
 * MyProject/
 *   MyProject.eviaproj   name, main program, version
 *   main.evia            the main program
 *   modules/             class modules (*.eviam) the program imports
 *   build/               generated C/C++ code and compiled programs (ignored by git)
 *   README.md
 *   .gitignore
 * </pre>
 */
final class Project {
    static final String EXT = ".eviaproj";

    final Path dir;
    final Path file;
    final Properties props = new Properties();

    private Project(Path file) {
        this.file = file.toAbsolutePath().normalize();
        this.dir = this.file.getParent();
    }

    String name() {
        return props.getProperty("name", dir.getFileName().toString());
    }

    Path mainProgram() {
        return dir.resolve(props.getProperty("main", "main.evia"));
    }

    Path modulesDir() {
        return dir.resolve("modules");
    }

    Path buildDir() {
        return dir.resolve("build");
    }

    /** Where Generate code writes the program, e.g. build/MyProject.cpp or build/MyProject.c. */
    Path codeFile(Target t) {
        return buildDir().resolve(safeName(name()) + t.ext());
    }

    /** The project's language and generation mode, or null when it uses the application's choice. */
    Target target() {
        String k = props.getProperty("target");
        return k == null ? null : Target.parse(k);
    }

    void setTarget(Target t) throws IOException {
        if (t.key().equals(props.getProperty("target"))) return;
        props.setProperty("target", t.key());
        save();
    }

    static String safeName(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        return s.isEmpty() ? "program" : s;
    }

    static boolean validName(String name) {
        return name != null && !name.isBlank() && !name.matches(".*[\\\\/:*?\"<>|].*") && !name.startsWith(".");
    }

    /** Creates the folder skeleton (everything except the main program, which the caller saves). */
    static Project create(Path parent, String name) throws IOException {
        Path dir = parent.resolve(name);
        if (Files.exists(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                if (s.findAny().isPresent()) throw new IOException("the folder " + dir + " already exists and is not empty");
            }
        }
        Files.createDirectories(dir.resolve("modules"));
        Files.createDirectories(dir.resolve("build"));
        Project p = new Project(dir.resolve(safeName(name) + EXT));
        p.props.setProperty("name", name);
        p.props.setProperty("main", "main.evia");
        p.props.setProperty("version", "0.1.0");
        p.props.setProperty("created-with", App.name() + " " + App.VERSION);
        p.save();
        Files.writeString(dir.resolve(".gitignore"), """
                # EVIA Paradigma: generated code and compiled programs
                build/
                *.exe
                *.o
                *.obj
                .DS_Store
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("README.md"), """
                # %s

                A visual program made with [EVIA Paradigma Native](https://github.com/sbrutyan01/EVIA-Paradigma-Native).

                * `main.evia` - the main program (open the project folder or `%s` in EVIA Paradigma)
                * `modules/` - class modules (`*.eviam`) used by the program
                * `build/` - generated C or C++ code (not committed)

                Run it with the **Run** button, or from a terminal:

                ```
                evia --run main.evia
                evia --gen main.evia -o build/%s.cpp                 (C++20)
                evia --gen main.evia --lang c11 -o build/%s.c        (C11; also c99, c17, c++98 ... c++20)
                ```
                """.formatted(name, p.file.getFileName(), safeName(name), safeName(name)), StandardCharsets.UTF_8);
        return p;
    }

    void save() throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(w, "EVIA Paradigma project");
        }
    }

    static Project load(Path file) throws IOException {
        Project p = new Project(file);
        try (Reader r = Files.newBufferedReader(p.file, StandardCharsets.UTF_8)) {
            p.props.load(r);
        }
        return p;
    }

    /** The project in a folder (its *.eviaproj file), or null. */
    static Project inFolder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            Path f = s.filter(x -> x.getFileName().toString().endsWith(EXT)).sorted().findFirst().orElse(null);
            return f == null ? null : load(f);
        }
    }

    /** The project a program file belongs to: a *.eviaproj next to it whose main program it is. */
    static Project ofProgram(Path program) {
        try {
            Project p = inFolder(program.toAbsolutePath().getParent());
            return p != null && p.mainProgram().equals(program.toAbsolutePath().normalize()) ? p : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Folder where new projects go by default: ~/EVIA Projects. */
    static Path defaultParent() {
        return Path.of(System.getProperty("user.home"), "EVIA Projects");
    }
}
