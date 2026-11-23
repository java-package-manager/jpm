package dev.jpm.importer;

import dev.jpm.Layout;
import dev.jpm.Manifest;
import dev.jpm.importer.impl.GradleScripts;
import dev.jpm.importer.impl.MavenImport;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportTest {
    private static void pom(Path dir, String body) {
        Fs.write(dir.resolve("pom.xml"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + body + "</project>");
    }

    @Test
    void importsMavenMultiModule(@TempDir Path dir) {
        pom(dir, """
                <groupId>com.acme</groupId><artifactId>parent</artifactId><version>2.0.0</version><packaging>pom</packaging>
                <properties><maven.compiler.release>17</maven.compiler.release><v>3.14.0</v></properties>
                <modules><module>util</module><module>app</module></modules>
                <dependencyManagement><dependencies>
                  <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>${v}</version></dependency>
                </dependencies></dependencyManagement>
                <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><configuration>
                  <annotationProcessorPaths><path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.34</version></path></annotationProcessorPaths>
                </configuration></plugin></plugins></build>
                """);
        pom(dir.resolve("util"), """
                <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>2.0.0</version></parent>
                <artifactId>util</artifactId>
                <dependencies>
                  <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId></dependency>
                  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl</artifactId><version>3.3.4</version><classifier>natives-linux</classifier></dependency>
                  <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-simple</artifactId><version>2.0.16</version><scope>runtime</scope>
                    <exclusions><exclusion><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></exclusion></exclusions></dependency>
                  <dependency><groupId>javax.servlet</groupId><artifactId>servlet-api</artifactId><version>2.5</version><scope>provided</scope></dependency>
                </dependencies>
                """);
        pom(dir.resolve("app"), """
                <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>2.0.0</version></parent>
                <artifactId>app</artifactId>
                <dependencies>
                  <dependency><groupId>com.acme</groupId><artifactId>util</artifactId><version>${project.version}</version></dependency>
                  <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency>
                </dependencies>
                <build><plugins><plugin><artifactId>maven-jar-plugin</artifactId><configuration>
                  <archive><manifest><mainClass>com.acme.App</mainClass></manifest></archive>
                </configuration></plugin><plugin><artifactId>protobuf-maven-plugin</artifactId></plugin></plugins></build>
                """);
        Imported imp = MavenImport.run(dir, new MavenRepo(List.of()));
        assertEquals(List.of("", "util", "app"), List.copyOf(imp.manifests.keySet()));
        assertEquals(List.of("util", "app"), imp.manifests.get("").get("workspace"));
        assertEquals("2.0.0", imp.manifests.get("").get("version"));

        Map<String, Object> util = imp.manifests.get("util");
        assertEquals("maven", util.get("layout"));
        assertEquals("17", util.get("target"));
        assertEquals(Map.of("org.apache.commons:commons-lang3", "3.14.0",
                "org.lwjgl:lwjgl:natives-linux", "3.3.4",
                "org.slf4j:slf4j-simple", Map.of("version", "2.0.16", "exclude", List.of("org.slf4j:slf4j-api"), "runtimeOnly", true)),
                util.get("runtime"));
        Manifest um = Manifest.parse(Path.of("util/project.jsonc"), imp.render("util"));
        assertTrue(um.dependencies.stream().anyMatch(d -> d.runtimeOnly() && d.exclusions().contains("org.slf4j:slf4j-api")));
        assertEquals(Map.of("javax.servlet:servlet-api", "2.5", "org.projectlombok:lombok", "1.18.34"), util.get("dev"));

        Map<String, Object> app = imp.manifests.get("app");
        assertEquals("com.acme.App", app.get("main"));
        assertEquals(Map.of("workspace:util", "*"), app.get("runtime"));
        assertEquals(Map.of("org.junit.jupiter:junit-jupiter", "5.11.4"), app.get("test"));
        assertTrue(imp.warnings.stream().anyMatch(w -> w.contains("protobuf-maven-plugin")), imp.warnings.toString());

        Manifest m = Manifest.parse(Path.of("app/project.jsonc"), imp.render("app"));
        assertEquals(Layout.MAVEN, m.layout);
        assertEquals(17, m.targetMajor());
    }

    @Test
    void readsGradleBuildsAsText(@TempDir Path dir) {
        Fs.write(dir.resolve("settings.gradle"), "rootProject.name = 'demo'\ninclude ':lib', ':app'\n");
        Fs.write(dir.resolve("gradle.properties"), "group=com.example\nversion=1.2.0\n");
        Fs.write(dir.resolve("gradle/libs.versions.toml"), """
                [versions]
                guava = "33.3.1-jre"
                [libraries]
                guava = { module = "com.google.guava:guava", version.ref = "guava" }
                commons-lang3 = { group = "org.apache.commons", name = "commons-lang3", version = "3.14.0" }
                [bundles]
                common = ["guava", "commons-lang3"]
                """);
        Fs.write(dir.resolve("build.gradle"), "subprojects { apply plugin: 'java'\n java { toolchain { languageVersion = JavaLanguageVersion.of(21) } } }\n");
        Fs.write(dir.resolve("lib/build.gradle"), """
                plugins { id 'java-library' }
                dependencies {
                    api libs.bundles.common
                    // a comment: implementation 'not:real:1'
                    compileOnly 'org.projectlombok:lombok:1.18.34'
                    compileOnly 'io.papermc.paper:paper-api:26.3.build.+'
                    compileOnlyApi 'org.jspecify:jspecify:1.0.0'
                    testImplementation group: 'org.junit.jupiter', name: 'junit-jupiter', version: '5.11.4'
                }
                """);
        Fs.write(dir.resolve("app/build.gradle.kts"), """
                plugins { application }
                application { mainClass.set("com.example.Main") }
                dependencies {
                    implementation(project(":lib"))
                    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
                }
                """);
        Imported imp = GradleScripts.runStatic(dir);
        assertEquals(List.of("lib", "app"), imp.manifests.get("").get("workspace"));
        Map<String, Object> lib = imp.manifests.get("lib");
        assertEquals("com.example", lib.get("group"));
        assertEquals("21", lib.get("target"));
        assertEquals(Map.of("com.google.guava:guava", "33.3.1-jre", "org.apache.commons:commons-lang3", "3.14.0"), lib.get("runtime"));
        assertEquals(Map.of("org.projectlombok:lombok", "1.18.34", "io.papermc.paper:paper-api", "26.3.build.+",
                "org.jspecify:jspecify", Map.of("version", "1.0.0", "api", true)), lib.get("dev"));
        assertEquals(Map.of("org.junit.jupiter:junit-jupiter", "5.11.4"), lib.get("test"));
        Map<String, Object> app = imp.manifests.get("app");
        assertEquals("com.example.Main", app.get("main"));
        assertEquals(Map.of("workspace:lib", "*",
                "org.slf4j:slf4j-simple", Map.of("version", "2.0.16", "runtimeOnly", true)), app.get("runtime"));
        assertNull(app.get("dev"));
        assertTrue(imp.warnings.stream().noneMatch(w -> w.contains("root project applies")), imp.warnings.toString());
    }

    /**
     * Kotlin DSL builds as a real multi-module project writes them (cases from a migrated server build).
     */
    @Test
    void readsKotlinBuildsAsText(@TempDir Path dir) {
        Fs.write(dir.resolve("settings.gradle.kts"), """
                pluginManagement {
                    repositories { maven("https://repo.example.org/plugins/") }
                    includeBuild("build-logic")
                }
                rootProject.name = "server" // the root
                enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
                include("server-api")
                include("server-core")
                dependencyResolutionManagement {
                    versionCatalogs { create("libs") { from(files("catalog/deps.versions.toml")) } }
                }
                """);
        Fs.write(dir.resolve("catalog/deps.versions.toml"), """
                [versions]
                netty = "4.2.19.Final"
                [libraries]
                netty-buffer = { module = "io.netty:netty-buffer", version.ref = "netty" }
                netty-epoll = { module = "io.netty:netty-transport-native-epoll", version.ref = "netty" }
                gson = "com.google.code.gson:gson:2.15.0"
                [bundles]
                netty = [
                    "netty-buffer",
                ]
                """);
        Fs.write(dir.resolve("build.gradle.kts"), "allprojects {\n    group = \"org.example\"\n}\n");
        Fs.write(dir.resolve("server-api/build.gradle.kts"), """
                plugins { `java-library` }
                repositories {
                    maven("https://repo.example.org/releases/") // releases
                    maven("https://repo.example.org/snapshots/")
                }
                dependencies {
                    api(libs.gson)
                }
                tasks.register("check-all") {
                    group = "verification"
                }
                """);
        Fs.write(dir.resolve("server-core/build.gradle.kts"), """
                plugins { java }
                tasks.named("jar") { group = "build" }
                configurations.named("api")
                dependencies {
                    implementation(projects.serverApi) {
                        exclude(group = "com.google.code.gson", module = "gson")
                    }
                    implementation(libs.bundles.netty)
                    for (platform in listOf("linux-x86_64", "linux-aarch_64")) {
                        runtimeOnly(variantOf(libs.netty.epoll) { classifier(platform) })
                    }
                }
                """);
        Imported imp = GradleScripts.runStatic(dir);
        assertEquals(List.of("server-api", "server-core"), imp.manifests.get("").get("workspace"), "includeBuild is another build");
        assertEquals(List.of("https://repo.example.org/releases/", "https://repo.example.org/snapshots/"),
                imp.manifests.get("").get("repositories"));
        Map<String, Object> api = imp.manifests.get("server-api"), core = imp.manifests.get("server-core");
        assertEquals("org.example", api.get("group"), "a task's group is not the project's");
        assertEquals("org.example", core.get("group"));
        assertEquals(Map.of("com.google.code.gson:gson", "2.15.0"), api.get("runtime"));
        @SuppressWarnings("unchecked") Map<String, Object> runtime = (Map<String, Object>) core.get("runtime");
        assertEquals(Map.of("version", "*", "exclude", List.of("com.google.code.gson:gson")), runtime.get("workspace:server-api"));
        assertEquals("4.2.19.Final", runtime.get("io.netty:netty-buffer"));
        assertEquals(Map.of("version", "4.2.19.Final", "runtimeOnly", true), runtime.get("io.netty:netty-transport-native-epoll:linux-x86_64"));
        assertEquals(Map.of("version", "4.2.19.Final", "runtimeOnly", true), runtime.get("io.netty:netty-transport-native-epoll:linux-aarch_64"));
        assertTrue(imp.warnings.stream().noneMatch(w -> w.contains("could not read")), imp.warnings.toString());
    }

    @Test
    void stripsCommentsButNotUrls() {
        assertEquals("maven(\"https://a.b/c\") \nx  \"/* kept */\"", GradleScripts.stripComments("maven(\"https://a.b/c\") // gone\nx /* gone */\"/* kept */\""));
    }

    @Test
    void readsJavaVersionsAndMainClasses() {
        assertEquals(17, GradleScripts.javaVersion("java { sourceCompatibility = JavaVersion.VERSION_17 }"));
        assertEquals(8, GradleScripts.javaVersion("sourceCompatibility = '1.8'"));
        assertEquals(21, GradleScripts.javaVersion("tasks.withType<JavaCompile> { options.release.set(21) }"));
        assertEquals(11, GradleScripts.javaVersion("kotlin { jvmToolchain(11) }"));
        assertEquals("a.B", GradleScripts.mainClass("application { mainClassName = 'a.B' }"));
        assertEquals("21", Imported.jdkFor(21));
        assertEquals("17", Imported.jdkFor(8));
        assertEquals(8, Imported.major("1.8"));
        assertEquals(17, Imported.major("VERSION_17"));
    }

    @Test
    void stripsSubprojectBlocks() {
        String s = GradleScripts.withoutBlocks("a { x }\nsubprojects { apply plugin: 'java'\n inner { y } }\nb", "subprojects");
        assertEquals("a { x }\n\nb", s);
    }
}
