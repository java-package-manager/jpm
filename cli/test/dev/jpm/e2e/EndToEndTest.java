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

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void removedCommandsSayWhatReplacesThem(@TempDir Path tmp) {
        Session s = new Session(tmp);
        Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\" }");
        s.jpm("schema");
        s.jpm("info", "--json");
        s.jpm("info");
        s.verify("removed-commands");
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
    void addsAnExtensionInAWorkspaceRoot(@TempDir Path tmp) {
        Session s = new Session(tmp);
        money(s.repo);
        Path ext = tmp.resolve("git/me/jpm-hello");
        Fs.write(ext.resolve("src/hello/Main.java"), "package hello; public class Main { public static void main(String[] a) {} }");
        Fs.write(ext.resolve("project.jsonc"), "{ \"name\": \"jpm-hello\", \"version\": \"1.0.0\", \"main\": \"hello.Main\", \"extension\": {} }");
        git(ext, "init", "-q", "-b", "main");
        git(ext, "add", "-A");
        git(ext, "commit", "-qm", "1.0.0");
        git(ext, "tag", "1.0.0");
        s.env("JPM_GIT_BASE_GITHUB", tmp.resolve("git").toUri().toString());
        Fs.write(s.project.resolve("project.jsonc"), "{\n  \"name\": \"ws\",\n  \"version\": \"1.0.0\",\n  \"workspace\": [\"a\", \"b\"]\n}\n");
        Fs.write(s.project.resolve("a/project.jsonc"), "{ \"name\": \"a\" }\n");
        Fs.write(s.project.resolve("b/project.jsonc"), "{ \"name\": \"b\" }\n");
        s.jpm("add", "github:me/jpm-hello");
        s.show("project.jsonc");
        s.jpm("add", "org.example:money");
        s.verify("add-extension-in-workspace");
    }

    @Test
    void audit(@TempDir Path tmp) throws Exception {
        Session s = new Session(tmp);
        money(s.repo);
        s.repo.artifact("org.example:shop:1.0.0").dependsOn("org.example:money:1.0.0").publish();
        com.sun.net.httpserver.HttpServer osv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        osv.createContext("/v1/querybatch", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder("{\"results\": [");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"name\":\\s*\"([^\"]+)\"\\s*\\},\\s*\"version\":\\s*\"([^\"]+)\"").matcher(body);
            boolean first = true;
            while (m.find()) {
                out.append(first ? "" : ", ").append(m.group(1).equals("org.example:money") && m.group(2).equals("1.0.0")
                        ? "{\"vulns\": [{\"id\": \"GHSA-0000-1111-2222\"}, {\"id\": \"GHSA-3333-4444-5555\"}]}" : "{}");
                first = false;
            }
            byte[] b = out.append("]}").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        osv.createContext("/v1/vulns/", ex -> {
            String id = ex.getRequestURI().getPath().substring("/v1/vulns/".length());
            String body = id.equals("GHSA-0000-1111-2222")
                    ? "{\"id\": \"GHSA-0000-1111-2222\", \"aliases\": [\"CVE-2026-0001\"], \"summary\": \"Rounding leaks cents\","
                            + " \"database_specific\": {\"severity\": \"HIGH\"}, \"affected\": [{\"package\": {\"ecosystem\": \"Maven\","
                            + " \"name\": \"org.example:money\"}, \"ranges\": [{\"type\": \"ECOSYSTEM\", \"events\": [{\"introduced\": \"0\"},"
                            + " {\"fixed\": \"0.9.0\"}, {\"introduced\": \"1.0.0\"}, {\"fixed\": \"1.2.0\"}]}]}]}"
                    : "{\"id\": \"GHSA-3333-4444-5555\", \"aliases\": [], \"summary\": \"Money is not a number\", \"affected\": []}";
            byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        osv.start();
        try {
            s.env("JPM_OSV_URL", "http://127.0.0.1:" + osv.getAddress().getPort());
            Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"runtime\": { \"org.example:shop\": \"1.0.0\" } }");
            s.jpm("audit");
            s.jpm("audit", "--ignore", "CVE-2026-0001", "--ignore", "GHSA-3333-4444-5555");
            s.jpm("audit", "--json", "--ignore", "GHSA-3333-4444-5555");
            Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"runtime\": { \"org.example:money\": \"2.0.0\" } }");
            s.jpm("audit");
        } finally {
            osv.stop(0);
        }
        s.verify("audit");
    }

    @Test
    void lockfileMergeConflicts(@TempDir Path tmp) {
        Session s = new Session(tmp);
        money(s.repo);
        s.repo.artifact("org.example:tax:1.0.0").publish();
        s.repo.artifact("org.example:tax:1.1.0").publish();
        Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\","
                + " \"runtime\": { \"org.example:money\": \"^1.0.0\", \"org.example:tax\": \"^1.0.0\" } }");
        Fs.write(s.project.resolve("project.lock"), "{ \"lockfileVersion\": 1, \"inputs\": \"\", \"packages\": {"
                + " \"org.example:money\": { \"version\": \"1.0.0\" }, \"org.example:tax\": { \"version\": \"1.0.0\" } } }");
        s.jpmUnrecorded("install");
        String base = Fs.read(s.project.resolve("project.lock"));
        s.jpmUnrecorded("update");
        String updated = Fs.read(s.project.resolve("project.lock"));
        String money = line(base, "org.example:money"), tax = line(base, "org.example:tax");
        String theirsMoney = line(updated, "org.example:money");
        Fs.write(s.project.resolve("project.lock"), base.replace(money + ",\n" + tax, "<<<<<<< HEAD\n" + money + ",\n"
                + line(updated, "org.example:tax") + "\n=======\n" + theirsMoney + "\n>>>>>>> feature"));
        s.show("project.lock");
        s.jpm("install", "--frozen");
        s.jpm("install");
        s.show("project.lock");
        s.jpm("install", "--frozen");
        s.verify("lockfile-conflicts");
    }

    @Test
    void privateRepositories(@TempDir Path tmp) throws Exception {
        Session s = new Session(tmp);
        TestRepo secret = new TestRepo(tmp.resolve("private"));
        secret.artifact("com.acme:internal:1.0.0").publish();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.List<String> cdnAuth = new java.util.ArrayList<>();
        server.createContext("/releases/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (!("Basic " + java.util.Base64.getEncoder().encodeToString("me:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8))).equals(auth)) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("Location", "http://localhost:" + server.getAddress().getPort() + "/cdn/"
                    + ex.getRequestURI().getPath().substring("/releases/".length()));
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/cdn/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null) cdnAuth.add(auth);
            Path f = secret.root().resolve(ex.getRequestURI().getPath().substring("/cdn/".length()));
            if (!java.nio.file.Files.isRegularFile(f)) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            byte[] b = java.nio.file.Files.readAllBytes(f);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/releases";
            Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"repositories\": [\""
                    + url + "\"], \"runtime\": { \"com.acme:internal\": \"1.0.0\" } }");
            String out = s.jpmUnrecorded("install");
            org.junit.jupiter.api.Assertions.assertTrue(out.contains("set JPM_REPO_127_0_0_1_USERNAME and JPM_REPO_127_0_0_1_PASSWORD"), out);
            s.env("JPM_REPO_127_0_0_1_USERNAME", "me").env("JPM_REPO_127_0_0_1_PASSWORD", "secret");
            s.jpm("install");
        } finally {
            server.stop(0);
        }
        assertEquals(List.of(), cdnAuth, "credentials do not follow a redirect to another host");
        s.verify("private-repositories");
    }

    @Test
    void cacheSizeAndClean(@TempDir Path tmp) {
        Session s = new Session(tmp);
        money(s.repo);
        Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"runtime\": { \"org.example:money\": \"1.0.0\" } }");
        s.jpm("install");
        Fs.write(s.home.resolve("global").resolve("keep.txt"), "an installed extension");
        String size = s.jpmUnrecorded("cache", "size");
        org.junit.jupiter.api.Assertions.assertTrue(size.matches("(?s).*\\d+(\\.\\d)? (B|KB|MB)  repository +Maven packages\n.*total.*"), size);
        String clean = s.jpmUnrecorded("cache", "clean", "repository");
        org.junit.jupiter.api.Assertions.assertTrue(clean.contains("Cleaned repository: "), clean);
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(s.home.resolve("repository")));
        s.jpm("cache", "clean", "nothing");
        s.jpm("cache", "frobnicate");
        s.jpm("install", "--frozen");
        s.jpmUnrecorded("cache", "clean");
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(s.home.resolve("global").resolve("keep.txt")));
        s.verify("cache");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void workspaceFilters(@TempDir Path tmp) {
        Session s = new Session(tmp).fixture("workspace-demo");
        money(s.repo);
        Fs.write(s.project.resolve("core/project.jsonc"), "{ \"name\": \"core\", \"runtime\": { \"org.example:money\": \"^1.0.0\" }, \"test\": { \"org.junit.jupiter:junit-jupiter\": \"*\" },"
                + " \"scripts\": { \"where\": \"echo core in $PWD\", \"fail\": \"exit 2\" } }");
        Fs.write(s.project.resolve("app/project.jsonc"), "{ \"name\": \"app\", \"main\": \"shop.app.Main\", \"define\": { \"GREETING\": \"Welcome\" },"
                + " \"defineClass\": \"shop.app.Config\", \"runtime\": { \"workspace:core\": \"*\" },"
                + " \"scripts\": { \"where\": \"echo app in $PWD\", \"fail\": \"exit 3\" } }");
        s.jpmUnrecorded("install");
        s.jpm("run", "-r", "where");
        s.jpm("run", "--filter", "app", "where");
        s.jpm("run", "--filter", "app...", "where");
        s.jpm("run", "--filter", "...core", "where");
        s.jpm("run", "-r", "--filter", "!core", "where");
        s.jpm("run", "--filter", "./a*", "where");
        s.jpm("run", "-r", "fail");
        s.jpm("run", "-r", "nothing");
        s.jpm("run", "--filter", "nope", "where");
        s.jpm("build", "--filter", "core");
        s.verify("workspace-filters");
    }

    @Test
    void testReports(@TempDir Path tmp) {
        Session s = new Session(tmp).fixture("workspace-demo");
        money(s.repo);
        s.jpmUnrecorded("test", "--reports", "reports");
        s.tree("reports");
        String xml = Fs.read(s.project.resolve("reports/core/TEST-junit-jupiter.xml"));
        org.junit.jupiter.api.Assertions.assertTrue(xml.contains("<testsuite") && xml.contains("CartTest"), xml);
        s.jpmUnrecorded("test", "--filter", "core", "--reports", "core-reports");
        s.tree("core-reports");
        s.verify("test-reports");
    }

    private static String line(String lock, String id) {
        return lock.lines().filter(l -> l.contains("\"" + id + "\"")).findFirst().orElseThrow().replaceAll(",$", "");
    }

    @Test
    void sbom(@TempDir Path tmp) {
        Session s = new Session(tmp);
        s.repo.artifact("org.example:money:1.0.0").javaClass("org.example.money.Money", MONEY_V1)
                .pom("<licenses><license><name>The Apache Software License, Version 2.0</name></license></licenses>").publish();
        s.repo.artifact("org.example:shop:1.0.0").dependsOn("org.example:money:1.0.0")
                .pom("<licenses><license><name>Shop Proprietary License</name><url>https://shop.example/license</url></license></licenses>").publish();
        s.repo.artifact("org.example:checks:1.0.0").publish();
        Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"group\": \"org.example\","
                + " \"license\": \"MIT\", \"main\": \"app.Main\", \"runtime\": { \"org.example:shop\": \"1.0.0\" },"
                + " \"test\": { \"org.example:checks\": \"1.0.0\" } }");
        s.jpm("sbom");
        s.jpm("sbom", "-o", "bom.json");
        String first = Fs.read(s.project.resolve("bom.json"));
        s.jpmUnrecorded("sbom", "-o", "bom.json");
        assertEquals(first, Fs.read(s.project.resolve("bom.json")), "the same lock gives the same SBOM");
        s.jpm("sbom", "extra");
        s.verify("sbom");
    }

    @Test
    void licenses(@TempDir Path tmp) {
        Session s = new Session(tmp);
        Path spdx = tmp.resolve("spdx");
        Fs.write(spdx.resolve("MIT.json"), "{ \"licenseId\": \"MIT\", \"licenseText\": \"MIT License\\n\\nCopyright (c) <copyright holders>\\n\\n"
                + "Permission is hereby granted, free of charge, to any person obtaining a copy of this software.\\n\\n"
                + "The above copyright notice and this permission notice shall be included in all copies.\\n\" }");
        s.env("JPM_SPDX_BASE", spdx.toUri().toString());
        Fs.write(s.project.resolve("project.jsonc"), "{\n  \"name\": \"app\",\n  \"version\": \"1.0.0\"\n}\n");
        s.jpm("license");
        s.jpm("license", "Apach-2.0");
        s.jpm("license", "MIT", "--author", "Ann Smith");
        s.show("project.jsonc").show("LICENSE");
        s.jpm("license");
        s.jpm("check", "--no-test", "license");
        Fs.write(s.project.resolve("project.jsonc"), "{\n  \"name\": \"app\",\n  \"version\": \"1.0.0\",\n  \"license\": \"Apache-2.0\"\n}\n");
        s.jpm("check", "--no-test", "license");
        Fs.write(s.project.resolve("project.jsonc"), "{\n  \"name\": \"app\",\n  \"version\": \"1.0.0\"\n}\n");
        s.jpm("check", "--no-test", "license");
        Fs.deleteTree(s.project.resolve("LICENSE"));
        Fs.write(s.project.resolve("project.jsonc"), "{\n  \"name\": \"app\",\n  \"version\": \"1.0.0\",\n  \"license\": \"MIT\"\n}\n");
        s.jpm("check", "--no-test", "license");
        Fs.write(s.project.resolve("new/.keep"), "");
        s.jpmIn("new", "init", "--license", "MIT", "--author", "Ann Smith");
        s.show("new/project.jsonc");
        Fs.write(s.project.resolve("lib/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId><artifactId>lib</artifactId><version>1.0.0</version>
                  <licenses><license><name>The Apache Software License, Version 2.0</name>
                    <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses></project>
                """);
        s.jpmIn("lib", "import");
        s.show("lib/project.jsonc");
        s.verify("licenses");
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
        s.jpm("check", "--no-test", "pahts");
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
    void gitMirrorsFollowTheirUrl(@TempDir Path tmp) {
        Session s = new Session(tmp);
        for (String base : List.of("old", "new")) {
            Path lib = tmp.resolve(base + "/me/lib");
            Fs.write(lib.resolve("src/lib/Lib.java"), "package lib; public class Lib {}");
            Fs.write(lib.resolve("project.jsonc"), "{ \"name\": \"lib\", \"version\": \"1.0.0\" }");
            git(lib, "init", "-q", "-b", "main");
            git(lib, "add", "-A");
            git(lib, "commit", "-qm", "1.0.0");
            git(lib, "tag", "1.0.0");
        }
        Path moved = tmp.resolve("new/me/lib");
        Fs.write(moved.resolve("project.jsonc"), "{ \"name\": \"lib\", \"version\": \"1.1.0\" }");
        git(moved, "commit", "-qam", "1.1.0");
        git(moved, "tag", "1.1.0");
        Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"runtime\": { \"github:me/lib\": \"^1.0.0\" } }");
        s.env("JPM_GIT_BASE_GITHUB", tmp.resolve("old").toUri().toString());
        s.jpm("install");
        s.env("JPM_GIT_BASE_GITHUB", tmp.resolve("new").toUri().toString());
        s.jpm("update");
        s.verify("git-mirror-moved");
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
