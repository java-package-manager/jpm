package dev.jpm.cli.impl;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.json.JsoncEdit;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Extensions;
import dev.jpm.resolve.GitModule;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.resolve.Resolver;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;
import dev.jpm.version.VersionSpecs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class AddCommand extends Command {

    AddCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "add";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.PROJECT, "add <dep>[@version]", "Add dependencies or extensions (--dev, --test; -r <url> adds a Maven repository; -g installs globally)",
                        "Add dependencies (`--dev`, `--test`, `-r <url>` to add a Maven repository). An extension (a Git project with `\"extension\"` in its manifest) is declared in the root's `\"extensions\"` instead; `-g` installs one globally. Without a version: latest release (Maven) or `^latest-tag` (Git)"));
    }

    @Override
    List<String> flags() {
        return List.of("--dev", "-D", "--test", "-T", "-g", "--global", "-r", "--repo", "--repository");
    }

    @Override
    int run(List<String> args) {
        boolean dev = flag(args, "--dev", "-D");
        boolean test = flag(args, "--test", "-T");
        boolean global = flag(args, "-g", "--global");
        List<String> repoUrls = options(args, "-r", "--repo", "--repository");
        noMoreFlags(args);
        if (args.isEmpty()) throw new JpmException("Usage: jpm add [-r <repository url>] <dependency>[@version] [--dev | --test]");
        if (dev && test) throw new JpmException("Choose one of --dev and --test");
        if (global) return GlobalExtensions.add(args);
        Manifest.Scope scope = dev ? Manifest.Scope.DEV : test ? Manifest.Scope.TEST : Manifest.Scope.RUNTIME;
        Project p = Project.find(cwd);
        Project.Module m = scope == Manifest.Scope.RUNTIME && p.root.isWorkspaceRoot() && p.moduleAt(cwd) == null && allExtensions(p, args)
                ? p.rootModule() : single(p, "add");
        Path rootFile = p.root.file, moduleFile = m.manifest.file;
        String rootOriginal = Fs.read(rootFile);
        String moduleOriginal = Fs.read(moduleFile);
        try {
            if (!repoUrls.isEmpty()) {
                String rootSrc = withRepositories(rootOriginal, p.root, repoUrls);
                if (!rootSrc.equals(rootOriginal)) {
                    Fs.write(rootFile, rootSrc);
                    p = Project.find(cwd);
                    m = single(p, "add");
                }
            }
            MavenRepo repo = new MavenRepo(p.root.repositories);
            String src = Fs.read(moduleFile);
            String rootSrc = rootFile.equals(moduleFile) ? null : Fs.read(rootFile);
            List<Manifest.Dependency> declared = m.manifest.dependencies;
            for (String a : args) {
                DepArgs.DepArg d = DepArgs.parse(a);
                if (d.key().isWorkspace() && p.module(d.key().artifact()) == null) {
                    throw new JpmException("No workspace module named \"" + d.key().artifact() + "\"");
                }
                String spec = d.spec() != null ? d.spec() : d.key().isWorkspace() ? "*" : DepArgs.latestSpec(repo, d.key());
                if (d.key().isMaven()) {
                    try {
                        VersionSpecs.parse(spec);
                    } catch (IllegalArgumentException e) {
                        throw new JpmException(e.getMessage());
                    }
                }
                if (scope == Manifest.Scope.RUNTIME && isExtension(p, d.key(), spec, repo)) {
                    if (rootSrc == null) src = JsoncEdit.set(src, List.of("extensions"), d.key().toString(), Jsonc.quote(spec));
                    else rootSrc = JsoncEdit.set(rootSrc, List.of("extensions"), d.key().toString(), Jsonc.quote(spec));
                    Log.step("Adding", "extension " + d.key() + " " + spec + Log.dim(" (run it with `jpm " + Manifest.nameOf(d.key()) + "`)"));
                    continue;
                }
                Manifest.Scope pair = scope == Manifest.Scope.DEV ? Manifest.Scope.TEST : scope == Manifest.Scope.TEST ? Manifest.Scope.DEV : null;
                for (Manifest.Scope s : Manifest.Scope.values()) {
                    if (s == scope) continue;
                    if (s == pair && declared.stream().anyMatch(x -> x.key().equals(d.key()) && x.scope() == pair)) {
                        src = setSpec(src, s.key, d.key().toString(), spec);
                    } else {
                        src = JsoncEdit.remove(src, List.of(s.key), d.key().toString());
                    }
                }
                src = JsoncEdit.set(src, List.of(scope.key), d.key().toString(), Jsonc.quote(spec));
                Log.step("Adding", d.key() + " " + spec + (scope == Manifest.Scope.RUNTIME ? "" : " (" + scope.key + ")"));
            }
            Fs.write(moduleFile, src);
            if (rootSrc != null) Fs.write(rootFile, rootSrc);
            Install.run(Project.find(cwd), repo, new Install.Options(false, false, Set.of(), false));
        } catch (RuntimeException e) {
            Fs.write(moduleFile, moduleOriginal);
            if (!rootFile.equals(moduleFile)) Fs.write(rootFile, rootOriginal);
            String msg = e instanceof JpmException ? e.getMessage() : "unexpected failure: " + e;
            throw new JpmException(msg + "\n" + Manifest.FILE + " was left unchanged.", e);
        }
        return 0;
    }

    private static boolean allExtensions(Project p, List<String> args) {
        MavenRepo repo = new MavenRepo(p.root.repositories);
        for (String a : args) {
            DepArgs.DepArg d = DepArgs.parse(a);
            if (!d.key().isGit()) return false;
            if (!isExtension(p, d.key(), d.spec() != null ? d.spec() : DepArgs.latestSpec(repo, d.key()), repo)) return false;
        }
        return true;
    }

    private static boolean isExtension(Project p, DepKey key, String spec, MavenRepo repo) {
        if (!key.isGit()) return false;
        Manifest.ExtensionDecl d = new Manifest.ExtensionDecl(key, spec, 0);
        Graph g = new Resolver(Extensions.project(p, d), repo, null, Set.of(), false).resolve();
        Graph.Pkg pkg = g.get(key.toString());
        return pkg != null && pkg.commit != null && GitModule.locate(key, pkg.commit).manifest().isExtension;
    }

    static String withRepositories(String src, Manifest m, List<String> urls) {
        List<String> all = new ArrayList<>(m.repositories);
        boolean changed = false;
        for (String u : urls) {
            String url = u.trim();
            if (!url.matches("(?i)(https?|file)://.+")) {
                throw new JpmException("Invalid repository URL \"" + u + "\": expected https://..., http://... or file://...");
            }
            String norm = url.replaceAll("/+$", "");
            boolean present = all.stream().anyMatch(x -> x.replaceAll("/+$", "").equalsIgnoreCase(norm));
            if (!present) {
                all.add(norm);
                changed = true;
                Log.step("Repository", norm);
            }
        }
        if (!changed) return src;
        StringBuilder raw = new StringBuilder("[");
        for (int i = 0; i < all.size(); i++) raw.append(i == 0 ? "" : ", ").append(Jsonc.quote(all.get(i)));
        return JsoncEdit.set(src, List.of(), "repositories", raw.append(']').toString());
    }

    private static String setSpec(String src, String scope, String key, String spec) {
        Jsonc.Node scopeNode = Jsonc.parseNode(src).get(scope);
        Jsonc.Node value = scopeNode == null ? null : scopeNode.get(key);
        return value != null && value.isObject()
                ? JsoncEdit.set(src, List.of(scope, key), "version", Jsonc.quote(spec))
                : JsoncEdit.set(src, List.of(scope), key, Jsonc.quote(spec));
    }
}
