package dev.jpm.cli;

import dev.jpm.api.json.Jsonc;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code jpm info --json --classpath}: every classpath as packages with coordinates, jars and edges. */
class InfoClasspathTest {
    private static void publish(Path repo, String a, String deps) throws Exception {
        Path dir = repo.resolve("g/" + a + "/1.0");
        Fs.write(dir.resolve(a + "-1.0.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>" + a
                + "</artifactId><version>1.0</version><dependencies>" + deps + "</dependencies></project>");
        Files.createDirectories(dir);
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(dir.resolve(a + "-1.0.jar")))) {
            out.putNextEntry(new java.util.zip.ZipEntry(a + "/Marker.txt"));
            out.write(a.getBytes());
            out.closeEntry();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsPackagesPerClasspath(@TempDir Path dir) throws Exception {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path repo = dir.resolve("repo");
        publish(repo, "a", "<dependency><groupId>g</groupId><artifactId>b</artifactId><version>1.0</version></dependency>");
        publish(repo, "b", "");
        publish(repo, "proc", "");
        Path ws = dir.resolve("ws");
        Fs.write(ws.resolve("project.jsonc"), "{ \"name\": \"ws\", \"version\": \"2.0.0\", \"group\": \"org.example\","
                + " \"workspace\": [\"core\", \"app\"], \"repositories\": [\"" + repo.toUri() + "\"] }");
        Fs.write(ws.resolve("core/project.jsonc"), "{ \"name\": \"core\", \"group\": \"org.example\" }");
        Fs.write(ws.resolve("core/src/core/Core.java"), "package core; public class Core {}");
        Fs.write(ws.resolve("app/project.jsonc"), "{ \"name\": \"app\", \"runtime\": { \"g:a\": \"1.0\", \"workspace:core\": \"*\" },"
                + " \"dev\": { \"g:proc\": \"1.0\" }, \"define\": { \"GREETING\": \"hi\" } }");

        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        try {
            assertEquals(0, new Commands(ws).info(new ArrayList<>(List.of("--json", "--classpath"))));
        } finally {
            System.setOut(old);
        }
        Map<String, Object> info = (Map<String, Object>) Jsonc.parse(buf.toString());
        Map<String, Object> app = null;
        for (Object o : (List<Object>) info.get("modules")) {
            if ("app".equals(((Map<String, Object>) o).get("name"))) app = (Map<String, Object>) o;
        }
        Map<String, Object> cps = (Map<String, Object>) app.get("classpaths");
        List<Map<String, Object>> runtime = (List<Map<String, Object>>) cps.get("runtime");
        List<String> ids = runtime.stream().map(p -> (String) p.get("id")).toList();
        assertEquals(List.of("g:a", "workspace:core", "g:b"), ids);

        Map<String, Object> a = runtime.get(0);
        assertEquals("maven", a.get("kind"));
        assertEquals("g", a.get("group"));
        assertEquals("a", a.get("artifact"));
        assertEquals("1.0", a.get("version"));
        assertTrue(((String) a.get("jar")).endsWith("a-1.0.jar"), (String) a.get("jar"));
        assertEquals(List.of(Map.of("id", "g:b", "scope", "compile")), a.get("dependencies"));

        Map<String, Object> core = runtime.get(1);
        assertEquals("workspace", core.get("kind"));
        assertEquals("org.example", core.get("group"));
        assertEquals("2.0.0", core.get("version"));
        assertTrue(((String) core.get("classes")).replace('\\', '/').endsWith(".cache/build/core/classes"), (String) core.get("classes"));

        List<Map<String, Object>> proc = (List<Map<String, Object>>) cps.get("processor");
        assertEquals(List.of("g:proc"), proc.stream().map(p -> p.get("id")).toList());
        assertEquals(Map.of("GREETING", "hi"), app.get("defines"));
        assertNull(app.get("compileModule"), "no module-info, no compileModule");
    }

    /**
     * A named module gets the module path / class path split javac compiles it with, so an extension
     * running javadoc does not put every jar on the module path, where two jars without module-info
     * sharing a package stop it ("reads package p from both a and b").
     */
    @Test
    @SuppressWarnings("unchecked")
    void splitsCompileClasspathForNamedModule(@TempDir Path dir) throws Exception {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path repo = dir.resolve("repo");
        publish(repo, "a", "<dependency><groupId>g</groupId><artifactId>b</artifactId><version>1.0</version></dependency>");
        publish(repo, "b", "");
        Path ws = dir.resolve("ws");
        Fs.write(ws.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\","
                + " \"repositories\": [\"" + repo.toUri() + "\"], \"runtime\": { \"g:a\": \"1.0\" } }");
        Fs.write(ws.resolve("src/module-info.java"), "module com.example.app { requires a; }");
        Fs.write(ws.resolve("src/com/example/app/App.java"), "package com.example.app; public class App {}");

        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        try {
            assertEquals(0, new Commands(ws).info(new ArrayList<>(List.of("--json", "--classpath"))));
        } finally {
            System.setOut(old);
        }
        Map<String, Object> info = (Map<String, Object>) Jsonc.parse(buf.toString());
        Map<String, Object> app = (Map<String, Object>) ((List<Object>) info.get("modules")).get(0);
        Map<String, Object> cm = (Map<String, Object>) app.get("compileModule");
        assertEquals("com.example.app", cm.get("name"));
        List<String> mp = (List<String>) cm.get("modulePath"), cp = (List<String>) cm.get("classPath");
        assertEquals(1, mp.size());
        assertTrue(mp.get(0).endsWith("a-1.0.jar"), mp.get(0));
        assertEquals(1, cp.size());
        assertTrue(cp.get(0).endsWith("b-1.0.jar"), cp.get(0));
        assertEquals(List.of("--module-path", mp.get(0), "-cp", cp.get(0), "--add-reads", "com.example.app=ALL-UNNAMED"),
                cm.get("options"));
    }
}
