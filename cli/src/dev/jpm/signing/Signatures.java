package dev.jpm.signing;

import dev.jpm.Manifest;
import dev.jpm.log.Log;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.signing.impl.OpenPgp;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Checks the OpenPGP signatures ({@code .asc}) that Maven repositories publish next to jars against the root manifest's
 * {@code "trustedKeys"}: each key's fingerprint and the packages it may sign. A package without a signature passes; a
 * signed one must be signed by a key trusted for it, and the signature must match. Keys are fetched by fingerprint
 * from {@code JPM_KEYSERVER} (keys.openpgp.org by default) into {@code ~/.jpm/keys}.
 */
public final class Signatures {
    private Signatures() {
    }

    /**
     * Where keys are fetched from by fingerprint, unless {@code JPM_KEYSERVER} names another.
     */
    public static final String KEYSERVER = "https://keys.openpgp.org/vks/v1/by-fingerprint/";

    private static final Map<String, OpenPgp.KeyRing> KEYS = new ConcurrentHashMap<>();

    /**
     * Checks every fetched Maven jar of {@code graph} (its packages' {@code artifact}s) against {@code trusted}
     * (fingerprint to package patterns, {@link Manifest#trustedKeys}); does nothing when {@code trusted} is empty.
     *
     * @throws JpmException when a signature is made by a key that is not trusted for the package, or does not match
     */
    public static void check(Map<String, List<String>> trusted, Graph graph, MavenRepo repo) {
        if (trusted.isEmpty()) return;
        for (Graph.Pkg p : graph.pkgs.values()) {
            if (p.kind != Graph.Kind.MAVEN || !p.hasJar || p.artifact == null || !Files.isRegularFile(p.artifact)) continue;
            check(trusted, p, repo);
        }
    }

