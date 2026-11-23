package dev.jpm.cli.impl;

import dev.jpm.util.JpmException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class ManifestSchema {
    static final String RESOURCE = "/dev/jpm/project.schema.json";

    private ManifestSchema() {
    }

    static String text() {
        try (InputStream in = ManifestSchema.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new JpmException("this jpm build has no " + RESOURCE.substring(1));
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JpmException("Could not read the schema: " + e.getMessage());
        }
    }
}
