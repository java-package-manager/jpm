package dev.jpm.build.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Relocator {
    private final Map<String, String> internal = new LinkedHashMap<>();

    public Relocator(Map<String, String> relocations) {
        relocations.entrySet().stream()
                .sorted((a, b) -> b.getKey().length() - a.getKey().length())
                .forEach(e -> internal.put(e.getKey().replace('.', '/'), e.getValue().replace('.', '/')));
    }

    public boolean isEmpty() {
        return internal.isEmpty();
    }

    public String entryName(String name) {
        if (name.startsWith("META-INF/services/") && name.indexOf('/', 18) < 0) {
            return "META-INF/services/" + dotted(name.substring(18));
        }
        java.util.regex.Matcher mr = java.util.regex.Pattern.compile("^(META-INF/versions/\\d+/)(.+)$").matcher(name);
        if (mr.matches()) return mr.group(1) + entryName(mr.group(2));
        for (Map.Entry<String, String> e : internal.entrySet()) {
            if (name.startsWith(e.getKey() + "/")) return e.getValue() + name.substring(e.getKey().length());
        }
        return name;
    }

    public String dotted(String name) {
        String slashed = name.replace('.', '/');
        for (Map.Entry<String, String> e : internal.entrySet()) {
            if (slashed.equals(e.getKey()) || slashed.startsWith(e.getKey() + "/")) {
                return (e.getValue() + slashed.substring(e.getKey().length())).replace('/', '.');
            }
        }
        return name;
    }

    public byte[] entry(String name, byte[] bytes) {
        if (internal.isEmpty()) return bytes;
        if (name.endsWith(".class") && bytes.length > 10 && (bytes[0] & 0xff) == 0xCA && (bytes[1] & 0xff) == 0xFE) return classFile(bytes);
        if (name.startsWith("META-INF/services/") && name.indexOf('/', 18) < 0) {
            StringBuilder sb = new StringBuilder();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\r?\n", -1)) {
                int hash = line.indexOf('#');
                String code = hash < 0 ? line : line.substring(0, hash);
                String rest = hash < 0 ? "" : line.substring(hash);
                String t = code.strip();
                sb.append(t.isEmpty() ? code : code.replace(t, dotted(t))).append(rest).append('\n');
            }
            String s = sb.toString();
            return (s.endsWith("\n\n") ? s.substring(0, s.length() - 1) : s).getBytes(StandardCharsets.UTF_8);
        }
        return bytes;
    }

    public byte[] classFile(byte[] b) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            ByteArrayOutputStream bos = new ByteArrayOutputStream(b.length + 64);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(in.readInt());
            out.writeShort(in.readUnsignedShort());
            out.writeShort(in.readUnsignedShort());
            int count = in.readUnsignedShort();
            byte[] tags = new byte[count];
            Object[] values = new Object[count];
            boolean[] isString = new boolean[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = (byte) tag;
                switch (tag) {
                    case 1 -> values[i] = in.readUTF();
                    case 8 -> {
                        int ref = in.readUnsignedShort();
                        values[i] = ref;
                    }
                    case 7, 16, 19, 20 -> values[i] = in.readNBytes(2);
                    case 15 -> values[i] = in.readNBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> values[i] = in.readNBytes(4);
                    case 5, 6 -> {
                        values[i] = in.readNBytes(8);
                        i++;
                    }
                    default -> throw new IOException("unknown constant pool tag " + tag);
                }
            }
            for (int i = 1; i < count; i++) if (tags[i] == 8 && values[i] instanceof Integer ref && ref < count) isString[ref] = true;
            out.writeShort(count);
            for (int i = 1; i < count; i++) {
                int tag = tags[i] & 0xff;
                out.writeByte(tag);
                switch (tag) {
                    case 1 -> {
                        String v = (String) values[i];
                        out.writeUTF(isString[i] ? string(v) : symbol(v));
                    }
                    case 8 -> out.writeShort((Integer) values[i]);
                    case 5, 6 -> {
                        out.write((byte[]) values[i]);
                        i++;
                    }
                    default -> out.write((byte[]) values[i]);
                }
            }
            out.write(in.readAllBytes());
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return b;
        }
    }

    public String symbol(String v) {
        for (Map.Entry<String, String> e : internal.entrySet()) {
            String from = e.getKey(), to = e.getValue();
            if (!v.contains(from)) continue;
            if (v.equals(from) || v.startsWith(from + "/")) {
                v = to + v.substring(from.length());
                continue;
            }
            v = v.replace("L" + from + "/", "L" + to + "/").replace("[" + from + "/", "[" + to + "/");
        }
        return v;
    }

    public String string(String v) {
        for (Map.Entry<String, String> e : internal.entrySet()) {
            String from = e.getKey(), to = e.getValue();
            String fromDots = from.replace('/', '.'), toDots = to.replace('/', '.');
            if (v.equals(fromDots) || v.startsWith(fromDots + ".")) return toDots + v.substring(fromDots.length());
            if (v.equals(from) || v.startsWith(from + "/")) return to + v.substring(from.length());
            if (v.startsWith("/" + from + "/")) return "/" + to + v.substring(from.length() + 1);
        }
        return symbol(v);
    }
}
