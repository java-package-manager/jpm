package dev.jpm.resolve;

/**
 * Reaches MavenRepo's package-private parser from the resolve tests.
 */
final class MavenRepoAccess {
    private MavenRepoAccess() {
    }

    static String snapshotVersion(String metadata, String version, String classifier, String ext) {
        return dev.jpm.maven.MavenRepo.snapshotVersionForTests(metadata, version, classifier, ext);
    }
}
