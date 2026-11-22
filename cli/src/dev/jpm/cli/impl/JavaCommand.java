package dev.jpm.cli.impl;

import dev.jpm.resolve.Install;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.json.JsoncEdit;
import dev.jpm.log.Log;
import dev.jpm.toolchain.Foojay;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JavaCommand extends Command {
    private final boolean asJvm;
    private final String self;

    public JavaCommand(Path cwd, boolean asJvm) {
        super(cwd);
        this.asJvm = asJvm;
        this.self = asJvm ? "jvm" : "jpm java";
    }

    @Override
    String name() {
        return asJvm ? "jvm" : "java";
    }

    @Override
    List<Usage> usages() {
        if (asJvm) {
            return List.of(
                    new Usage(Group.TOOLCHAIN, "jvm use <java>", "Choose the JDK that `java` runs outside projects (shims in ~/.jpm/bin)"),
                    new Usage(Group.TOOLCHAIN, "jvm current", "Show which JDK `java` runs here, and why",
                            "Which JDK `java` runs here, and why. `jpm java …` and `jvm …` are the same commands"),
                    new Usage(Group.TOOLCHAIN, "jvm exec [java] <cmd>", "Run one command with a JDK"));
        }
        return List.of(
                new Usage(Group.TOOLCHAIN, "java list [--available]", "List installed JDKs, or what can be installed",
                        "Installed JDKs (`*` marks what `java` runs here), or what can be installed (`--available [vendor]`)"),
                new Usage(Group.TOOLCHAIN, "java install <java>", "Install a JDK or GraalVM (21, temurin-17, graalvm-21, ...)",
                        "Install a JDK or GraalVM into `~/.jpm/jdks` (`21`, `temurin-17`, `graalvm-21`, ...)"),
                new Usage(Group.TOOLCHAIN, "java remove <java>", "Remove a JDK jpm installed"),
                new Usage(Group.TOOLCHAIN, "java use <java>", "Set \"java\" in the manifest (and install it)",
                        "Set `\"java\"` in the manifest, installing it if needed"));
    }

    @Override
    List<String> flags() {
        return List.of("list", "install", "remove", "use", "current", "exec", "setup", "--available");
    }

    public static final String JVM_HELP = """
            jvm - install JDKs and GraalVM and choose which one `java` runs.

            Usage: jvm <command>
              use <java>                      Make it the default for java, javac, ... outside projects
              use --project <java>            Set "java" in this project's project.jsonc instead
              current                         Show which JDK `java` runs here, and why
              install <java...>   (i)         Install JDKs into ~/.jpm/jdks
              remove <java...>                Remove JDKs that jpm installed
              list [--available [vendor]]     List installed JDKs, or what can be installed
              exec [java] <command...>        Run one command with a JDK (java is implied for -jar, .jar, -version)
              env <java>                      Print exports for this shell only: eval "$(jvm env 21)"
              home [java]                     Print the home directory of a JDK
              setup [--remove]                Create (or remove) the shims, and keep ~/.jpm/bin first on PATH

            How `java` is chosen (the shims in ~/.jpm/bin, first on PATH):
              1. inside a jpm project: its "java"     2. the default from `jvm use`
              3. otherwise the java that comes next on PATH, unchanged

            <java> is a version or vendor-version: 21, 21.0.5, temurin-17, zulu-21, graalvm-21, oracle-graalvm-21.
            Missing JDKs are installed automatically (set JPM_NO_INSTALL to turn that off).
            """;

    @Override
    public int run(List<String> args) {
        String sub = args.isEmpty() ? "list" : args.remove(0);
        return switch (sub) {
            case "list", "ls" -> list(args);
            case "install", "i", "add" -> install(args);
            case "remove", "rm", "uninstall" -> remove(args);
            case "exec", "run" -> exec(args);
            case "env" -> env(args);
            case "home" -> home(args);
            case "use" -> use(args);
            case "current", "which" -> current(args);
            case "setup" -> setup(args);
            case "help", "-h", "--help" -> {
                Log.print(JVM_HELP);
                yield 0;
            }
            default -> throw new JpmException("Unknown command `" + self + " " + sub + "`. Run `" + self + " help`.");
        };
    }

    private int list(List<String> args) {
        if (args.remove("--available")) {
            String dist = args.isEmpty() ? JavaSpec.DEFAULT_DISTRIBUTION : JavaSpec.parse(args.get(0)).installDistribution();
            List<String> versions = Foojay.available(dist);
            Log.out(Log.bold(dist + " for " + Foojay.os() + "/" + Foojay.arch()));
            for (String v : versions) Log.out("  " + v);
            return 0;
        }
        List<Jdk> all = Jdk.discover();
        if (all.isEmpty()) {
            Log.info("No JDKs found. Install one with `" + self + " install 21`.");
            return 0;
        }
        Jdk current = null;
        String reason = null;
        try {
            Shims.Choice c = Shims.choose(cwd, "java");
            current = c.jdk();
            reason = c.reason();
        } catch (JpmException ignored) {
        }
        String def = Shims.defaultSpec();
        Jdk defJdk = def == null ? null : Toolchain.find(JavaSpec.parse(def));
        for (Jdk j : all) {
            String mark = j.equals(current) ? "*" : " ";
            String tags = (j.graal() ? " native-image" : "") + (j.managed() ? " (jpm)" : "") + (j.equals(defJdk) ? " (default)" : "");
            Log.out(mark + " " + String.format("%-40s %s", j.id() + tags, j.home()));
        }
        if (current != null) Log.info(Log.dim("* what `java` runs here: " + reason));
        return 0;
    }
    private int install(List<String> args) {
        if (args.isEmpty()) throw new JpmException("Usage: " + self + " install <java>, e.g. 21, temurin-17 or graalvm-21");
        for (String a : args) {
            JavaSpec spec = JavaSpec.parse(a);
            if (spec.version() == null) throw new JpmException("Say which version to install, e.g. " + spec.installDistribution() + "-21");
            Jdk have = Toolchain.find(spec);
            if (have != null && !spec.exact()) {
                Log.step("Installed", spec + " is already available: " + have.id() + " (" + have.home() + ")");
                continue;
            }
            Toolchain.install(spec);
        }
        return 0;
    }

    private int remove(List<String> args) {
        if (args.isEmpty()) throw new JpmException("Usage: " + self + " remove <java>");
        for (String a : args) {
            List<Jdk> removed = Toolchain.remove(JavaSpec.parse(a));
            if (removed.isEmpty()) {
                throw new JpmException("No JDK installed by jpm matches " + a + ". jpm only removes JDKs in " + Jdk.managedRoot());
            }
            for (Jdk j : removed) Log.step("Removed", j.id());
        }
        return 0;
    }

    private int home(List<String> args) {
        Jdk j;
        if (args.isEmpty()) {
            JavaSpec spec = JavaSpec.any();
            try {
                spec = JavaSpec.parse(Project.find(cwd).modules.get(0).manifest.java);
            } catch (JpmException ignored) {
            }
            j = Toolchain.require(spec);
        } else {
            j = Toolchain.require(JavaSpec.parse(args.get(0)));
        }
        Log.out(j.home().toString());
        return 0;
    }

    static Map<String, String> envFor(Jdk j) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", j.home().toString());
        String path = System.getenv("PATH");
        String pathKey = "PATH";
        for (String k : System.getenv().keySet()) if (k.equalsIgnoreCase("PATH")) pathKey = k;
        env.put(pathKey, j.home().resolve("bin") + (path == null || path.isEmpty() ? "" : File.pathSeparator + path));
        if (j.graal()) env.put("GRAALVM_HOME", j.home().toString());
        return env;
    }

    private int exec(List<String> args) {
        if (args.isEmpty()) {
            throw new JpmException("Usage: " + self + " exec [java] <command...>, e.g. `jvm exec 21 -jar app.jar` or `jvm exec java -version`");
        }
        JavaSpec spec = null;
        if (looksLikeSpec(args.get(0))) spec = JavaSpec.parse(args.remove(0));
        if (!args.isEmpty() && args.get(0).equals("--")) args.remove(0);
        if (args.isEmpty()) throw new JpmException("No command given. Example: " + self + " exec 21 java -version");
        List<String> cmd = new ArrayList<>(args);
        String first = cmd.get(0);
        if (first.startsWith("-")) cmd.add(0, "java");
        else if (first.endsWith(".jar") && Files.isRegularFile(cwd.resolve(first))) {
            cmd.add(0, "-jar");
            cmd.add(0, "java");
        }
        Jdk j = spec != null ? Toolchain.require(spec) : Shims.choose(cwd, cmd.get(0)).jdk();
        if (j == null) j = Toolchain.require(JavaSpec.any());
        Path tool = j.bin(cmd.get(0));
        if (!cmd.get(0).contains("/") && !cmd.get(0).contains("\\") && Files.exists(tool)) cmd.set(0, tool.toString());
        return Shims.execTransparently(cmd, envFor(j));
    }

    static boolean looksLikeSpec(String a) {
        if (a.isEmpty() || a.startsWith("-") || a.contains("/") || a.contains("\\") || a.endsWith(".jar")) return false;
        if (Shims.TOOLS.contains(a)) return false;
        try {
            JavaSpec s = JavaSpec.parse(a);
            return s.version() != null || s.distribution() != null;
        } catch (JpmException e) {
            return false;
        }
    }

    private int current(List<String> args) {
        String tool = args.isEmpty() ? "java" : args.get(0);
        Shims.Choice c = Shims.choose(cwd, tool);
        if (c.jdk() != null) {
            Log.out(c.jdk().id() + "  " + c.jdk().home());
        } else {
            Log.out(c.systemTool().toString());
        }
        Log.info(Log.dim("chosen by " + c.reason()));
        if (!Shims.installed()) {
            Log.warn("the java shim is not installed, so `java` in your shell does not follow this yet. Run `jvm setup`.");
        }
        return 0;
    }

    private int setup(List<String> args) {
        if (args.remove("--remove")) {
            List<String> removed = Shims.remove();
            Log.step("Removed", removed.isEmpty() ? "no shims" : String.join(", ", removed) + " from " + Shims.bin());
            return 0;
        }
        boolean noShims = args.remove("--no-shims");
        if (!noShims) {
            List<String> done = Shims.install();
            Log.step("Shims", String.join(", ", done) + Log.dim(" in " + Shims.bin()));
        }
        ShellSetup.ensure(false);
        checkPathOrder();
        return 0;
    }
    static void checkPathOrder() {
        Path ours = Shims.bin().toAbsolutePath().normalize();
        String path = System.getenv("PATH");
        if (path == null) return;
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        for (String d : path.split(File.pathSeparator)) {
            if (d.isEmpty()) continue;
            Path dir = Path.of(d).toAbsolutePath().normalize();
            if (dir.equals(ours)) return;
            if (Files.exists(dir.resolve(win ? "java.exe" : "java"))) {
                if (win) {
                    Log.warn(dir + " comes before " + ours + " on PATH, so its java wins. Entries in the system PATH come"
                            + " before the user PATH on Windows; remove " + dir + " from the system PATH (System Properties"
                            + " > Environment Variables), or move it after " + ours + ".");
                } else if (ShellSetup.configured() && System.getenv("JPM_NO_MODIFY_PATH") == null) {
                    Log.info("In this shell " + dir + " still comes before " + ours + ". New terminals put jpm first,"
                            + " and so does your next prompt; to fix this one now, run:  . " + ShellSetup.envFile());
                } else {
                    Log.warn(dir + " comes before " + ours + " on PATH, so its java wins. Run `jvm setup` to keep "
                            + ours + " first.");
                }
                return;
            }
        }
        Log.info(ours + " is not on PATH in this shell yet; open a new terminal, or run:  . " + ShellSetup.envFile());
    }    private int env(List<String> args) {
        if (args.size() != 1) throw new JpmException("Usage: " + self + " env <java>");
        Jdk j = Toolchain.require(JavaSpec.parse(args.get(0)));
        boolean win = System.getProperty("os.name").toLowerCase().contains("win") && System.getenv("SHELL") == null;
        for (Map.Entry<String, String> e : envFor(j).entrySet()) {
            if (win) Log.out("set \"" + e.getKey() + "=" + e.getValue() + "\"");
            else Log.out("export " + e.getKey() + "=" + shellQuote(e.getValue()));
        }
        return 0;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private int use(List<String> args) {
        boolean project = args.remove("--project") || !self.equals("jvm");
        boolean unset = args.remove("--unset") || (args.size() == 1 && args.get(0).equals("system"));
        if (unset && !project) {
            try {
                Files.deleteIfExists(Shims.defaultFile());
            } catch (java.io.IOException e) {
                throw new JpmException(e.getMessage());
            }
            Log.step("Default", "removed; outside projects `java` is the one next on PATH");
            return 0;
        }
        if (args.size() != 1) throw new JpmException("Usage: " + self + " use <java>, e.g. " + self + " use 21");
        JavaSpec spec = JavaSpec.parse(args.get(0));
        if (spec.version() == null) throw new JpmException("Say which version, e.g. " + self + " use 21");
        String value = args.get(0).trim();
        Jdk j = Toolchain.require(spec);
        if (project) {
            Project p = Project.find(cwd);
            Project.Module m = p.moduleAt(cwd) != null ? p.moduleAt(cwd) : p.rootModule();
            String src = Fs.read(m.manifest.file);
            Fs.write(m.manifest.file, JsoncEdit.set(src, List.of(), "java", Jsonc.quote(value)));
            Log.step("Using", value + " (" + j.id() + ") in " + p.dir.relativize(m.manifest.file).toString().replace('\\', '/'));
        } else {
            Fs.write(Shims.defaultFile(), value + "\n");
            Log.step("Default", value + " (" + j.id() + ") for java, javac, ... outside jpm projects");
            Manifest here = Shims.projectManifest(cwd);
            if (here != null && here.java != null) {
                Log.info(Log.dim("Here the project's \"java\": \"" + here.java + "\" still wins; `jvm use --project " + value + "` changes it."));
            }
        }
        if (!Shims.installed()) {
            try {
                Shims.install();
                Log.step("Shims", "java, javac, ... in " + Shims.bin());
            } catch (JpmException e) {
                Log.warn(e.getMessage());
            }
        }
        ShellSetup.ensure(false);
        checkPathOrder();
        return 0;
    }}
