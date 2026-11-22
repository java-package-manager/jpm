package dev.jpm.importer;

/** Gradle init script that reports the build model as JSON. Kept in code so native builds need no resources. */
final class GradleModelScript {
    private GradleModelScript() {}

    static final String TEXT = """
// Written by jpm: reports the Gradle build model as JSON for `jpm import`. It does not change the build.
// Each project reports itself from its own jpmModel task: Gradle only lets a project resolve its
// configurations while it holds that project's lock, which a build-wide callback does not.
import groovy.json.JsonOutput

def jpmModelDir = new File(System.getProperty('jpm.model.output'))

def jpmModel = { Project p ->
    def root = p.rootProject
        def info = [
            path       : p.path,
            name       : p.name,
            dir        : root.projectDir.toPath().relativize(p.projectDir.toPath()).toString().replace('\\\\', '/'),
            group      : p.group?.toString(),
            version    : p.version?.toString(),
            java       : p.plugins.hasPlugin('java'),
            kotlin     : p.plugins.findPlugin('org.jetbrains.kotlin.jvm') != null,
            plugins    : p.plugins.collect { it.class.name },
            deps       : [:],
            resolved   : [:],
            repositories: [],
            sourceSets : [:],
        ]
        def javaExt = p.extensions.findByName('java')
        if (javaExt != null) {
            try { info.toolchain = javaExt.toolchain.languageVersion.getOrNull()?.asInt() } catch (ignored) {}
            try { info.target = javaExt.targetCompatibility?.majorVersion } catch (ignored) {}
            try {
                javaExt.sourceSets.each { ss ->
                    info.sourceSets[ss.name] = [
                        java     : ss.java.srcDirs.collect { root.projectDir.toPath().relativize(it.toPath()).toString().replace('\\\\', '/') },
                        resources: ss.resources.srcDirs.collect { root.projectDir.toPath().relativize(it.toPath()).toString().replace('\\\\', '/') },
                    ]
                }
            } catch (ignored) {}
        }
        def compileJava = p.tasks.findByName('compileJava')
        if (compileJava != null) {
            try { info.release = compileJava.options.release.getOrNull() } catch (ignored) {}
        }
        def app = p.extensions.findByName('application')
        if (app != null) {
            try { info.mainClass = app.mainClass.getOrNull() } catch (ignored) {
                try { info.mainClass = app.mainClassName } catch (ignored2) {}
            }
        }
        ['api', 'implementation', 'compileOnly', 'compileOnlyApi', 'runtimeOnly', 'annotationProcessor',
         'testImplementation', 'testCompileOnly', 'testRuntimeOnly', 'testAnnotationProcessor'].each { c ->
            def conf = p.configurations.findByName(c)
            if (conf == null) return
            info.deps[c] = conf.dependencies.collect { d ->
                if (d instanceof ProjectDependency) {
                    def path = null
                    try { path = d.path } catch (ignored) { path = d.dependencyProject.path }
                    return [project: path]
                }
                def category = null
                try {
                    def attr = d.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)
                    category = attr?.name
                } catch (ignored) {}
                def classifier = null
                try { classifier = d.artifacts.find { it.classifier }?.classifier } catch (ignored) {}
                def excludes = []
                try { excludes = d.excludeRules.collect { [group: it.group, module: it.module] } } catch (ignored) {}
                [group: d.group, name: d.name, version: d.version, category: category, classifier: classifier, excludes: excludes]
            }
        }
        def failures = [:]
        ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath', 'annotationProcessor'].each { c ->
            def conf = p.configurations.findByName(c)
            if (conf == null || !conf.canBeResolved) return
            try {
                conf.incoming.resolutionResult.allComponents.each { comp ->
                    def id = comp.id
                    if (id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                        info.resolved["${id.group}:${id.module}"] = id.version
                    }
                }
            } catch (e) {
                def cause = e
                while (cause.cause != null && cause.cause != cause) cause = cause.cause
                def why = cause.message?.readLines()?.find { it.trim() }?.trim() ?: cause.class.simpleName
                failures.computeIfAbsent(why) { [] } << c
            }
        }
        if (failures) info.resolveError = failures.collect { why, confs -> confs.join(', ') + ' (' + why + ')' }.join('; ')
        p.repositories.each { r ->
            if (r instanceof MavenArtifactRepository) info.repositories << r.url.toString()
        }
        return info
}

gradle.allprojects { Project p ->
    p.tasks.register('jpmModel') { t ->
        t.group = 'help'
        t.description = 'Reports the build model for jpm import'
        try { t.notCompatibleWithConfigurationCache('jpm reads the build model') } catch (ignored) {}
        t.doLast {
            def info = jpmModel(p)
            info.gradleVersion = p.gradle.gradleVersion
            def name = p.path == ':' ? 'root' : p.path.substring(1).replace(':', '__')
            new File(jpmModelDir, name + '.json').text = JsonOutput.toJson(info)
        }
    }
}
""";
}
