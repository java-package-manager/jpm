package dev.jpm.toolchain;

import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolchainTest {
    private static Jdk jdk(String dist, String version, boolean graal) {
        int major = Integer.parseInt(version.split("[.+]")[0]);
        return new Jdk(Path.of("/jdk"), major, version, graal, dist, false);
    }

    @Test
    void parsesSpecs() {
        assertEquals(new JavaSpec(null, "21"), JavaSpec.parse("21"));
        assertEquals(new JavaSpec("temurin", "17"), JavaSpec.parse("temurin-17"));
        assertEquals(new JavaSpec("graalvm_community", "21"), JavaSpec.parse("graalvm-21"));
        assertEquals(new JavaSpec("graalvm", "21"), JavaSpec.parse("oracle-graalvm-21"));
        assertEquals(new JavaSpec("zulu", "21.0.5"), JavaSpec.parse("Zulu-21.0.5"));
        assertEquals(new JavaSpec("sap_machine", "21"), JavaSpec.parse("sapmachine-21"));
        assertEquals(new JavaSpec(null, "8"), JavaSpec.parse("1.8"));
        assertEquals(new JavaSpec("corretto", null), JavaSpec.parse("corretto"));
        assertEquals(JavaSpec.any(), JavaSpec.parse(null));
        assertThrows(JpmException.class, () -> JavaSpec.parse("temurin-x"));
        assertEquals("graalvm-21", JavaSpec.parse("graalvm-21").toString());
        assertTrue(JavaSpec.parse("graalvm-21").graal());
        assertEquals(new JavaSpec("graalvm_community", "17"), JavaSpec.parse("17").asGraal());
        assertEquals(new JavaSpec("mandrel", "21"), JavaSpec.parse("mandrel-21").asGraal());
    }

    @Test
    void matchesJdks() {
        Jdk temurin = jdk("temurin", "21.0.5", false);
        Jdk graal = jdk("graalvm_community", "21.0.2", true);
        assertTrue(JavaSpec.parse("21").matches(temurin));
        assertTrue(JavaSpec.parse("21").matches(graal));
        assertFalse(JavaSpec.parse("17").matches(temurin));
        assertTrue(JavaSpec.parse("temurin-21").matches(temurin));
        assertFalse(JavaSpec.parse("zulu-21").matches(temurin));
        assertTrue(JavaSpec.parse("graalvm-21").matches(graal));
        assertFalse(JavaSpec.parse("graalvm-21").matches(temurin));
        assertTrue(JavaSpec.parse("21.0.5").matches(temurin));
        assertTrue(JavaSpec.parse("21.0").matches(temurin));
        assertFalse(JavaSpec.parse("21.0.50").matches(temurin));
        assertTrue(JavaSpec.versionMatches("21.0.5+11", "21.0.5"));
        assertFalse(JavaSpec.versionMatches("21.0.51", "21.0.5"));
    }

    @Test
    void choosesPackages() {
        String json = """
                { "result": [
                  { "id": "a", "distribution": "temurin", "java_version": "21.0.4+7", "jdk_version": 21, "filename": "a.tar.gz",
                    "archive_type": "tar.gz", "lib_c_type": "glibc", "javafx_bundled": false, "links": { "pkg_info_uri": "u/a" } },
                  { "id": "b", "distribution": "temurin", "java_version": "21.0.5+11", "jdk_version": 21, "filename": "b.tar.gz",
                    "archive_type": "tar.gz", "lib_c_type": "musl", "javafx_bundled": false, "links": { "pkg_info_uri": "u/b" } },
                  { "id": "c", "distribution": "temurin", "java_version": "21.0.5+11", "jdk_version": 21, "filename": "c.tar.gz",
                    "archive_type": "tar.gz", "lib_c_type": "glibc", "javafx_bundled": true, "links": { "pkg_info_uri": "u/c" } },
                  { "id": "d", "distribution": "temurin", "java_version": "17.0.9+9", "jdk_version": 17, "filename": "d.tar.gz",
                    "archive_type": "tar.gz", "lib_c_type": "glibc", "javafx_bundled": false, "links": { "pkg_info_uri": "u/d" } }
                ] }
                """;
        List<Foojay.Package> all = Foojay.parsePackages(json);
        assertEquals(3, all.size(), "JavaFX bundles are skipped");
        assertEquals("a", Foojay.choose(all, JavaSpec.parse("21"), "glibc").id());
        assertEquals("b", Foojay.choose(all, JavaSpec.parse("21"), "musl").id());
        assertEquals("d", Foojay.choose(all, JavaSpec.parse("17"), "glibc").id());
        assertNull(Foojay.choose(all, JavaSpec.parse("21.0.9"), "glibc"));
    }

    @Test
    void extractsZipsIntoUnnormalizedPaths(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("a.zip");
        try (var out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("jdk/bin/x"));
            out.write(1);
            out.closeEntry();
        }
        Path target = dir.resolve("sub").resolve("..").resolve("out");
        dev.jpm.build.Jars.extract(zip, target);
        assertTrue(java.nio.file.Files.exists(dir.resolve("out/jdk/bin/x")));
    }

    @Test
    void prefersTarOverZip() {
        Foojay.Package zip = new Foojay.Package("z", "zulu", "21.0.5", 21, "a.zip", "zip", "", "u");
        Foojay.Package tar = new Foojay.Package("t", "zulu", "21.0.5", 21, "a.tar.gz", "tar.gz", "", "u");
        assertEquals("t", Foojay.choose(List.of(zip, tar), JavaSpec.parse("21"), null).id());
        assertEquals("t", Foojay.choose(List.of(tar, zip), JavaSpec.parse("21"), null).id());
    }

    @Test
    void findsNestedJdkHomes(@TempDir Path dir) {
        String javac = System.getProperty("os.name").toLowerCase().contains("win") ? "javac.exe" : "javac";
        Path home = dir.resolve("zulu-21.jdk/Contents/Home");
        Fs.write(home.resolve("bin").resolve(javac), "");
        Fs.write(home.resolve("release"), "IMPLEMENTOR=\"Azul Systems, Inc.\"\nJAVA_VERSION=\"21.0.5\"\n");
        Fs.write(dir.resolve(Jdk.METADATA), "zulu\n21.0.5\n");
        Jdk j = Toolchain.probeInstalled(dir);
        assertEquals(home, j.home());
        assertTrue(j.managed());
        assertEquals("zulu", j.distribution());
    }

    @Test
    void recognizesVendors() {
        assertEquals("temurin", Jdk.distributionOf("Eclipse Adoptium", "", false));
        assertEquals("zulu", Jdk.distributionOf("Azul Systems, Inc.", "", false));
        assertEquals("corretto", Jdk.distributionOf("Amazon.com Inc.", "", false));
        assertEquals("graalvm_community", Jdk.distributionOf("GraalVM Community", "", true));
        assertEquals("graalvm", Jdk.distributionOf("Oracle Corporation", "", true));
        assertEquals("mandrel", Jdk.distributionOf("Red Hat, Inc.", "", true));
        assertEquals("openjdk", Jdk.distributionOf("Ubuntu", "", false));
    }

    @Test
    void probesJdkHomes(@TempDir Path dir) {
        String javac = System.getProperty("os.name").toLowerCase().contains("win") ? "javac.exe" : "javac";
        Fs.write(dir.resolve("bin").resolve(javac), "");
        Fs.write(dir.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"17.0.9\"\n");
        Jdk j = Jdk.probe(dir);
        assertEquals(17, j.major());
        assertEquals("temurin", j.distribution());
        assertFalse(j.managed());
        Fs.write(dir.resolve(Jdk.METADATA), "zulu\n17.0.9\n");
        Jdk m = Jdk.probe(dir);
        assertEquals("zulu", m.distribution());
        assertTrue(m.managed());
    }
}
