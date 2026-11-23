package dev.jpm.resolve.impl;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.git.GitRepo;
import dev.jpm.importer.Imported;
import dev.jpm.importer.Importer;
import dev.jpm.log.Log;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ImportedRepo {
    private ImportedRepo() {
    }

    public static Map<String, String> manifests(DepKey key, String commit) {
        DepKey rk = key.repoKey();
        Path cache = Fs.home().resolve("imported").resolve(rk.host()).resolve(rk.group()).resolve(rk.artifact()).resolve(commit);
        Path index = cache.resolve("modules.txt");
        if (!Files.exists(index)) {
            Path src = GitRepo.of(key).checkout(commit);
            String tool = Importer.detect(src);
            if (tool == null) {
                throw new JpmException(rk + " has no " + Manifest.FILE + ", pom.xml or Gradle build at its root (commit "
                        + commit.substring(0, 7) + "), so jpm cannot build it");
            }
            Log.step("Importing", rk + " from its " + (tool.equals("maven") ? "pom.xml" : "Gradle build")
                    + Log.dim(" (" + commit.substring(0, 7) + ")"));
            Imported imp;
            try {
                imp = Importer.run(src, new MavenRepo(List.of()), true);
            } catch (JpmException e) {
                throw new JpmException("Could not import the " + tool + " build of " + rk + ": " + e.getMessage());
            }
            for (String w : imp.warnings) Log.warn(rk + ": " + w);
            Fs.deleteTree(cache);
            StringBuilder idx = new StringBuilder();
            for (String rel : imp.manifests.keySet()) {
                Fs.write(cache.resolve(rel.isEmpty() ? "_root" : rel).resolve(Manifest.FILE), imp.render(rel));
                idx.append(rel).append('\n');
            }
            Fs.write(cache.resolve("warnings.txt"), String.join("\n", imp.warnings) + "\n");
            Fs.write(index, idx.toString());
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String rel : Fs.read(index).split("\n", -1)) {
            if (rel.isEmpty() && !out.isEmpty()) continue;
            Path f = cache.resolve(rel.isEmpty() ? "_root" : rel).resolve(Manifest.FILE);
            if (Files.exists(f)) out.put(rel, Fs.read(f));
        }
        return out;
    }
}
