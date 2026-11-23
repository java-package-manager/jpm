package dev.jpm.build;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Install;
import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "groovy" and "scala": their sources compiled before javac, seeing the Java sources. */
class JvmLanguagesTest {
    @Test
    void settingsAndMavenLayoutFolders(@TempDir Path dir) {
        Manifest m = Manifest.parse(dir.resolve("project.jsonc"), "{ \"name\": \"a\", \"layout\": \"maven\", \"groovy\": \"4.99.0-stub\", \"scala\": \"3.99.0-stub\" }");
        assertEquals("4.99.0-stub", m.groovy);
        assertEquals("3.99.0-stub", m.scala);
        assertTrue(m.sourceDirs(dir).containsAll(List.of(dir.resolve("src/main/groovy"), dir.resolve("src/main/scala"))));
        assertTrue(m.testSourceDirs(dir).containsAll(List.of(dir.resolve("src/test/groovy"), dir.resolve("src/test/scala"))));
        JpmException e = assertThrows(JpmException.class, () -> Manifest.parse(dir.resolve("project.jsonc"), "{ \"name\": \"a\", \"scala\": \"3\" }"));
        assertTrue(e.getMessage().contains("exact Scala version"), e.getMessage());
    }

    @Test
    void sourcesWithoutAVersionSayWhatToAdd(@TempDir Path dir) {
        Fs.write(dir.resolve("project.jsonc"), "{ \"name\": \"g\", \"version\": \"1.0.0\" }");
        Fs.write(dir.resolve("src/g/A.groovy"), "package g\nclass A {}\n");
        Project project = Project.load(dir);
        MavenRepo r = new MavenRepo(List.of());
        Builder b = new Builder(project, Install.run(project, r, Install.Options.normal()), r, Env.Mode.PRODUCTION);
        JpmException e = assertThrows(JpmException.class, () -> b.compileMain(project.modules.get(0)));
        assertTrue(e.getMessage().contains("no \"groovy\" version") && e.getMessage().contains("org.apache.groovy:groovy"), e.getMessage());
    }

    /**
     * A stand-in for groovyc and the Scala compiler: it reads their options (an argfile too), and compiles the
     * sources it gets, which are written in Java syntax, with javac. So jpm's side (resolving the compiler, its
     * arguments, the Java sources it sees, the output merged into the module's classes) is tested without the
     * network; the real compilers are covered by hand and in CI's imports.
     */
    private static final String STUB = """
            public class Stub {
                public static void main(String[] args) throws Exception {
                    java.util.List<String> all = new java.util.ArrayList<>();
                    for (String a : args) {
                        if (a.startsWith("@")) {
                            for (String l : java.nio.file.Files.readAllLines(java.nio.file.Path.of(a.substring(1)))) {
                                if (!l.isBlank()) all.add(l.strip().replaceAll("^\\"|\\"$", ""));
                            }
                        } else all.add(a);
                    }
                    String out = null, cp = "";
                    java.util.List<String> files = new java.util.ArrayList<>();
                    java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("stubc");
                    for (int i = 0; i < all.size(); i++) {
                        String a = all.get(i);
                        if (a.equals("-d")) out = all.get(++i);
                        else if (a.equals("-cp") || a.equals("-classpath")) cp = all.get(++i);
                        else if (a.equals("-java-output-version") || a.equals("-release")) i++;
                        else if (a.equals("-j") || a.startsWith("--temp=")) continue;
                        else if (a.endsWith(".java")) files.add(a);
                        else {
                            java.nio.file.Path src = java.nio.file.Path.of(a);
                            String name = src.getFileName().toString();
                            java.nio.file.Path copy = tmp.resolve(name.substring(0, name.lastIndexOf('.')) + ".java");
                            java.nio.file.Files.copy(src, copy);
                            files.add(copy.toString());
                        }
                    }
                    java.util.List<String> javac = new java.util.ArrayList<>(java.util.List.of("-d", out, "-cp", cp));
                    javac.addAll(files);
                    System.exit(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, javac.toArray(new String[0])));
                }
            }
            """;

