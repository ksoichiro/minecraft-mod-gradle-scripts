package com.github.ksoichiro.mcmod

import org.gradle.api.Action

class McmodExtension {

    // --- Shared properties ---
    String archivesName
    String modVersion
    List<String> supportedVersions
    Map<String, String> hotfixVersions
    List<String> fabricOnlyVersions
    String projectDisplayName

    // --- Sub-extensions ---
    final MultiVersionExtension multiVersion = new MultiVersionExtension()
    final ResourceValidationExtension resourceValidation = new ResourceValidationExtension()
    final ProdRunExtension prodRun = new ProdRunExtension()
    final ReleaseModrinthExtension releaseModrinth = new ReleaseModrinthExtension()
    final ReleaseCurseForgeExtension releaseCurseForge = new ReleaseCurseForgeExtension()

    void multiVersion(Action<? super MultiVersionExtension> action) {
        action.execute(multiVersion)
    }

    void resourceValidation(Action<? super ResourceValidationExtension> action) {
        action.execute(resourceValidation)
    }

    void prodRun(Action<? super ProdRunExtension> action) {
        action.execute(prodRun)
    }

    void releaseModrinth(Action<? super ReleaseModrinthExtension> action) {
        action.execute(releaseModrinth)
    }

    void releaseCurseForge(Action<? super ReleaseCurseForgeExtension> action) {
        action.execute(releaseCurseForge)
    }

    // --- Sub-extension classes ---

    static class MultiVersionExtension {
        boolean enabled = true
    }

    static class ResourceValidationExtension {
        boolean enabled = true
        List<String> sharedResourceDirs
        String entityIdFile
        String itemIdFile
        List<String> excludedEntities
        List<String> translationVersions
        Map<String, String> translationHotfixVersions
    }

    static class ProdRunExtension {
        boolean enabled = false
        String baseDir
    }

    static class ReleaseModrinthExtension {
        boolean enabled = false
        String projectId
        String depArchitecturyId
        String depFabricApiId
    }

    static class ReleaseCurseForgeExtension {
        boolean enabled = false
        String projectId
    }
}
