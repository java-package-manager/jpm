package dev.jpm.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeTest {
    @Test
    void readsChecksums() {
        String sums = "aaa  jpm-linux-x64\nbbb  jpm-linux-arm64\nccc *jpm-windows-x64.exe\n";
        assertEquals("aaa", Upgrade.checksum(sums, "jpm-linux-x64"));
        assertEquals("ccc", Upgrade.checksum(sums, "jpm-windows-x64.exe"));
        assertNull(Upgrade.checksum(sums, "jpm-macos-x64"));
    }

    @Test
    void namesTheAssetForThisPlatform() {
        String a = Upgrade.asset();
        assertTrue(a.matches("jpm-(linux|macos|windows)-(x64|arm64|[a-z0-9]+)(\\.exe)?"), a);
    }

    @Test
    void recognizesCanaryVersions() {
        assertTrue(Upgrade.isCanary("0.5.0-3ff03ab"));
        assertTrue(Upgrade.isCanary("1.0.0-SNAPSHOT-0123456789abcdef0123456789abcdef01234567"));
        assertEquals("0.5.0", Upgrade.releaseOf("0.5.0-3ff03ab"));
        assertEquals("0.5.0", Upgrade.releaseOf("0.5.0"));
        assertTrue(!Upgrade.isCanary("0.5.0") && !Upgrade.isCanary("1.0.0-rc1") && !Upgrade.isCanary("1.0.0-beta.2"));
        assertTrue(dev.jpm.version.Version.of("0.5.0-3ff03ab").compareTo(dev.jpm.version.Version.of("0.4.0")) > 0);
        assertTrue(dev.jpm.version.Version.of("0.5.0-3ff03ab").compareTo(dev.jpm.version.Version.of("0.6.0")) < 0);
        assertEquals("https://github.com/java-package-manager/jpm/releases/download/canary", Upgrade.canaryBase());
    }

    @Test
    void versionTellsWhatIsNewer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path latest = dir.resolve("latest"), canary = dir.resolve("canary");
        dev.jpm.util.Fs.write(latest, "0.6.0\n");
        dev.jpm.util.Fs.write(canary, "0.6.0-abcdef1\n");
        String l = latest.toUri().toString(), c = canary.toUri().toString();

        assertEquals("jpm 0.6.0 is available. Run `jpm upgrade`.", Upgrade.updateNotice("0.5.0", l, c));
        assertNull(Upgrade.updateNotice("0.6.0", l, c), "releases hear about releases only");
        assertEquals("jpm 0.6.0-abcdef1 is the newest canary build. Run `jpm upgrade --canary`.",
                Upgrade.updateNotice("0.6.0-1234567", l, c));
        assertEquals("jpm 0.6.0 is available. Run `jpm upgrade`.\njpm 0.6.0-abcdef1 is the newest canary build. Run `jpm upgrade --canary`.",
                Upgrade.updateNotice("0.5.0-1234567", l, c));
        assertNull(Upgrade.updateNotice("0.6.0-abcdef1", l, c));
        assertNull(Upgrade.updateNotice("0.5.0", c, c));
        assertNull(Upgrade.updateNotice("0.5.0", dir.resolve("missing").toUri().toString(), c));
    }

    @Test
    void releaseUrlsUsePlainVersionTags() {
        assertEquals("https://github.com/java-package-manager/jpm/releases/download/0.3.0", Upgrade.base("0.3.0"));
        assertEquals("https://github.com/java-package-manager/jpm/releases/latest/download", Upgrade.base(null));
    }
}
