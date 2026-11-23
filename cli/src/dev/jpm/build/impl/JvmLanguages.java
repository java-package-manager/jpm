package dev.jpm.build.impl;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.build.Builder;
import dev.jpm.build.Compiler;
import dev.jpm.log.Log;
import dev.jpm.resolve.Resolver;
import dev.jpm.toolchain.Jdk;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class JvmLanguages {
    private JvmLanguages() {
    }

    public enum Language {
        GROOVY("Groovy", "groovy", ".groovy"), SCALA("Scala", "scala", ".scala");

        public final String title, key, ext;

        Language(String title, String key, String ext) {
            this.title = title;
            this.key = key;
            this.ext = ext;
        }
    }

    public record Request(Language lang, String label, String version, Jdk jdk, int release, List<Path> sourceRoots, List<Path> excludes,
                          List<Path> classpath, Path outDir, Path stateFile) {}

    private static List<Path> files(List<Path> roots, List<Path> excludes, String ext) {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) for (Path p : Fs.files(root, ext)) if (excludes.stream().noneMatch(p::startsWith)) out.add(p);
        return out;
    }

    public static boolean compile(Builder b, Request r) {
        List<Path> own = files(r.sourceRoots, r.excludes, r.lang.ext);
        if (own.isEmpty()) {
            Fs.deleteTree(r.outDir);
            try {
                Files.deleteIfExists(r.stateFile);
            } catch (IOException ignored) {
            }
            return false;
        }
        if (r.version == null) {
            throw new JpmException(r.label + " has " + r.lang.title + " sources (" + own.get(0) + "), but no \"" + r.lang.key + "\" version in its "
                    + Manifest.FILE + ". Add one, e.g. \"" + r.lang.key + "\": \"" + example(r.lang) + "\", and its runtime library, "
                    + runtimeLibrary(r.lang, example(r.lang)) + ", to \"runtime\"");
        }
        List<Path> java = files(r.sourceRoots, r.excludes, ".java");
        Hash h = new Hash().add(r.lang.key + "-v1").add(r.version).add(r.jdk.id()).add(Integer.toString(r.release));
        for (Path p : r.classpath) h.add("cp " + p + " " + stamp(p));
        for (Path p : own) h.add(p + " " + Hash.sha256(p));
        for (Path p : java) h.add(p + " " + Hash.sha256(p));
        String key = h.hex();
        if (Files.isDirectory(r.outDir) && Files.isRegularFile(r.stateFile) && Fs.read(r.stateFile).equals(key)) return false;

        String library = runtimeLibrary(r.lang, r.version);
        String libraryJar = library.substring(library.indexOf(':') + 1);
        if (r.classpath.stream().noneMatch(p -> p.getFileName().toString().startsWith(libraryJar + "-"))) {
            throw new JpmException(r.label + ": the " + r.lang.title + " runtime library is not on the classpath. Add \"" + library + "\": \""
                    + r.version + "\" to \"runtime\"");
        }
        String[] coords = compiler(r.lang, r.version).split(":");
        List<Path> tool = b.resolveTool(List.of(new Resolver.Root(r.lang.key + "c " + r.version, DepKey.maven(coords[0], coords[1]), r.version)),
                null, dev.jpm.resolve.Graph.Walk.RUNTIME);

        Path tmp = r.outDir.resolveSibling(r.outDir.getFileName() + ".tmp");
        Fs.deleteTree(tmp);
        Fs.mkdirs(tmp);
        List<String> args = new ArrayList<>();
        String target = r.release <= 8 ? "8" : Integer.toString(r.release);
        switch (r.lang) {
            case GROOVY -> {
                args.add("-d");
                args.add(tmp.toString());
                if (!r.classpath.isEmpty()) {
                    args.add("-cp");
                    args.add(Compiler.join(r.classpath));
                }
                if (!java.isEmpty()) {
                    args.add("-j");
                    Path stubs = r.outDir.resolveSibling(r.outDir.getFileName() + ".stubs");
                    Fs.deleteTree(stubs);
                    args.add("--temp=" + stubs);
                }
            }
            case SCALA -> {
                args.add("-d");
                args.add(tmp.toString());
                args.add("-classpath");
                args.add(Compiler.join(r.classpath));
                if (r.version.startsWith("3.")) {
                    args.add("-java-output-version");
                    args.add(target);
                } else {
                    args.add("-release");
                    args.add(target);
                }
            }
        }
        for (Path p : own) args.add(p.toString());
        for (Path p : java) args.add(p.toString());
        Path argFile = r.stateFile.resolveSibling(r.outDir.getFileName() + ".args");
        Fs.write(argFile, String.join("\n", args.stream().map(JvmLanguages::quote).toList()) + "\n");
        List<String> command = new ArrayList<>();
        command.add(r.jdk.java());
        command.add("-cp");
        command.add(Compiler.join(tool));
        command.add(mainClass(r.lang, r.version));
        if (r.lang == Language.GROOVY) command.addAll(args);
        else command.add("@" + argFile);
        Log.debug(r.label + ": " + r.lang.key + "c " + r.version + " on " + own.size() + " " + r.lang.title + " and " + java.size() + " Java file(s)");
        Exec.Result res = Exec.capture(null, command);
        String out = (res.out() + res.err()).strip();
        if (!res.ok()) {
            Fs.deleteTree(tmp);
            try {
                Files.deleteIfExists(r.stateFile);
            } catch (IOException ignored) {
            }
            Log.tool(out);
            throw new JpmException(r.label + ": " + r.lang.title + " compilation failed");
        }
        Log.tool(out);
        Fs.deleteTree(r.outDir);
        try {
            Files.move(tmp, r.outDir);
        } catch (IOException e) {
            throw new JpmException("Could not move " + tmp + " to " + r.outDir + ": " + e.getMessage());
        }
        Fs.write(r.stateFile, key);
        return true;
    }

    public static String example(Language lang) {
        return lang == Language.GROOVY ? "4.0.24" : "3.5.2";
    }

    public static String runtimeLibrary(Language lang, String version) {
        if (lang == Language.GROOVY) return groovyGroup(version) + ":groovy";
        return version.startsWith("3.") ? "org.scala-lang:scala3-library_3" : "org.scala-lang:scala-library";
    }

    static String compiler(Language lang, String version) {
        if (lang == Language.GROOVY) return groovyGroup(version) + ":groovy";
        return version.startsWith("3.") ? "org.scala-lang:scala3-compiler_3" : "org.scala-lang:scala-compiler";
    }

    static String mainClass(Language lang, String version) {
        if (lang == Language.GROOVY) return "org.codehaus.groovy.tools.FileSystemCompiler";
        return version.startsWith("3.") ? "dotty.tools.dotc.Main" : "scala.tools.nsc.Main";
    }

    private static String groovyGroup(String version) {
        int major;
        try {
            major = Integer.parseInt(version.split("\\.")[0]);
        } catch (NumberFormatException e) {
            major = 4;
        }
        return major >= 4 ? "org.apache.groovy" : "org.codehaus.groovy";
    }

    private static String stamp(Path p) {
        try {
            if (Files.isDirectory(p)) return Builder.treeKey(List.of(p));
            return Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return "missing";
        }
    }

    private static String quote(String s) {
        if (s.isEmpty() || s.chars().anyMatch(c -> c == ' ' || c == '"' || c == '\'' || c == '\\' || c == '\t')) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }
}
