package dev.jpm.resolve;

import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Snapshot repositories name files with timestamps; jpm finds them through maven-metadata.xml.
 */
class SnapshotRepoTest {
    @Test
    void fetchesTimestampedSnapshots(@TempDir Path tmp) {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path repo = tmp.resolve("snapshots");
        Path dir = repo.resolve("net/kyori/adventure-text-logger-slf4j/4.26.0-SNAPSHOT");
        Fs.write(dir.resolve("adventure-text-logger-slf4j-4.26.0-20251101.100000-1.pom"), "<project>old</project>");
        Fs.write(dir.resolve("adventure-text-logger-slf4j-4.26.0-20251102.110000-2.pom"), "<project>new</project>");
        Fs.write(dir.resolve("adventure-text-logger-slf4j-4.26.0-20251102.110000-2.jar"), "jar");
        Fs.write(dir.resolve("adventure-text-logger-slf4j-4.26.0-20251102.110000-2-sources.jar"), "sources");
        Fs.write(dir.resolve("maven-metadata.xml"), """
                <metadata>
                  <groupId>net.kyori</groupId><artifactId>adventure-text-logger-slf4j</artifactId><version>4.26.0-SNAPSHOT</version>
                  <versioning>
                    <snapshot><timestamp>20251102.110000</timestamp><buildNumber>2</buildNumber></snapshot>
                    <snapshotVersions>
                      <snapshotVersion><extension>pom</extension><value>4.26.0-20251102.110000-2</value></snapshotVersion>
                      <snapshotVersion><extension>jar</extension><value>4.26.0-20251102.110000-2</value></snapshotVersion>
                      <snapshotVersion><classifier>sources</classifier><extension>jar</extension><value>4.26.0-20251102.110000-2</value></snapshotVersion>
                    </snapshotVersions>
                  </versioning>
                </metadata>
                """);
        MavenRepo r = new MavenRepo(List.of(repo.toUri().toString()));
        assertEquals("<project>new</project>", Fs.read(r.require("net.kyori", "adventure-text-logger-slf4j", "4.26.0-SNAPSHOT", null, "pom")));
        assertEquals("jar", Fs.read(r.require("net.kyori", "adventure-text-logger-slf4j", "4.26.0-SNAPSHOT", null, "jar")));
        assertEquals("sources", Fs.read(r.require("net.kyori", "adventure-text-logger-slf4j", "4.26.0-SNAPSHOT", "sources", "jar")));
    }

    @Test
    void readsMaven2StyleSnapshotMetadata() {
        String meta = "<metadata><versioning><snapshot><timestamp>20240101.000000</timestamp><buildNumber>7</buildNumber></snapshot></versioning></metadata>";
        assertEquals("1.0-20240101.000000-7", MavenRepoAccess.snapshotVersion(meta, "1.0-SNAPSHOT", null, "jar"));
        assertNotNull(MavenRepoAccess.snapshotVersion(meta, "1.0-SNAPSHOT", "sources", "jar"));
    }
}
