package dev.jpm.e2e;

import dev.jpm.testing.TestRepo;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs the {@code jpm} binary on the projects in {@code fixtures/} and compares everything it prints with
 * {@code fixtures/expected/}. These tests are the safety net for the restructure: a change to an expected
 * transcript is a change in behavior.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the transcripts use / in paths; Windows parity is a roadmap item")
class EndToEndTest {
    private static final String MONEY_V1 = """
            package org.example.money;
            public final class Money {
                public static String format(long cents) {
                    return String.format("EUR %d.%02d", cents / 100, cents % 100);
                }
            }
            """;

    private static void money(TestRepo repo) {
        repo.artifact("org.example:money:1.0.0").javaClass("org.example.money.Money", MONEY_V1).publish();
        repo.artifact("org.example:money:1.2.0").javaClass("org.example.money.Money", MONEY_V1).publish();
        repo.artifact("org.example:money:2.0.0").javaClass("org.example.money.Money", MONEY_V1).publish();
    }

    @Test
    void initBuildStartTest(@TempDir Path tmp) {
        Session s = new Session(tmp);
        s.jpm("init", "hello");
        s.tree(".").show("project.jsonc").show(".gitignore");
        s.jpm("add", "--test", "org.junit.jupiter:junit-jupiter@*");
        s.jpm("build");
        s.tree("dist");
        s.jpm("start");
        s.jpm("test");
        s.jpm("info");
        s.jpm("clean");
        s.tree("dist");
        s.jpm("init");
        s.verify("init");
    }

    @Test
    void workspace(@TempDir Path tmp) {
        Session s = new Session(tmp).fixture("workspace-demo");
        money(s.repo);
        s.jpm("install");
        s.show("project.lock");
        s.jpm("install", "--frozen");
        s.jpm("why", "org.example:money");
        s.jpm("outdated");
        s.jpm("build");
        s.tree("core/dist").tree("app/dist");
        s.jpmIn("app", "start");
        s.jpm("test");
        s.jpm("run", "hello");
        s.jpm("run");
        s.jpm("info");
        s.jpm("update", "--latest", "org.example:money");
        s.show("core/project.jsonc");
        s.jpmIn("core", "remove", "org.example:money");
        s.jpmIn("core", "add", "org.example:money@1.0.0");
        s.show("core/project.jsonc");
        s.jpm("why", "org.example:nothing");
        s.jpm("frobnicate");
        s.jpm("test", "shop.core.CartTest");
        s.jpm("test", "shop.core.Missing");
        s.verify("workspace");
    }

