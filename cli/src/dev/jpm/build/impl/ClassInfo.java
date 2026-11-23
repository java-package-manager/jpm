package dev.jpm.build.impl;

import dev.jpm.util.Hash;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ClassInfo(String name, String sourceFile, Set<String> refs, String abi, String constants) {

    private static final Pattern DESC_REF = Pattern.compile("L([\\w/$]+)[;<]");

    public static ClassInfo read(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Object[] pool = new Object[count];
        int[] tags = new int[count];
        int[][] refsIdx = new int[count][];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 3 -> pool[i] = in.readInt();
                case 4 -> pool[i] = in.readFloat();
                case 5 -> {
                    pool[i] = in.readLong();
                    i++;
                }
                case 6 -> {
                    pool[i] = in.readDouble();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> refsIdx[i] = new int[]{in.readUnsignedShort()};
                case 9, 10, 11, 12, 17, 18 -> refsIdx[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                case 15 -> {
                    in.readUnsignedByte();
                    refsIdx[i] = new int[]{in.readUnsignedShort()};
                }
                default -> throw new IOException("bad constant pool tag " + tag);
            }
        }
        Set<String> refs = new TreeSet<>();
        for (int i = 1; i < count; i++) {
            if (tags[i] == 7) {
                String n = (String) pool[refsIdx[i][0]];
                if (n.startsWith("[")) addDescRefs(n, refs);
                else refs.add(n);
            } else if (tags[i] == 1) {
                String s = (String) pool[i];
                if (s.indexOf('L') >= 0 && s.indexOf(';') > 0) addDescRefs(s, refs);
            }
        }
        int access = in.readUnsignedShort();
        String self = (String) pool[refsIdx[in.readUnsignedShort()][0]];
        int superIdx = in.readUnsignedShort();
        String sup = superIdx == 0 ? "" : (String) pool[refsIdx[superIdx][0]];
        Hash abi = new Hash().add(self).add(Integer.toString(access & ~0x20)).add(sup);
        Hash consts = new Hash();
        int ifaces = in.readUnsignedShort();
        List<String> ifaceNames = new ArrayList<>();
        for (int i = 0; i < ifaces; i++) ifaceNames.add((String) pool[refsIdx[in.readUnsignedShort()][0]]);
        ifaceNames.sort(null);
        for (String s : ifaceNames) abi.add("i " + s);

        List<String> members = new ArrayList<>();
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            int fa = in.readUnsignedShort();
            String fn = (String) pool[in.readUnsignedShort()];
            String fd = (String) pool[in.readUnsignedShort()];
            int attrs = in.readUnsignedShort();
            String constant = null;
            for (int a = 0; a < attrs; a++) {
                String an = (String) pool[in.readUnsignedShort()];
                int len = in.readInt();
                if (an.equals("ConstantValue") && len == 2) {
                    int idx = in.readUnsignedShort();
                    constant = tags[idx] == 8 ? "s:" + pool[refsIdx[idx][0]] : String.valueOf(pool[idx]);
                } else {
                    in.skipNBytes(len);
                }
            }
            if ((fa & 0x0002) == 0) members.add("f " + fa + " " + fn + " " + fd);
            if (constant != null) consts.add(fn + "=" + constant);
        }
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            int ma = in.readUnsignedShort();
            String mn = (String) pool[in.readUnsignedShort()];
            String md = (String) pool[in.readUnsignedShort()];
            int attrs = in.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                in.readUnsignedShort();
                in.skipNBytes(in.readInt());
            }
            if ((ma & 0x0002) == 0 && (ma & 0x1000) == 0) members.add("m " + (ma & ~0x0020) + " " + mn + " " + md);
        }
        members.sort(null);
        for (String s : members) abi.add(s);

        String sourceFile = null;
        int attrs = in.readUnsignedShort();
        for (int a = 0; a < attrs; a++) {
            String an = (String) pool[in.readUnsignedShort()];
            int len = in.readInt();
            if (an.equals("SourceFile")) sourceFile = (String) pool[in.readUnsignedShort()];
            else if (an.equals("PermittedSubclasses") || an.equals("Record")) {
                byte[] b = in.readNBytes(len);
                abi.add(an).add(b);
            } else in.skipNBytes(len);
        }
        refs.remove(self);
        return new ClassInfo(self, sourceFile, refs, abi.hex(), consts.hex());
    }

    private static void addDescRefs(String desc, Set<String> out) {
        Matcher m = DESC_REF.matcher(desc);
        while (m.find()) out.add(m.group(1));
    }
}
