package dev.jpm.signing.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class OpenPgp {
    private OpenPgp() {
    }

    public static final class FormatException extends RuntimeException {
        FormatException(String message) {
            super(message);
        }
    }

    public record Key(String fingerprint, int algorithm, byte[] body, PublicKey key) {
        public String keyId() {
            return fingerprint.substring(fingerprint.length() - 16);
        }
    }

    public record KeyRing(Key primary, List<Key> subkeys) {
        public List<Key> all() {
            List<Key> out = new ArrayList<>();
            out.add(primary);
            out.addAll(subkeys);
            return out;
        }
    }

    public record Sig(int type, int algorithm, int hash, byte[] hashed, List<byte[]> values, String issuerKeyId,
                      String issuerFingerprint) {
        public String issuer() {
            return issuerFingerprint != null ? issuerFingerprint : issuerKeyId;
        }

        public boolean issuedBy(Key k) {
            if (issuerFingerprint != null) return issuerFingerprint.equals(k.fingerprint());
            return issuerKeyId != null && issuerKeyId.equals(k.keyId());
        }
    }

    private record Packet(int tag, byte[] body) {}

    private static final byte[] ED25519_OID = {0x2B, 0x06, 0x01, 0x04, 0x01, (byte) 0xDA, 0x47, 0x0F, 0x01};
    private static final byte[] ED25519_X509 = HexFormat.of().parseHex("302a300506032b6570032100");

    public static byte[] dearmor(String text) {
        if (!text.contains("-----BEGIN PGP")) return text.getBytes(StandardCharsets.ISO_8859_1);
        String[] lines = text.replace("\r", "").split("\n");
        StringBuilder b64 = new StringBuilder();
        int i = 0;
        while (i < lines.length && !lines[i].startsWith("-----BEGIN PGP")) i++;
        i++;
        while (i < lines.length && !lines[i].isBlank()) i++;
        for (i++; i < lines.length && !lines[i].startsWith("-----END PGP"); i++) {
            String l = lines[i].strip();
            if (l.startsWith("=") && l.length() == 5) continue;
            b64.append(l);
        }
        try {
            return Base64.getDecoder().decode(b64.toString());
        } catch (IllegalArgumentException e) {
            throw new FormatException("its ASCII armor is not valid Base64");
        }
    }

    private static List<Packet> packets(byte[] d) {
        List<Packet> out = new ArrayList<>();
        int i = 0;
        while (i < d.length) {
            int ctb = d[i++] & 0xff;
            if ((ctb & 0x80) == 0) throw new FormatException("it is not an OpenPGP packet sequence");
            int tag;
            long len;
            if ((ctb & 0x40) != 0) {
                tag = ctb & 0x3f;
                int o = d[i++] & 0xff;
                if (o < 192) len = o;
                else if (o < 224) len = ((o - 192) << 8) + (d[i++] & 0xff) + 192;
                else if (o == 255) {
                    len = be(d, i, 4);
                    i += 4;
                } else throw new FormatException("it uses partial packet lengths");
            } else {
                tag = (ctb >> 2) & 0xf;
                switch (ctb & 3) {
                    case 0 -> len = d[i++] & 0xff;
                    case 1 -> {
                        len = be(d, i, 2);
                        i += 2;
                    }
                    case 2 -> {
                        len = be(d, i, 4);
                        i += 4;
                    }
                    default -> len = d.length - i;
                }
            }
            if (len < 0 || i + len > d.length) throw new FormatException("a packet is cut off");
            out.add(new Packet(tag, Arrays.copyOfRange(d, i, (int) (i + len))));
            i += (int) len;
        }
        return out;
    }

    private static long be(byte[] d, int at, int n) {
        if (at + n > d.length) throw new FormatException("a packet is cut off");
        long v = 0;
        for (int k = 0; k < n; k++) v = (v << 8) | (d[at + k] & 0xff);
        return v;
    }

    public static KeyRing readKeys(String text) {
        Key primary = null;
        List<Key> subkeys = new ArrayList<>();
        Key pending = null;
        for (Packet p : packets(dearmor(text))) {
            if (p.tag == 6) {
                if (primary != null) break;
                primary = key(p.body);
            } else if (p.tag == 14 && primary != null) {
                pending = key(p.body);
            } else if (p.tag == 2 && pending != null && primary != null) {
                Sig s = signature(p.body);
                if (s != null && s.type == 0x18 && s.issuedBy(primary) && verifyBinding(s, primary, pending)) {
                    subkeys.add(pending);
                    pending = null;
                }
            }
        }
        if (primary == null) throw new FormatException("it holds no public key");
        return new KeyRing(primary, List.copyOf(subkeys));
    }

    private static Key key(byte[] body) {
        if (body.length < 6 || body[0] != 4) return new Key("v" + (body.length == 0 ? 0 : body[0]), -1, body, null);
        int algo = body[5] & 0xff;
        String fpr;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(new byte[]{(byte) 0x99, (byte) (body.length >> 8), (byte) body.length});
            fpr = HexFormat.of().withUpperCase().formatHex(sha1.digest(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return new Key(fpr, algo, body, publicKey(algo, body));
    }

    private static PublicKey publicKey(int algo, byte[] body) {
        try {
            int[] at = {6};
            switch (algo) {
                case 1, 2, 3 -> {
                    BigInteger n = new BigInteger(1, mpi(body, at));
                    BigInteger e = new BigInteger(1, mpi(body, at));
                    return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
                }
                case 19 -> {
                    byte[] oid = oid(body, at);
                    String curve = curve(oid);
                    if (curve == null) return null;
                    byte[] point = mpi(body, at);
                    AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                    params.init(new ECGenParameterSpec(curve));
                    ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
                    int size = (point.length - 1) / 2;
                    if (point[0] != 4) return null;
                    ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 1 + size)),
                            new BigInteger(1, Arrays.copyOfRange(point, 1 + size, point.length)));
                    return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, spec));
                }
                case 22 -> {
                    byte[] oid = oid(body, at);
                    if (!Arrays.equals(oid, ED25519_OID)) return null;
                    byte[] point = mpi(body, at);
                    if (point.length != 33 || point[0] != 0x40) return null;
                    return ed25519(Arrays.copyOfRange(point, 1, 33));
                }
                case 27 -> {
                    return ed25519(Arrays.copyOfRange(body, 6, 38));
                }
                default -> {
                    return null;
                }
            }
        } catch (GeneralSecurityException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    private static PublicKey ed25519(byte[] raw) throws GeneralSecurityException {
        byte[] der = Arrays.copyOf(ED25519_X509, ED25519_X509.length + 32);
        System.arraycopy(raw, 0, der, ED25519_X509.length, 32);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    private static String curve(byte[] oid) {
        String hex = HexFormat.of().formatHex(oid);
        return switch (hex) {
            case "2a8648ce3d030107" -> "secp256r1";
            case "2b81040022" -> "secp384r1";
            case "2b81040023" -> "secp521r1";
            default -> null;
        };
    }

    private static byte[] oid(byte[] b, int[] at) {
        int n = b[at[0]++] & 0xff;
        byte[] oid = Arrays.copyOfRange(b, at[0], at[0] + n);
        at[0] += n;
        return oid;
    }

    private static byte[] mpi(byte[] b, int[] at) {
        int bits = (int) be(b, at[0], 2);
        int n = (bits + 7) / 8;
        if (at[0] + 2 + n > b.length) throw new FormatException("a number is cut off");
        byte[] v = Arrays.copyOfRange(b, at[0] + 2, at[0] + 2 + n);
        at[0] += 2 + n;
        return v;
    }

    public static Sig readSignature(String text) {
        for (Packet p : packets(dearmor(text))) {
            if (p.tag != 2) continue;
            Sig s = signature(p.body);
            if (s == null) throw new FormatException("it is not a version 4 signature");
            return s;
        }
        throw new FormatException("it holds no signature");
    }

    private static Sig signature(byte[] b) {
        if (b.length < 6 || b[0] != 4) return null;
        int type = b[1] & 0xff, algo = b[2] & 0xff, hash = b[3] & 0xff;
        int hashedLen = (int) be(b, 4, 2);
        int hashedEnd = 6 + hashedLen;
        byte[] hashed = Arrays.copyOfRange(b, 0, hashedEnd);
        int unhashedLen = (int) be(b, hashedEnd, 2);
        int unhashedEnd = hashedEnd + 2 + unhashedLen;
        String[] issuer = new String[2];
        subpackets(b, 6, hashedEnd, issuer);
        subpackets(b, hashedEnd + 2, unhashedEnd, issuer);
        int[] at = {unhashedEnd + 2};
        List<byte[]> values = new ArrayList<>();
        if (algo == 27) {
            values.add(Arrays.copyOfRange(b, at[0], at[0] + 64));
        } else {
            while (at[0] < b.length) values.add(mpi(b, at));
        }
        return new Sig(type, algo, hash, hashed, List.copyOf(values), issuer[0], issuer[1]);
    }

    private static void subpackets(byte[] b, int from, int to, String[] issuer) {
        int i = from;
        while (i < to) {
            int o = b[i++] & 0xff;
            int len;
            if (o < 192) len = o;
            else if (o < 255) len = ((o - 192) << 8) + (b[i++] & 0xff) + 192;
            else {
                len = (int) be(b, i, 4);
                i += 4;
            }
            if (len < 1 || i + len > to) throw new FormatException("a signature subpacket is cut off");
            int type = b[i] & 0x7f;
            HexFormat hex = HexFormat.of().withUpperCase();
            if (type == 16 && len == 9) issuer[0] = hex.formatHex(b, i + 1, i + 9);
            if (type == 33 && len == 22 && b[i + 1] == 4) issuer[1] = hex.formatHex(b, i + 2, i + 22);
            i += len;
        }
    }

    public static String hashName(int hash) {
        return switch (hash) {
            case 2 -> "SHA-1";
            case 8 -> "SHA-256";
            case 9 -> "SHA-384";
            case 10 -> "SHA-512";
            case 11 -> "SHA-224";
            default -> null;
        };
    }

    public static boolean verifyFile(Sig sig, Key key, Path file) throws IOException {
        if (sig.type != 0x00) return false;
        try (InputStream in = Files.newInputStream(file)) {
            return verify(sig, key, in, null);
        }
    }

    private static boolean verifyBinding(Sig sig, Key primary, Key subkey) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (Key k : List.of(primary, subkey)) {
            data.write(0x99);
            data.write(k.body().length >> 8);
            data.write(k.body().length);
            data.writeBytes(k.body());
        }
        try {
            return verify(sig, primary, InputStream.nullInputStream(), data.toByteArray());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean verify(Sig sig, Key key, InputStream in, byte[] prefix) throws IOException {
        String hashName = hashName(sig.hash);
        if (hashName == null || key.key() == null || sig.algorithm != key.algorithm() && !(sig.algorithm == 1 && key.algorithm() <= 3)) {
            return false;
        }
        byte[] trailer = trailer(sig);
        try {
            if (sig.algorithm >= 1 && sig.algorithm <= 3) {
                Signature v = Signature.getInstance(hashName.replace("-", "") + "withRSA");
                v.initVerify(key.key());
                if (prefix != null) v.update(prefix);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) v.update(buf, 0, n);
                v.update(trailer);
                int size = (((java.security.interfaces.RSAPublicKey) key.key()).getModulus().bitLength() + 7) / 8;
                return v.verify(leftPad(sig.values.get(0), size));
            }
            MessageDigest md = MessageDigest.getInstance(hashName);
            if (prefix != null) md.update(prefix);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            md.update(trailer);
            byte[] digest = md.digest();
            if (sig.algorithm == 19) {
                Signature v = Signature.getInstance("NONEwithECDSA");
                v.initVerify(key.key());
                v.update(digest);
                return v.verify(der(sig.values.get(0), sig.values.get(1)));
            }
            if (sig.algorithm == 22 || sig.algorithm == 27) {
                byte[] raw = sig.algorithm == 27 ? sig.values.get(0)
                        : concat(leftPad(sig.values.get(0), 32), leftPad(sig.values.get(1), 32));
                Signature v = Signature.getInstance("Ed25519");
                v.initVerify(key.key());
                v.update(digest);
                return v.verify(raw);
            }
            return false;
        } catch (GeneralSecurityException | IndexOutOfBoundsException e) {
            return false;
        }
    }

    private static byte[] trailer(Sig sig) {
        byte[] t = Arrays.copyOf(sig.hashed, sig.hashed.length + 6);
        int n = sig.hashed.length;
        t[n] = 4;
        t[n + 1] = (byte) 0xff;
        t[n + 2] = (byte) (n >>> 24);
        t[n + 3] = (byte) (n >>> 16);
        t[n + 4] = (byte) (n >>> 8);
        t[n + 5] = (byte) n;
        return t;
    }

    private static byte[] leftPad(byte[] v, int size) {
        if (v.length >= size) return v.length == size ? v : Arrays.copyOfRange(v, v.length - size, v.length);
        byte[] out = new byte[size];
        System.arraycopy(v, 0, out, size - v.length, v.length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] der(byte[] r, byte[] s) {
        byte[] ri = new BigInteger(1, r).toByteArray(), si = new BigInteger(1, s).toByteArray();
        ByteArrayOutputStream seq = new ByteArrayOutputStream();
        for (byte[] i : List.of(ri, si)) {
            seq.write(0x02);
            length(seq, i.length);
            seq.writeBytes(i);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        length(out, seq.size());
        out.writeBytes(seq.toByteArray());
        return out.toByteArray();
    }

    private static void length(ByteArrayOutputStream out, int n) {
        if (n < 128) out.write(n);
        else if (n < 256) {
            out.write(0x81);
            out.write(n);
        } else {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n);
        }
    }

    public static String normalizeFingerprint(String s) {
        return s.replace(" ", "").toUpperCase(Locale.ROOT);
    }
}