    @Test
    void bundle(@TempDir Path tmp) {
        Session s = new Session(tmp).fixture("bundle-demo");
        s.repo.artifact("org.apache.commons:commons-text:1.12.0").javaClass("org.apache.commons.text.WordUtils", """
                package org.apache.commons.text;
                public final class WordUtils {
                    public static String capitalizeFully(String s) {
                        StringBuilder b = new StringBuilder();
                        for (String w : s.split(" ")) {
                            if (b.length() > 0) b.append(' ');
                            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
                        }
                        return b.toString();
                    }
                }
                """).publish();
        s.jpm("build");
        s.tree("app/dist");
        s.exec(".", Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", "app/dist/app-1.0.0-bundle.jar");
        s.verify("bundle");
    }

    @Test
    void importMaven(@TempDir Path tmp) {
        Session s = new Session(tmp).fixture("maven-demo");
        money(s.repo);
        s.jpm("import", "--dry-run");
        s.jpm("import");
        s.show("project.jsonc");
        s.jpm("import");
        s.jpm("build");
        s.jpm("start");
        s.verify("import-maven");
    }

    @Test
    void importGradleWithoutRunningIt(@TempDir Path tmp) {
        Session s = new Session(tmp).fixture("gradle-demo");
        s.repo.publish("com.google.guava:guava:33.3.1-jre");
        s.repo.publish("org.apache.commons:commons-lang3:3.14.0");
        s.repo.publish("org.projectlombok:lombok:1.18.34");
        s.repo.publish("com.fasterxml.jackson.core:jackson-databind:2.17.2");
        s.repo.artifact("com.fasterxml.jackson:jackson-bom:2.17.2").pom("<packaging>pom</packaging><dependencyManagement><dependencies>"
                + "<dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId>"
                + "<version>2.17.2</version></dependency></dependencies></dependencyManagement>").publish();
        s.repo.publish("org.junit.jupiter:junit-jupiter:5.11.4");
        s.jpm("import", "--static");
        s.show("project.jsonc").show("core/project.jsonc").show("app/project.jsonc");
        s.verify("import-gradle-static");
    }

    @Test
    void schema(@TempDir Path tmp) {
        Session s = new Session(tmp);
        String schema = s.jpmUnrecorded("schema");
        if (!schema.contains("\"$schema\"")) throw new AssertionError("jpm schema printed no JSON Schema:\n" + schema);
        s.jpm("schema", "--path");
        s.jpm("--version");
        s.jpm("schema", "--bogus");
        s.verify("schema");
    }

    private static void git(Path dir, String... args) {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-c", "user.name=e2e", "-c", "user.email=e2e@example.com",
                "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false"));
        cmd.addAll(List.of(args));
        Exec.Result r = Exec.capture(dir, cmd);
        if (r.exit() != 0) throw new AssertionError(String.join(" ", cmd) + ": " + r.out() + r.err());
    }

    @Test
    void gitDependencies(@TempDir Path tmp) {
        Session s = new Session(tmp);
        Path lib = tmp.resolve("git/me/lib");
        Fs.write(lib.resolve("src/lib/Lib.java"), "package lib; public class Lib { public static String v() { return \"one\"; } }");
        Fs.write(lib.resolve("project.jsonc"), "{ \"name\": \"lib\", \"version\": \"1.0.0\" }");
        git(lib, "init", "-q", "-b", "main");
        git(lib, "add", "-A");
        git(lib, "commit", "-qm", "1.0.0");
        git(lib, "tag", "1.0.0");
        Fs.write(lib.resolve("src/lib/Lib.java"), "package lib; public class Lib { public static String v() { return \"two\"; } }");
        Fs.write(lib.resolve("project.jsonc"), "{ \"name\": \"lib\", \"version\": \"1.1.0\" }");
        git(lib, "commit", "-qam", "1.1.0");
        git(lib, "tag", "1.1.0-canary1");
        s.env("JPM_GIT_BASE_GITHUB", tmp.resolve("git").toUri().toString());
        Fs.write(s.project.resolve("src/app/Main.java"),
                "package app; public class Main { public static void main(String[] a) { System.out.println(lib.Lib.v()); } }");
        for (String spec : List.of("1.0.0", "main", "#main")) {
            Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"java\": \"21\","
                    + " \"main\": \"app.Main\", \"runtime\": { \"github:me/lib\": \"" + spec + "\" } }");
            Fs.deleteTree(s.project.resolve("project.lock"));
            s.jpm("install");
            s.jpm("start");
        }
        s.verify("git");
    }

    @Test
    void projectExtensions(@TempDir Path tmp) {
        Session s = new Session(tmp);
        Path ext = tmp.resolve("git/me/jpm-hello");
        String main = "package hello; public class Main { public static void main(String[] a) {"
                + " System.out.println(\"hello %s from VERSION, settings: \" + String.valueOf(System.getenv(\"JPM_CONFIG\")).contains(\"\\\"loud\\\"\")); } }";
        Fs.write(ext.resolve("src/hello/Main.java"), main.replace("%s", "\" + String.join(\" \", a) + \"").replace("VERSION", "1.0.0"));
        Fs.write(ext.resolve("project.jsonc"), "{ \"name\": \"jpm-hello\", \"version\": \"1.0.0\", \"main\": \"hello.Main\", \"extension\": {} }");
        git(ext, "init", "-q", "-b", "main");
        git(ext, "add", "-A");
        git(ext, "commit", "-qm", "1.0.0");
        git(ext, "tag", "1.0.0");
        Fs.write(ext.resolve("src/hello/Main.java"), main.replace("%s", "\" + String.join(\" \", a) + \"").replace("VERSION", "1.1.0"));
        Fs.write(ext.resolve("project.jsonc"), "{ \"name\": \"jpm-hello\", \"version\": \"1.1.0\", \"main\": \"hello.Main\", \"extension\": {} }");
        git(ext, "commit", "-qam", "1.1.0");
        git(ext, "tag", "1.1.0");
        s.env("JPM_GIT_BASE_GITHUB", tmp.resolve("git").toUri().toString());
        Fs.write(s.project.resolve("project.jsonc"), "{\n  \"name\": \"app\",\n  \"version\": \"1.0.0\",\n  \"java\": \"21\",\n"
                + "  \"extensions\": { \"hello\": { \"loud\": true } }\n}\n");
        s.jpm("install");
        s.jpm("add", "-g", "github:me/jpm-hello@1.0.0");
        s.jpm("hello", "world");
        s.jpm("add", "github:me/jpm-hello");
        s.show("project.jsonc");
        s.jpm("hello", "world");
        s.jpmIn("..", "hello", "outside");
        Fs.deleteTree(s.project.resolve(".cache"));
        s.jpm("install", "--frozen");
        s.jpm("hello", "again");
        s.jpm("remove", "hello");
        s.show("project.jsonc");
        s.verify("project-extensions");
    }

    @Test
    void checkReportsEveryProblem(@TempDir Path tmp) {
        Session s = new Session(tmp);
        money(s.repo);
        s.repo.artifact("org.example:money-copy:1.0.0").javaClass("org.example.money.Money", MONEY_V1).publish();
        s.repo.artifact("org.example:unused:1.0.0").javaClass("org.example.unused.Unused", "package org.example.unused; public class Unused {}").publish();
        Path lint = tmp.resolve("git/me/jpm-lint");
        Fs.write(lint.resolve("src/lint/Main.java"), "package lint; public class Main { public static void main(String[] a) throws Exception {"
                + " java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv(\"JPM_CHECK_PROBLEMS\")),"
                + " \"[{\\\"severity\\\": \\\"warning\\\", \\\"message\\\": \\\"Main is too long\\\", \\\"file\\\": \\\"src/app/Main.java\\\", \\\"line\\\": 1}]\");"
                + " System.out.println(\"jpm-lint ran with \" + String.join(\" \", a)); } }");
        Fs.write(lint.resolve("project.jsonc"), "{ \"name\": \"jpm-lint\", \"version\": \"1.0.0\", \"main\": \"lint.Main\", \"extension\": {"
                + " \"checks\": { \"style\": { \"description\": \"the code has style\", \"run\": [\"--style\"] } } } }");
        git(lint, "init", "-q", "-b", "main");
        git(lint, "add", "-A");
        git(lint, "commit", "-qm", "1.0.0");
        git(lint, "tag", "1.0.0");
        s.env("JPM_GIT_BASE_GITHUB", tmp.resolve("git").toUri().toString());
        Fs.write(s.project.resolve("project.jsonc"), """
                {
                  "name": "app",
                  "group": "org.example",
                  "version": "1.0.0",
                  "java": "21",
                  "main": "app.Main",
                  "sources": ["src", "generated"],
                  "scripts": { "gen": "gen.java" },
                  "runtime": {
                    "org.example:money": "1.0.0",
                    "org.example:money-copy": "1.0.0",
                    "org.example:unused": "1.0.0"
                  },
                  "test": { "org.junit.jupiter:junit-jupiter": "*" },
                  "extensions": { "github:me/jpm-lint": "1.0.0" }
                }
                """);
        Fs.write(s.project.resolve("src/app/Main.java"), "package app; public class Main { public static void main(String[] a) {"
                + " System.out.println(org.example.money.Money.format(1234)); } }");
        Fs.write(s.project.resolve("test/app/MainTest.java"), """
                package app;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                class MainTest {
                    @Test
                    void adds() {
                        assertEquals(3, 1 + 1);
                    }
                }
                """);
        s.jpm("check", "--sarif", "check.sarif", "--junit", "check.xml");
        s.show("check.xml");
        s.jpm("check", "--no-test", "paths", "license");
        Fs.write(s.project.resolve("project.jsonc"), Fs.read(s.project.resolve("project.jsonc")).replace("\"sources\": [\"src\", \"generated\"],", "")
                .replace("\"scripts\": { \"gen\": \"gen.java\" },", "\"license\": \"MIT\","));
        Fs.write(s.project.resolve("test/app/MainTest.java"), "package app; class MainTest { @org.junit.jupiter.api.Test void adds() {"
                + " org.junit.jupiter.api.Assertions.assertEquals(2, 1 + 1); } }");
        s.jpm("check", "--fail-on-warning", "--no-test");
        s.jpm("check", "--no-test");
        s.verify("check");
        String sarif = Fs.read(s.project.resolve("check.sarif"));
        if (!sarif.contains("\"ruleId\": \"paths\"") || !sarif.contains("\"uri\": \"src/app/Main.java\"") || !sarif.contains("\"startLine\": 9")) {
            throw new AssertionError("the SARIF report misses results:\n" + sarif);
        }
    }

    @Test
    void patchADependency(@TempDir Path tmp) {
        Session s = new Session(tmp);
        s.repo.artifact("org.example:money:1.0.0").javaClass("org.example.money.Money", MONEY_V1)
                .source("org/example/money/Money.java", MONEY_V1).publish();
        Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"java\": \"21\","
                + " \"main\": \"app.Main\", \"runtime\": { \"org.example:money\": \"1.0.0\" } }");
        Fs.write(s.project.resolve("src/app/Main.java"), "package app; public class Main { public static void main(String[] a) {"
                + " System.out.println(org.example.money.Money.format(1234)); } }");
        s.jpm("start");
        s.jpm("patch", "org.example:money");
        Path source;
        try (var files = java.nio.file.Files.walk(s.project.resolve(".cache"))) {
            source = files.filter(f -> f.endsWith("org/example/money/Money.java")).findFirst().orElseThrow();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        try {
            Fs.write(source, java.nio.file.Files.readString(source).replace("EUR", "USD"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        s.jpm("patch", "--commit", "org.example:money");
        s.tree("patches").show("project.jsonc");
        s.jpm("start");
        s.verify("patch");
    }

    @Test
    void outputOptions(@TempDir Path tmp) {
        Session s = new Session(tmp).fixture("workspace-demo");
        money(s.repo);
        s.jpm("--quiet", "install");
        s.jpm("build", "--quiet");
        s.jpm("-q", "why", "org.example:money");
        s.jpm("--color=sometimes", "build");
        s.jpm("--color", "never", "outdated");
        s.jpm("install", "-v");
        Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"shop\", \"versoin\": \"1.0.0\" }");
        s.jpm("build");
        s.verify("output");
    }

    @Test
    void gitDependencyWithItsOwnRepository(@TempDir Path tmp) {
        Session s = new Session(tmp);
        Path other = tmp.resolve("other-repo");
        money(new dev.jpm.testing.TestRepo(other));
        Path lib = tmp.resolve("git/me/shop");
        Fs.write(lib.resolve("src/shop/Price.java"), "package shop; public class Price { public static String of(long c) {"
                + " return org.example.money.Money.format(c); } }");
        Fs.write(lib.resolve("project.jsonc"), "{ \"name\": \"shop\", \"version\": \"1.0.0\", \"repositories\": [\"" + other.toUri()
                + "\"], \"runtime\": { \"org.example:money\": \"1.2.0\" } }");
        git(lib, "init", "-q", "-b", "main");
        git(lib, "add", "-A");
        git(lib, "commit", "-qm", "1.0.0");
        git(lib, "tag", "1.0.0");
        s.env("JPM_GIT_BASE_GITHUB", tmp.resolve("git").toUri().toString());
        Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"java\": \"21\","
                + " \"main\": \"app.Main\", \"runtime\": { \"github:me/shop\": \"1.0.0\" } }");
        Fs.write(s.project.resolve("src/app/Main.java"),
                "package app; public class Main { public static void main(String[] a) { System.out.println(shop.Price.of(1234)); } }");
        s.jpm("install");
        Fs.deleteTree(s.home.resolve("repository"));
        s.jpm("install", "--frozen");
        s.jpm("start");
        s.verify("git-own-repository");
    }

    @Test
    void gitDependencyWithAMavenBuild(@TempDir Path tmp) {
        Session s = new Session(tmp);
        Path lib = tmp.resolve("git/me/maven-lib");
        Fs.write(lib.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>me</groupId>
                  <artifactId>maven-lib</artifactId>
                  <version>2.0.0</version>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                </project>
                """);
        Fs.write(lib.resolve("src/main/java/lib/Lib.java"), "package lib; public class Lib { public static String v() { return \"from maven\"; } }");
        git(lib, "init", "-q", "-b", "main");
        git(lib, "add", "-A");
        git(lib, "commit", "-qm", "2.0.0");
        git(lib, "tag", "2.0.0");
        s.env("JPM_GIT_BASE_GITHUB", tmp.resolve("git").toUri().toString());
        Fs.write(s.project.resolve("src/app/Main.java"),
                "package app; public class Main { public static void main(String[] a) { System.out.println(lib.Lib.v()); } }");
        Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"java\": \"21\","
                + " \"main\": \"app.Main\", \"runtime\": { \"github:me/maven-lib\": \"2.0.0\" } }");
        s.jpm("install");
        s.jpm("start");
        s.verify("git-maven");
    }
}
