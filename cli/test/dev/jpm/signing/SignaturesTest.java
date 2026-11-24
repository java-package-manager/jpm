package dev.jpm.signing;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Install;
import dev.jpm.signing.impl.OpenPgp;
import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signature checks against keys and signatures made with GnuPG 2.4 (test/assets/dev/jpm/signatures): an RSA key that
 * signs itself, an Ed25519 key that signs with a subkey, a NIST P-256 key, and a key nobody trusts.
 */
public class SignaturesTest {
    public static final String RSA = "BFCD855FE7DC80E91F2D06BA9E15370847B8D788";
    static final String ED25519 = "EDF319BB299212C0696EB269D2DBBE41600B25EB";
    static final String ECDSA = "084FEAB89F615E41896B2B2950C28C9A80469272";
    public static final String OTHER = "ABA6E444CCEEB44EE78C293B28DEA147D8ED39D9";

    public static Path assets() {
        try {
            return Path.of(SignaturesTest.class.getResource("/dev/jpm/signatures/data.txt").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void clear() {
        System.clearProperty("jpm.mavenCentral");
        System.clearProperty("jpm.keyserver");
    }

    @Test
    void gnupgSignaturesVerifyWithTheirKeys() throws IOException {
        Path data = assets().resolve("data.txt");
        Map<String, String> byKey = Map.of("rsa", RSA, "ed25519", ED25519, "ecdsa", ECDSA, "other", OTHER);
        for (Map.Entry<String, String> e : byKey.entrySet()) {
            OpenPgp.KeyRing ring = OpenPgp.readKeys(Fs.read(assets().resolve("keys/" + e.getValue() + ".asc")));
            assertEquals(e.getValue(), ring.primary().fingerprint());
            OpenPgp.Sig sig = OpenPgp.readSignature(Fs.read(assets().resolve("data.txt." + e.getKey() + ".asc")));
            OpenPgp.Key signer = ring.all().stream().filter(sig::issuedBy).findFirst().orElseThrow();
            assertTrue(OpenPgp.verifyFile(sig, signer, data), e.getKey());
            Path changed = Files.createTempFile("data", ".txt");
            Files.writeString(changed, "jpm signature test data!\n");
            assertFalse(OpenPgp.verifyFile(sig, signer, changed), e.getKey());
            Files.delete(changed);
        }
        OpenPgp.KeyRing ed = OpenPgp.readKeys(Fs.read(assets().resolve("keys/" + ED25519 + ".asc")));
        assertEquals(List.of("10342BFD2CBEDD238F4CF1E1E5F090C1B714B560"), ed.subkeys().stream()
                .filter(k -> k.algorithm() == 22).map(OpenPgp.Key::fingerprint).toList());
    }

    @Test
    void patterns() {
        assertTrue(Signatures.matches("*", "com.example", "lib"));
        assertTrue(Signatures.matches("com.example", "com.example", "lib"));
        assertFalse(Signatures.matches("com.example", "com.example.sub", "lib"));
        assertTrue(Signatures.matches("org.apache.*", "org.apache.commons", "commons-lang3"));
        assertTrue(Signatures.matches("com.example:lib", "com.example", "lib"));
        assertFalse(Signatures.matches("com.example:lib", "com.example", "other"));
    }

    @Test
    void theManifestNamesKeysByFingerprint() {
        Manifest m = Manifest.parse(Path.of("project.jsonc"), "{ \"name\": \"app\", \"trustedKeys\": { \"bfcd 855f e7dc 80e9 1f2d"
                + "  06ba 9e15 3708 47b8 d788\": \"com.example\", \"" + ED25519 + "\": [\"org.a\", \"org.b:c\"] } }");
        assertEquals(Map.of(RSA, List.of("com.example"), ED25519, List.of("org.a", "org.b:c")), m.trustedKeys);
        JpmException e = assertThrows(JpmException.class, () -> Manifest.parse(Path.of("project.jsonc"),
                "{ \"name\": \"app\", \"trustedKeys\": { \"9E15370847B8D788\": \"*\" } }"));
        assertTrue(e.getMessage().contains("not a key fingerprint"), e.getMessage());
    }

    @Test
    void installChecksSignedJarsAgainstTheTrustedKeys(@TempDir Path tmp) throws IOException {
        TestRepo repo = new TestRepo(tmp.resolve("repo"));
        repo.publish("com.example:signed:1.0.0").publish("com.example:unsigned:1.0.0").publish("org.other:lib:1.0.0");
        sign(repo, "com.example", "signed", "rsa");
        sign(repo, "org.other", "lib", "other");
        System.setProperty("jpm.mavenCentral", repo.uri());
        System.setProperty("jpm.keyserver", assets().resolve("keys").toUri() + "/");
        Path dir = tmp.resolve("app");
        String deps = "\"runtime\": { \"com.example:signed\": \"1.0.0\", \"com.example:unsigned\": \"1.0.0\" }";
        Fs.write(dir.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", " + deps
                + ", \"trustedKeys\": { \"" + RSA + "\": \"com.example\" } }");
        install(dir);
        assertTrue(Files.isRegularFile(Fs.home().resolve("keys/" + RSA + ".asc")));
        Path signed = new MavenRepo(List.of()).local("com.example", "signed", "1.0.0", null, "jar.asc.ok");
        assertTrue(Fs.read(signed).endsWith(" " + RSA), "the signed jar was not checked");
        install(dir);

        Fs.write(dir.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"runtime\": { \"org.other:lib\": \"1.0.0\" }"
                + ", \"trustedKeys\": { \"" + RSA + "\": \"com.example\" } }");
        JpmException untrusted = assertThrows(JpmException.class, () -> install(dir));
        assertTrue(untrusted.getMessage().contains("org.other:lib 1.0.0 is signed by key " + OTHER + ", which \"trustedKeys\" does not trust"),
                untrusted.getMessage());
        assertTrue(untrusted.hint().contains("\"" + OTHER + "\": \"org.other\""), untrusted.hint());

        Fs.write(dir.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"runtime\": { \"org.other:lib\": \"1.0.0\" }"
                + ", \"trustedKeys\": { \"" + OTHER + "\": \"org.*\" } }");
        install(dir);
        Path jar = new MavenRepo(List.of()).local("org.other", "lib", "1.0.0", null, "jar");
        Files.writeString(jar, "jpm signature test data, changed\n");
        Fs.deleteTree(dir.resolve("project.lock"));
        Files.deleteIfExists(jar.resolveSibling("lib-1.0.0.jar.asc.ok"));
        JpmException tampered = assertThrows(JpmException.class, () -> install(dir));
        assertTrue(tampered.getMessage().contains("does not match its jar"), tampered.getMessage());
    }

    private static void sign(TestRepo repo, String group, String artifact, String key) throws IOException {
        Path v = repo.root().resolve(group.replace('.', '/')).resolve(artifact).resolve("1.0.0");
        Files.copy(assets().resolve("data.txt"), v.resolve(artifact + "-1.0.0.jar"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(assets().resolve("data.txt." + key + ".asc"), v.resolve(artifact + "-1.0.0.jar.asc"));
    }

    private static void install(Path dir) {
        Project p = Project.find(dir);
        Install.run(p, new MavenRepo(p.root.repositories), Install.Options.normal());
    }
}