    private static String repo(Path dir, String compiler, String main, String library) {
        TestRepo repo = new TestRepo(dir.resolve("repo"));
        String pkg = main.substring(0, main.lastIndexOf('.')), cls = main.substring(main.lastIndexOf('.') + 1);
        repo.artifact(compiler).javaClass(main, "package " + pkg + ";\n" + STUB.replace("class Stub", "class " + cls)).publish();
        if (library != null) repo.artifact(library).publish();
        return repo.root().toUri().toString();
    }

    @Test
    void groovyAndJavaTogether(@TempDir Path dir) throws Exception {
        String repo = repo(dir, "org.apache.groovy:groovy:4.99.0-stub", "org.codehaus.groovy.tools.FileSystemCompiler", null);
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"g\", \"version\": \"1.0.0\", \"groovy\": \"4.99.0-stub\", \"repositories\": [\"" + repo
                + "\"], \"runtime\": { \"org.apache.groovy:groovy\": \"4.99.0-stub\" } }");
        Fs.write(p.resolve("src/g/Greeter.groovy"), "package g; public class Greeter { public String greet() { return \"Hi \" + new Twice().of(21); } }");
        Fs.write(p.resolve("src/g/Twice.java"), "package g; public class Twice { public int of(int x) { return 2 * x; } }");
        Fs.write(p.resolve("src/g/Run.java"), "package g; public class Run { public static String run() { return new Greeter().greet(); } }");
        assertEquals("Hi 42", run(p, "g.Run"));
        assertTrue(Fs.read(p.resolve(".cache/build/g/groovy-classes.key")).length() > 0);
    }

    @Test
    void scalaAndJavaTogether(@TempDir Path dir) throws Exception {
        String repo = repo(dir, "org.scala-lang:scala3-compiler_3:3.99.0-stub", "dotty.tools.dotc.Main", "org.scala-lang:scala3-library_3:3.99.0-stub");
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"s\", \"version\": \"1.0.0\", \"scala\": \"3.99.0-stub\", \"repositories\": [\"" + repo
                + "\"], \"runtime\": { \"org.scala-lang:scala3-library_3\": \"3.99.0-stub\" } }");
        Fs.write(p.resolve("src/s/Square.scala"), "package s; public class Square { public int area(int side) { return side * side * Unit1.one(); } }");
        Fs.write(p.resolve("src/s/Unit1.java"), "package s; public class Unit1 { public static int one() { return 1; } }");
        Fs.write(p.resolve("src/s/Run.java"), "package s; public class Run { public static String run() { return \"area \" + new Square().area(3); } }");
        assertEquals("area 9", run(p, "s.Run"));
    }

    @Test
    void aMissingRuntimeLibrarySaysWhichToAdd(@TempDir Path dir) {
        String repo = repo(dir, "org.scala-lang:scala3-compiler_3:3.99.0-stub", "dotty.tools.dotc.Main", null);
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"s\", \"version\": \"1.0.0\", \"scala\": \"3.99.0-stub\", \"repositories\": [\"" + repo + "\"] }");
        Fs.write(p.resolve("src/s/A.scala"), "package s; class A {}");
        JpmException e = assertThrows(JpmException.class, () -> run(p, "s.A"));
        assertTrue(e.getMessage().contains("\"org.scala-lang:scala3-library_3\": \"3.99.0-stub\""), e.getMessage());
    }

    private static Object run(Path p, String cls) throws Exception {
        Project project = Project.load(p);
        MavenRepo r = new MavenRepo(project.root.repositories);
        Builder b = new Builder(project, Install.run(project, r, Install.Options.normal()), r, Env.Mode.PRODUCTION);
        Project.Module m = project.modules.get(0);
        b.jar(m);
        try (URLClassLoader cl = new URLClassLoader(b.runtimeClasspath(m).stream().map(JvmLanguagesTest::url).toArray(java.net.URL[]::new), null)) {
            return cl.loadClass(cls).getMethod("run").invoke(null);
        }
    }

    private static java.net.URL url(Path p) {
        try {
            return p.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
