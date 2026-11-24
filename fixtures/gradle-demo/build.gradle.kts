plugins {
    id("com.diffplug.spotless") version "7.0.2"
}

subprojects {
    apply(plugin = "java")
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(17) }
    }
    repositories { mavenCentral() }
}

spotless {
    java {
        target("*/src/**/*.java")
        palantirJavaFormat("2.50.0")
        removeUnusedImports()
        importOrder()
    }
    kotlinGradle {
        ktlint()
    }
}