    private static void check(Map<String, List<String>> trusted, Graph.Pkg p, MavenRepo repo) {
        Path jar = p.artifact;
        Path asc = repo.fetch(p.group(), p.name(), p.version, p.classifier(), "jar.asc");
        String what = p.id + " " + p.version;
        if (asc == null) {
            Log.debug(what + " has no published signature");
            return;
        }
        OpenPgp.Sig sig;
        try {
            sig = OpenPgp.readSignature(Fs.read(asc));
        } catch (OpenPgp.FormatException e) {
            throw new JpmException("The signature of " + what + " cannot be read: " + e.getMessage())
                    .withHint("delete " + asc + " and try again; if it stays, the repository publishes a broken signature");
        }
        Path marker = asc.resolveSibling(asc.getFileName() + ".ok");
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : trusted.entrySet()) {
            if (e.getValue().stream().anyMatch(pattern -> matches(pattern, p.group(), p.name()))) candidates.add(e.getKey());
        }
        String sha = p.sha256 != null ? p.sha256 : dev.jpm.util.Hash.sha256(jar);
        for (String fpr : candidates) {
            OpenPgp.KeyRing ring = keys(fpr);
            for (OpenPgp.Key k : ring.all()) {
                if (!sig.issuedBy(k)) continue;
                if (Files.isRegularFile(marker) && Fs.read(marker).equals(sha + " " + fpr)) return;
                boolean ok;
                try {
                    ok = OpenPgp.verifyFile(sig, k, jar);
                } catch (IOException e) {
                    throw new JpmException("Could not read " + jar + ": " + e.getMessage());
                }
                if (!ok) {
                    throw new JpmException("The signature of " + what + " does not match its jar (key " + fpr + ")")
                            .withHint("the jar or its signature was changed after it was signed, or the key cannot check it ("
                                    + (k.key() == null ? "an algorithm jpm does not support" : "hash " + OpenPgp.hashName(sig.hash())) + ");"
                                    + " delete " + jar.getParent() + " and try again, and report it to the publisher if it stays");
                }
                Fs.write(marker, sha + " " + fpr);
                Log.debug("verified the signature of " + what + " (key " + fpr + ")");
                return;
            }
        }
        String id = sig.issuer() == null ? "an unnamed key" : "key " + sig.issuer();
        String trustedFor = candidates.isEmpty() ? "no key is trusted for " + p.group()
                : "the keys trusted for it are " + String.join(", ", candidates);
        throw new JpmException(what + " is signed by " + id + ", which \"trustedKeys\" does not trust for it (" + trustedFor + ")")
                .withHint("once you have checked whose key it is, add \"" + (sig.issuer() == null ? "<fingerprint>" : sig.issuer())
                        + "\": \"" + p.group() + "\" to \"trustedKeys\" in " + Manifest.FILE
                        + (sig.issuerFingerprint() == null ? " (with the key's full fingerprint)" : ""));
    }

    /**
     * Whether a {@code "trustedKeys"} pattern covers a package: {@code *}, a group ({@code com.example}), a group with
     * {@code *} ({@code org.apache.*}), or {@code group:artifact}.
     */
    public static boolean matches(String pattern, String group, String artifact) {
        int colon = pattern.indexOf(':');
        String g = colon < 0 ? pattern : pattern.substring(0, colon);
        String a = colon < 0 ? "*" : pattern.substring(colon + 1);
        return glob(g).matcher(group).matches() && glob(a).matcher(artifact).matches();
    }

    private static Pattern glob(String g) {
        StringBuilder re = new StringBuilder();
        for (String part : g.split("\\*", -1)) {
            if (!re.isEmpty()) re.append(".*");
            re.append(Pattern.quote(part));
        }
        return Pattern.compile(re.toString());
    }

    private static OpenPgp.KeyRing keys(String fpr) {
        return KEYS.computeIfAbsent(fpr, Signatures::load);
    }

    private static OpenPgp.KeyRing load(String fpr) {
        Path cached = Fs.home().resolve("keys").resolve(fpr + ".asc");
        if (!Files.isRegularFile(cached)) {
            String base = System.getProperty("jpm.keyserver", System.getenv("JPM_KEYSERVER"));
            if (base == null || base.isBlank()) base = KEYSERVER;
            String url = base + fpr;
            String text;
            if (url.startsWith("file:")) {
                Path src = Path.of(URI.create(url + ".asc"));
                if (!Files.isRegularFile(src)) throw new JpmException("No key " + fpr + " at " + src);
                text = Fs.read(src);
            } else {
                try {
                    text = dev.jpm.util.Http.getString(url);
                } catch (JpmException e) {
                    throw new JpmException("Could not fetch the trusted key " + fpr + " from " + base + ": " + e.getMessage())
                            .withHint("check the fingerprint in \"trustedKeys\", or put the key, exported with `gpg --armor --export "
                                    + fpr + "`, in " + cached);
                }
            }
            OpenPgp.KeyRing ring = parse(fpr, text, url);
            Fs.write(cached, text);
            return ring;
        }
        return parse(fpr, Fs.read(cached), cached.toString());
    }

    private static OpenPgp.KeyRing parse(String fpr, String text, String where) {
        OpenPgp.KeyRing ring;
        try {
            ring = OpenPgp.readKeys(text);
        } catch (OpenPgp.FormatException e) {
            throw new JpmException("The key " + fpr + " from " + where + " cannot be read: " + e.getMessage());
        }
        if (!ring.primary().fingerprint().equals(fpr)) {
            throw new JpmException("The key from " + where + " has the fingerprint " + ring.primary().fingerprint() + ", not " + fpr)
                    .withHint("a key is trusted only by its own fingerprint; delete " + where + " if it is a local file");
        }
        return ring;
    }

    /**
     * Normalizes a fingerprint as written in {@code "trustedKeys"}: without spaces, in upper case.
     */
    public static String fingerprint(String s) {
        return OpenPgp.normalizeFingerprint(s);
    }
}
