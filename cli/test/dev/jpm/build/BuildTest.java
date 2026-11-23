package dev.jpm.build;

import dev.jpm.build.impl.BundleLauncher;
import dev.jpm.build.impl.ClassInfo;

import dev.jpm.Manifest;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildTest {
    @Test
    void parsesEnvFiles() {
        Map<String, String> m = new LinkedHashMap<>();
        Env.parse("""
                # comment
                A=1
                export B = two words # trailing
                C="quoted # not a comment"
                D='single'
                E=${A}-x
                """, m);
        assertEquals("1", m.get("A"));
        assertEquals("two words", m.get("B"));
        assertEquals("quoted # not a comment", m.get("C"));
        assertEquals("single", m.get("D"));
        assertEquals("1-x", m.get("E"));
    }

    @Test
    void envFileOrder(@TempDir Path dir) {
        Fs.write(dir.resolve(".env"), "A=base\nB=base\nC=base\n");
        Fs.write(dir.resolve(".env.production"), "B=prod\nC=prod\n");
        Fs.write(dir.resolve(".env.development"), "B=dev\n");
        Fs.write(dir.resolve(".env.local"), "C=local\n");
        Map<String, String> prod = Env.files(dir, Env.Mode.PRODUCTION);
        assertEquals(Map.of("A", "base", "B", "prod", "C", "local"), prod);
        assertEquals("dev", Env.files(dir, Env.Mode.DEVELOPMENT).get("B"));
    }

    private static Manifest lib(String defines) {
        return Manifest.parse(Path.of("lib", "project.jsonc"), "{ \"name\": \"lib\", \"define\": " + defines + " }");
    }

    @Test
    void definesResolveAndType() {
        Manifest m = lib("{ \"URL\": { \"type\": \"string\", \"env\": \"URL\", \"default\": \"d\" },"
                + " \"N\": { \"type\": \"int\", \"env\": \"N\" }, \"F\": true }");
        List<Defines.Value> v = Defines.resolve(m, null, null, null, Map.of("N", "7"));
        assertEquals("d", v.get(0).value());
        assertEquals(7, v.get(1).value());
        assertEquals(true, v.get(2).value());
        String src = Defines.source("a.b.BuildConfig", v, "lib", "1.0");
        assertTrue(src.contains("public static final int N = 7;"));
        assertTrue(src.contains("package a.b;"));
        JpmException bad = assertThrows(JpmException.class, () -> Defines.resolve(m, null, null, null, Map.of("N", "x")));
        assertTrue(bad.getMessage().contains("must be an int"), bad.getMessage());
    }

    @Test
    void libraryDefinesMustBeProvided() {
        Manifest m = lib("{ \"API_URL\": { \"type\": \"string\" } }");
        Manifest consumer = Manifest.parse(Path.of("app", "project.jsonc"), "{ \"name\": \"app\" }");
        JpmException e = assertThrows(JpmException.class, () -> Defines.resolve(m, "github:u/lib", null, consumer, Map.of()));
        assertTrue(e.getMessage().contains("github:u/lib needs a value for define API_URL (string)"), e.getMessage());
        assertTrue(e.getMessage().contains("\"github:u/lib\": { \"API_URL\": \"...\" }"), e.getMessage());
        JpmException typed = assertThrows(JpmException.class,
                () -> Defines.resolve(m, "github:u/lib", Map.of("API_URL", new java.math.BigDecimal(3)), consumer, Map.of()));
        assertTrue(typed.getMessage().contains("must be a string"), typed.getMessage());
        assertEquals("x", Defines.resolve(m, "github:u/lib", Map.of("API_URL", "x"), consumer, Map.of()).get(0).value());
    }

    @Test
    void classInfoSeparatesAbiFromBody(@TempDir Path dir) throws Exception {
        Jdk jdk = Toolchain.require(JavaSpec.any());
        Path src = dir.resolve("src/p");
        Fs.write(src.resolve("A.java"), "package p; public class A { public int f() { return 1; } private int g() { return 0; } }");
        ClassInfo one = compileOne(jdk, dir);
        Fs.write(src.resolve("A.java"), "package p; public class A { public int f() { return 2; } private int g2() { return 0; } }");
        ClassInfo two = compileOne(jdk, dir);
        assertEquals(one.abi(), two.abi());
        Fs.write(src.resolve("A.java"), "package p; public class A { public long f() { return 2; } }");
        ClassInfo three = compileOne(jdk, dir);
        assertNotEquals(one.abi(), three.abi());
        Fs.write(src.resolve("A.java"), "package p; public class A { public static final String V = \"1.0\"; }");
        ClassInfo s1 = compileOne(jdk, dir);
        Fs.write(src.resolve("A.java"), "package p; public class A { public static final String V = \"2.0\"; }");
        ClassInfo s2 = compileOne(jdk, dir);
        assertNotEquals(s1.constants(), s2.constants(), "a String constant change must be seen (javac inlines it)");
        Fs.write(src.resolve("A.java"), "package p; public class A { public static final int K = 1; java.util.List<String> l; }");
        ClassInfo four = compileOne(jdk, dir);
        assertTrue(four.refs().contains("java/util/List"));
        assertEquals("A.java", four.sourceFile());
    }

    private static ClassInfo compileOne(Jdk jdk, Path dir) throws Exception {
        Path out = dir.resolve("out");
        Fs.deleteTree(out);
        var r = dev.jpm.util.Exec.capture(dir, List.of(jdk.javac(), "-d", out.toString(), dir.resolve("src/p/A.java").toString()));
        assertTrue(r.ok(), r.err());
        return ClassInfo.read(Files.readAllBytes(out.resolve("p/A.class")));
    }

    @Test
    void bundleLauncherCompilesForJava8(@TempDir Path dir) {
        Jdk jdk = Toolchain.require(JavaSpec.any());
        Fs.write(dir.resolve("src/dev/jpm/bundle/Launcher.java"), BundleLauncher.LAUNCHER);
        assertTrue(Compiler.compile(new Compiler.Request("launcher", jdk, 8, List.of(dir.resolve("src")), List.of(), List.of(),
                dir.resolve("out"), dir.resolve("state.json"), List.of("-nowarn"), "k")));
        for (String c : List.of("Launcher", "IsolatedLoader", "ContextLoader")) {
            assertTrue(Files.exists(dir.resolve("out/dev/jpm/bundle/" + c + ".class")), c);
        }
    }

    @Test
    void incrementalCompile(@TempDir Path dir) {
        Jdk jdk = Toolchain.require(JavaSpec.any());
        Path src = dir.resolve("src");
        Fs.write(src.resolve("p/A.java"), "package p; public class A { public static int f() { return 1; } }");
        Fs.write(src.resolve("p/B.java"), "package p; public class B { int g() { return A.f(); } }");
        Fs.write(src.resolve("p/C.java"), "package p; public class C { }");
        Compiler.Request req = new Compiler.Request("t", jdk, jdk.major(), List.of(src), List.of(), List.of(),
                dir.resolve("classes"), dir.resolve("state.json"), List.of(), "k");
        assertTrue(Compiler.compile(req));
        assertFalse(Compiler.compile(req), "nothing changed");
        Fs.write(src.resolve("p/C.java"), "package p; public class C { int x; }");
        assertTrue(Compiler.compile(req));
        try {
            Files.delete(src.resolve("p/A.java"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        java.io.PrintStream err = System.err;
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            assertThrows(JpmException.class, () -> Compiler.compile(req));
        } finally {
            System.setErr(err);
        }
        assertFalse(Files.exists(dir.resolve("classes/p/A.class")));
    }

    /**
     * "-J" entries in javacArgs reach the JVM that runs javac (ErrorProne needs --add-exports there); javac
     * refuses them inside an argfile. An annotation processor running inside javac checks the property.
     */
    @Test
    void jvmOptionsReachTheJavacProcess(@TempDir Path dir) throws Exception {
        Jdk jdk = Toolchain.require(JavaSpec.any());
        Path procSrc = dir.resolve("proc-src");
        Fs.write(procSrc.resolve("proc/Check.java"), """
                package proc;
                import java.util.Set;
                import javax.annotation.processing.*;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                @SupportedAnnotationTypes("*")
                public class Check extends AbstractProcessor {
                    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    @Override public boolean process(Set<? extends TypeElement> a, RoundEnvironment env) {
                        if (!"yes".equals(System.getProperty("jpm.test.marker"))) {
                            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, "no -J option");
                        }
                        return false;
                    }
                }
                """);
        Fs.write(procSrc.resolve("META-INF/services/javax.annotation.processing.Processor"), "proc.Check\n");
        Path proc = dir.resolve("proc");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", proc.toString(),
                procSrc.resolve("proc/Check.java").toString()));
        Fs.write(proc.resolve("META-INF/services/javax.annotation.processing.Processor"), "proc.Check\n");
        Fs.write(dir.resolve("src/p/A.java"), "package p; public class A { }");
        assertTrue(Compiler.compile(new Compiler.Request("t", jdk, jdk.major(), List.of(dir.resolve("src")), List.of(), List.of(proc),
                dir.resolve("classes"), dir.resolve("state.json"), List.of("-J-Djpm.test.marker=yes", "-Xlint:all"), "k")));
        assertTrue(Files.exists(dir.resolve("classes/p/A.class")));
        assertFalse(Fs.read(dir.resolve("classes.args")).contains("-J"), "javac refuses -J in an argfile");
    }

    private static final List<String> EXPORT_JAVAC_TREE =
            List.of("--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED");

    @Test
    void exportsFromJdkModulesWorkWhenTheJdkIsTheTarget(@TempDir Path dir) {
        Jdk jdk = Toolchain.require(JavaSpec.any());
        Fs.write(dir.resolve("src/p/A.java"), "package p; public class A { com.sun.tools.javac.tree.JCTree t; }");
        assertTrue(Compiler.compile(new Compiler.Request("t", jdk, jdk.major(), List.of(dir.resolve("src")), List.of(), List.of(),
                dir.resolve("classes"), dir.resolve("state.json"), EXPORT_JAVAC_TREE, "k")));
        assertTrue(Files.exists(dir.resolve("classes/p/A.class")));
    }

    @Test
    void crossCompilingStillChecksTheTargetApi(@TempDir Path dir) {
        Jdk jdk = Toolchain.require(JavaSpec.any());
        Assumptions.assumeTrue(jdk.major() >= 21, "needs JDK 21+ to target 17");
        Fs.write(dir.resolve("src/p/A.java"), "package p; public class A { java.util.SequencedCollection<String> s; }");
        Compiler.Request req = new Compiler.Request("t", jdk, 17, List.of(dir.resolve("src")), List.of(), List.of(),
                dir.resolve("classes"), dir.resolve("state.json"), List.of(), "k");
        quietly(() -> assertThrows(JpmException.class, () -> Compiler.compile(req)));
    }

    @Test
    void crossCompilingWithExportsFromJdkModulesExplainsWhy(@TempDir Path dir) {
        Jdk jdk = Toolchain.require(JavaSpec.any());
        Assumptions.assumeTrue(jdk.major() >= 18, "needs a JDK newer than the target 17");
        Fs.write(dir.resolve("src/p/A.java"), "package p; public class A { }");
        Compiler.Request req = new Compiler.Request("t", jdk, 17, List.of(dir.resolve("src")), List.of(), List.of(),
                dir.resolve("classes"), dir.resolve("state.json"), EXPORT_JAVAC_TREE, "k");
        quietly(() -> {
            JpmException e = assertThrows(JpmException.class, () -> Compiler.compile(req));
            assertTrue(e.getMessage().contains("build with JDK 17"), e.getMessage());
        });
    }

    @Test
    void argumentsJavacRefusesWithRelease() {
        assertEquals("--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                Compiler.refusedWithRelease(List.of("--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")));
        assertEquals("--add-exports java.base/sun.nio.ch=ALL-UNNAMED",
                Compiler.refusedWithRelease(List.of("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED")));
        assertEquals("--system", Compiler.refusedWithRelease(List.of("--system", "/opt/jdk")));
        assertEquals(null, Compiler.refusedWithRelease(List.of("--add-exports", "my.module/my.pkg=ALL-UNNAMED", "-Xlint:all")));
        assertEquals(null, Compiler.refusedWithRelease(List.of("-parameters")));
    }

    @Test
    void sourceDependenciesThatExportJdkModulesBuildWithTheirTargetJdk() {
        assertEquals(JavaSpec.parse("21"), Builder.targetJdk(25, 21, EXPORT_JAVAC_TREE));
        assertEquals(null, Builder.targetJdk(21, 21, EXPORT_JAVAC_TREE));
        assertEquals(null, Builder.targetJdk(25, 21, List.of("-parameters")));
    }

    private static void quietly(Runnable r) {
        java.io.PrintStream err = System.err;
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            r.run();
        } finally {
            System.setErr(err);
        }
    }
}
