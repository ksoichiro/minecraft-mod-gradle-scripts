package com.github.ksoichiro.mcmod

import groovy.json.JsonException
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project

class ResourceValidationTasks {

    static void register(Project project, McmodExtension ext) {
        def archivesName = ext.archivesName
        if (archivesName == null) return

        project.tasks.register('validateResources') {
            group = 'verification'
            description = 'Validates JSON syntax and resource cross-references in assets'

            doLast {
                def sharedDirs = []
                if (ext.resourceValidation.sharedResourceDirs) {
                    ext.resourceValidation.sharedResourceDirs.each {
                        sharedDirs.add(project.file("${project.rootProject.projectDir}/${it.trim()}"))
                    }
                } else {
                    project.file("${project.rootProject.projectDir}/common").listFiles()?.findAll {
                        it.isDirectory() && it.name.startsWith('shared') && project.file("${it}/src/main/resources").exists()
                    }?.sort()?.each {
                        sharedDirs.add(project.file("${it}/src/main/resources"))
                    }
                }
                def errors = []

                // 1. JSON syntax check across all shared directories
                project.logger.lifecycle("Checking JSON syntax...")
                def jsonSlurper = new JsonSlurper()
                int totalJsonFiles = 0
                sharedDirs.each { resourceDir ->
                    if (!resourceDir.exists()) return
                    def jsonFiles = project.fileTree(dir: resourceDir, includes: ['**/*.json'])
                    jsonFiles.each { File jsonFile ->
                        try {
                            jsonSlurper.parseText(jsonFile.text)
                        } catch (JsonException e) {
                            def relPath = resourceDir.toPath().relativize(jsonFile.toPath()).toString()
                            errors << "JSON syntax error in ${relPath}: ${e.message}"
                        } catch (Exception e) {
                            def relPath = resourceDir.toPath().relativize(jsonFile.toPath()).toString()
                            errors << "Failed to parse ${relPath}: ${e.message}"
                        }
                    }
                    totalJsonFiles += jsonFiles.files.size()
                }
                project.logger.lifecycle("  Checked ${totalJsonFiles} JSON files across ${sharedDirs.size()} shared directories")

                // 2. Blockstate -> model reference check (blockstates and block models are in the first shared dir)
                project.logger.lifecycle("Checking blockstate -> model references...")
                def assetsDir = project.file("${sharedDirs[0]}/assets/${archivesName}")
                def blockstatesDir = project.file("${assetsDir}/blockstates")
                def modelsBlockDir = project.file("${assetsDir}/models/block")
                int blockstateRefs = 0
                if (blockstatesDir.exists()) {
                    blockstatesDir.eachFileMatch(~/.+\.json/) { File bsFile ->
                        try {
                            def bs = jsonSlurper.parseText(bsFile.text)
                            def models = extractModelReferences(bs)
                            models.each { String modelRef ->
                                if (modelRef.startsWith("${archivesName}:block/")) {
                                    def modelName = modelRef.replace("${archivesName}:block/", "")
                                    def modelFile = new File(modelsBlockDir, "${modelName}.json")
                                    if (!modelFile.exists()) {
                                        errors << "Blockstate ${bsFile.name} references model '${modelRef}' but ${modelFile.name} does not exist"
                                    }
                                    blockstateRefs++
                                }
                            }
                        } catch (Exception e) {
                            // JSON syntax errors already caught above
                        }
                    }
                }
                project.logger.lifecycle("  Checked ${blockstateRefs} blockstate -> model references")

                // 3. Model -> texture reference check across all shared directories
                project.logger.lifecycle("Checking model -> texture references...")
                // Collect all textures from all shared directories
                def allTextures = [] as Set
                sharedDirs.each { resourceDir ->
                    def texturesDir = project.file("${resourceDir}/assets/${archivesName}/textures")
                    if (texturesDir.exists()) {
                        project.fileTree(dir: texturesDir, includes: ['**/*.png']).each { File textureFile ->
                            def relPath = texturesDir.toPath().relativize(textureFile.toPath()).toString().replace('.png', '')
                            allTextures << relPath
                        }
                    }
                }
                int textureRefs = 0
                sharedDirs.each { resourceDir ->
                    def modelsDir = project.file("${resourceDir}/assets/${archivesName}/models")
                    if (modelsDir.exists()) {
                        project.fileTree(dir: modelsDir, includes: ['**/*.json']).each { File modelFile ->
                            try {
                                def model = jsonSlurper.parseText(modelFile.text)
                                if (model instanceof Map && model.textures instanceof Map) {
                                    model.textures.each { key, value ->
                                        if (value instanceof String && value.startsWith("${archivesName}:")) {
                                            def texturePath = value.replace("${archivesName}:", "")
                                            if (!allTextures.contains(texturePath)) {
                                                def modelRelative = modelsDir.toPath().relativize(modelFile.toPath()).toString()
                                                errors << "Model ${modelRelative} references texture '${value}' but ${texturePath}.png does not exist in any shared directory"
                                            }
                                            textureRefs++
                                        }
                                        // Skip minecraft: prefixed references
                                    }
                                }
                            } catch (Exception e) {
                                // JSON syntax errors already caught above
                            }
                        }
                    }
                }
                project.logger.lifecycle("  Checked ${textureRefs} model -> texture references")

                // Report results
                if (errors.isEmpty()) {
                    project.logger.lifecycle("Resource validation passed: all references are valid.")
                } else {
                    errors.each { project.logger.error("  ERROR: ${it}") }
                    throw new GradleException("Resource validation failed with ${errors.size()} error(s)")
                }
            }
        }

        // Cross-version translation validation task
        // Validates that all registered entities/items have translations in all versions
        project.tasks.register('validateTranslations') {
            group = 'verification'
            description = 'Validates that translation keys exist across all Minecraft versions'

            doLast {
                def versions = []
                if (ext.resourceValidation.translationVersions) {
                    versions = ext.resourceValidation.translationVersions
                } else if (ext.supportedVersions) {
                    versions = ext.supportedVersions
                }

                // Map hotfix versions to their base module version
                def moduleVersionMap = [:]
                if (ext.resourceValidation.translationHotfixVersions) {
                    moduleVersionMap = ext.resourceValidation.translationHotfixVersions
                } else if (ext.hotfixVersions) {
                    moduleVersionMap = ext.hotfixVersions
                }

                def jsonSlurper = new JsonSlurper()
                def errors = []

                // Parse entity ID file
                def entityIds = []
                if (ext.resourceValidation.entityIdFile) {
                    def entityIdFile = project.file("${project.rootProject.projectDir}/${ext.resourceValidation.entityIdFile}")
                    entityIds = extractEnumValues(entityIdFile)
                }

                // Parse item ID file
                def itemIds = []
                if (ext.resourceValidation.itemIdFile) {
                    def itemIdFile = project.file("${project.rootProject.projectDir}/${ext.resourceValidation.itemIdFile}")
                    itemIds = extractEnumValues(itemIdFile)
                }

                def excludedEntities = ext.resourceValidation.excludedEntities ?: []

                project.logger.lifecycle("Validating translations across ${versions.size()} versions...")
                project.logger.lifecycle("  Found ${entityIds.size()} entities and ${itemIds.size()} items to validate")

                versions.each { version ->
                    def moduleVersion = moduleVersionMap.getOrDefault(version, version)
                    // Search for lang file in version-specific dir first, then shared directories
                    def langFile = project.file("${project.rootProject.projectDir}/common/${moduleVersion}/src/main/resources/assets/${archivesName}/lang/en_us.json")
                    if (!langFile.exists()) {
                        // Try shared directories in order: auto-detect from common/ directory
                        def sharedLangDirs = []
                        project.file("${project.rootProject.projectDir}/common").listFiles()?.findAll {
                            it.isDirectory() && it.name.startsWith('shared')
                        }?.sort()?.reverse()?.each {
                            sharedLangDirs.add(it.name)
                        }
                        for (sharedDir in sharedLangDirs) {
                            def candidate = project.file("${project.rootProject.projectDir}/common/${sharedDir}/src/main/resources/assets/${archivesName}/lang/en_us.json")
                            if (candidate.exists()) {
                                langFile = candidate
                                break
                            }
                        }
                    }
                    if (!langFile.exists()) {
                        errors << "[${version}] Language file not found in any shared directory"
                        return
                    }

                    def langMap = jsonSlurper.parseText(langFile.text)

                    // Check entity translations
                    entityIds.each { entityId ->
                        if (excludedEntities.contains(entityId)) return
                        // Use toString() to convert GString to String for proper Map key lookup
                        def key = "entity.${archivesName}.${entityId}".toString()
                        if (!langMap.containsKey(key)) {
                            errors << "[${version}] Missing entity translation: ${key}"
                        }
                    }

                    // Check spawn egg translations
                    entityIds.each { entityId ->
                        if (excludedEntities.contains(entityId)) return
                        // Use toString() to convert GString to String for proper Map key lookup
                        def key = "item.${archivesName}.${entityId}_spawn_egg".toString()
                        def spawnEggItemId = "${entityId}_spawn_egg".toString()
                        // Only check if the spawn egg item exists in the item ID list
                        if (itemIds.contains(spawnEggItemId)) {
                            if (!langMap.containsKey(key)) {
                                errors << "[${version}] Missing spawn egg translation: ${key}"
                            }
                        }
                    }
                }

                // Report results
                if (errors.isEmpty()) {
                    project.logger.lifecycle("Cross-version translation validation passed.")
                } else {
                    project.logger.lifecycle("")
                    errors.each { project.logger.error("  ERROR: ${it}") }
                    throw new GradleException("Translation validation failed with ${errors.size()} error(s)")
                }
            }
        }
    }

    // Extract enum values from a ModXxxId.java file
    // Matches patterns like: ENTITY_NAME("entity_name"),
    private static List<String> extractEnumValues(File file) {
        def ids = []
        if (!file.exists()) {
            return ids
        }
        def content = file.text
        def pattern = ~/(\w+)\s*\(\s*"([^"]+)"\s*\)/
        def matcher = pattern.matcher(content)
        while (matcher.find()) {
            ids << matcher.group(2)  // The string value in parentheses
        }
        return ids
    }

    // Extract all model references from a blockstate JSON structure
    private static List<String> extractModelReferences(Object node) {
        def models = []
        if (node instanceof Map) {
            node.each { key, value ->
                if (key == "model" && value instanceof String) {
                    models << value
                } else {
                    models.addAll(extractModelReferences(value))
                }
            }
        } else if (node instanceof List) {
            node.each { item ->
                models.addAll(extractModelReferences(item))
            }
        }
        return models
    }
}
