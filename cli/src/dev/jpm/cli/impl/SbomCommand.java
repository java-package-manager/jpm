package dev.jpm.cli.impl;

import dev.jpm.api.json.Jsonc;
import dev.jpm.log.Log;
import dev.jpm.maven.Pom;
import dev.jpm.sbom.Sbom;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class SbomCommand extends Command {
    SbomCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "sbom";
    }

    @Override
    List<Usage> usages() {
        return List.of(new Usage(Group.DEPENDENCIES, "sbom", "Write a CycloneDX software bill of materials from the lock",
                "Write a CycloneDX " + Sbom.SPEC_VERSION + " software bill of materials (JSON) for the locked dependencies: package URLs,"
                        + " SHA-256 hashes, licenses and the dependency graph; the same lock gives the same file. `-o <file>` writes it"
                        + " to a file instead of stdout, `--frozen`"));
    }

    @Override
    List<String> flags() {
        return List.of("-o", "--output", "--frozen");
    }

    @Override
    int run(List<String> args) {
        boolean frozen = flag(args, "--frozen");
        String output = option(args, "-o");
        if (output == null) output = option(args, "--output");
        noMoreFlags(args);
        if (!args.isEmpty()) throw new JpmException("Usage: jpm sbom [-o <file>] [--frozen]", 2);
        Ctx c = open(frozen);
        Map<String, Object> bom = Sbom.of(c.project(), c.graph(), new Pom.Loader(c.repo()), dev.jpm.BuildConfig.VERSION);
        String json = Jsonc.write(bom) + "\n";
        if (output == null) {
            Log.out(json.stripTrailing());
            return 0;
        }
        Path file = cwd.resolve(output);
        Fs.write(file, json);
        int n = ((List<?>) bom.get("components")).size();
        Log.step("Wrote", output + " (CycloneDX " + Sbom.SPEC_VERSION + ", " + n + (n == 1 ? " component)" : " components)"));
        return 0;
    }
}
