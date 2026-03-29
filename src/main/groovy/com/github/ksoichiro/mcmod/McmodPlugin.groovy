package com.github.ksoichiro.mcmod

import org.gradle.api.Plugin
import org.gradle.api.Project

class McmodPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def ext = project.extensions.create('mcmod', McmodExtension)

        project.afterEvaluate {
            applyDefaults(project, ext)

            if (ext.multiVersion.enabled) {
                MultiVersionTasks.register(project, ext)
            }
            if (ext.resourceValidation.enabled) {
                ResourceValidationTasks.register(project, ext)
            }
            if (ext.releaseModrinth.enabled) {
                ReleaseModrinthTasks.register(project, ext)
            }
            if (ext.releaseCurseForge.enabled) {
                ReleaseCurseForgeTasks.register(project, ext)
            }
            if (ext.prodRun.enabled) {
                ProdRunTasks.register(project, ext)
            }
        }
    }

    private static void applyDefaults(Project project, McmodExtension ext) {
        if (ext.archivesName == null && project.hasProperty('archives_name')) {
            ext.archivesName = project.property('archives_name').toString()
        }
        if (ext.modVersion == null && project.hasProperty('mod_version')) {
            ext.modVersion = project.property('mod_version').toString()
        }
        if (ext.supportedVersions == null && project.hasProperty('supported_mc_versions')) {
            ext.supportedVersions = project.property('supported_mc_versions').toString().split(',').collect { it.trim() }
        }
        if (ext.hotfixVersions == null && project.hasProperty('hotfix_mc_versions')) {
            ext.hotfixVersions = [:]
            project.property('hotfix_mc_versions').toString().split(',').each {
                def parts = it.trim().split('=')
                ext.hotfixVersions[parts[0]] = parts[1]
            }
        }
        if (ext.hotfixVersions == null) {
            ext.hotfixVersions = [:]
        }
        if (ext.fabricOnlyVersions == null && project.hasProperty('fabric_only_mc_versions')) {
            ext.fabricOnlyVersions = project.property('fabric_only_mc_versions').toString().split(',').collect { it.trim() }
        }
        if (ext.fabricOnlyVersions == null) {
            ext.fabricOnlyVersions = []
        }
        if (ext.projectDisplayName == null && project.hasProperty('release_project_name')) {
            ext.projectDisplayName = project.property('release_project_name').toString()
        }
        if (ext.projectDisplayName == null) {
            ext.projectDisplayName = project.name
        }

        // Resource validation defaults
        def rv = ext.resourceValidation
        if (rv.sharedResourceDirs == null && project.hasProperty('shared_resource_dirs')) {
            rv.sharedResourceDirs = project.property('shared_resource_dirs').toString().split(',').collect { it.trim() }
        }
        if (rv.entityIdFile == null && project.hasProperty('entity_id_file')) {
            rv.entityIdFile = project.property('entity_id_file').toString()
        }
        if (rv.itemIdFile == null && project.hasProperty('item_id_file')) {
            rv.itemIdFile = project.property('item_id_file').toString()
        }
        if (rv.excludedEntities == null && project.hasProperty('excluded_entities')) {
            rv.excludedEntities = project.property('excluded_entities').toString().split(',').collect { it.trim() }
        }
        if (rv.excludedEntities == null) {
            rv.excludedEntities = []
        }
        if (rv.translationVersions == null && project.hasProperty('translation_versions')) {
            rv.translationVersions = project.property('translation_versions').toString().split(',').collect { it.trim() }
        }
        if (rv.translationHotfixVersions == null && project.hasProperty('translation_hotfix_versions')) {
            rv.translationHotfixVersions = [:]
            project.property('translation_hotfix_versions').toString().split(',').each {
                def parts = it.trim().split('=')
                rv.translationHotfixVersions[parts[0]] = parts[1]
            }
        }

        // ProdRun defaults
        if (ext.prodRun.baseDir == null && project.hasProperty('prod_base_dir')) {
            ext.prodRun.baseDir = project.property('prod_base_dir').toString()
        }
        if (ext.prodRun.baseDir == null) {
            ext.prodRun.baseDir = "${project.rootDir}/run-prod"
        }

        // Release Modrinth defaults
        def rm = ext.releaseModrinth
        if (rm.projectId == null && project.hasProperty('modrinth_project_id')) {
            rm.projectId = project.property('modrinth_project_id').toString()
        }
        if (rm.depArchitecturyId == null) {
            rm.depArchitecturyId = project.findProperty('modrinth_dep_architectury')?.toString() ?: 'lhGA9TYQ'
        }
        if (rm.depFabricApiId == null) {
            rm.depFabricApiId = project.findProperty('modrinth_dep_fabric_api')?.toString() ?: 'P7dR8mSH'
        }

        // Release CurseForge defaults
        def rc = ext.releaseCurseForge
        if (rc.projectId == null && project.hasProperty('curseforge_project_id')) {
            rc.projectId = project.property('curseforge_project_id').toString()
        }
    }
}
