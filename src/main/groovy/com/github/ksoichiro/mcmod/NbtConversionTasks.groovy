package com.github.ksoichiro.mcmod

import org.gradle.api.GradleException
import org.gradle.api.Project

class NbtConversionTasks {

    static void register(Project project, McmodExtension ext) {
        def nbtExt = ext.nbtConversion
        if (nbtExt.sourceVersion == null || nbtExt.targetVersion == null) {
            throw new GradleException("nbtConversion requires sourceVersion and targetVersion to be set")
        }
        if (nbtExt.inputDir == null || nbtExt.outputDir == null) {
            throw new GradleException("nbtConversion requires inputDir and outputDir to be set")
        }

        def inputDir = project.file(nbtExt.inputDir)
        def outputDir = project.file(nbtExt.outputDir)

        project.tasks.register('convertNbt') {
            group = 'build'
            description = "Converts NBT structure files from ${nbtExt.sourceVersion} to ${nbtExt.targetVersion}"

            inputs.dir(inputDir).optional()
            outputs.dir(outputDir)

            doLast {
                if (!inputDir.exists()) {
                    project.logger.lifecycle("NBT conversion: input directory does not exist, skipping: ${inputDir}")
                    return
                }

                def converter = resolveConverter(nbtExt)
                def targetDataVersion = getDataVersion(nbtExt.targetVersion)

                def nbtFiles = project.fileTree(dir: inputDir, includes: ['**/*.nbt']).files
                if (nbtFiles.isEmpty()) {
                    project.logger.lifecycle("NBT conversion: no .nbt files found in ${inputDir}")
                    return
                }

                outputDir.mkdirs()

                int convertedCount = 0
                nbtFiles.each { File nbtFile ->
                    def relativePath = inputDir.toPath().relativize(nbtFile.toPath())
                    def outFile = outputDir.toPath().resolve(relativePath).toFile()
                    outFile.parentFile.mkdirs()

                    try {
                        def inputBytes = nbtFile.bytes
                        def outputBytes = converter.convert(inputBytes, targetDataVersion)
                        outFile.bytes = outputBytes
                        convertedCount++
                    } catch (Exception e) {
                        throw new GradleException("Failed to convert ${nbtFile.name}: ${e.message}", e)
                    }
                }

                project.logger.lifecycle("NBT conversion: converted ${convertedCount} file(s) from ${nbtExt.sourceVersion} to ${nbtExt.targetVersion}")
            }
        }

        // Wire convertNbt into processResources
        project.tasks.matching { it.name == 'processResources' }.configureEach {
            dependsOn('convertNbt')
        }
        project.afterEvaluate {
            // Add outputDir as a resource source so converted files end up in the JAR
            if (project.plugins.hasPlugin('java') || project.plugins.hasPlugin('java-library')) {
                project.sourceSets.matching { it.name == 'main' }.configureEach {
                    resources.srcDir(outputDir)
                }
            }
        }
    }

    private static NbtConverter resolveConverter(McmodExtension.NbtConversionExtension ext) {
        if (ext.converterClass != null) {
            return ext.converterClass.getDeclaredConstructor().newInstance()
        }

        // Auto-select based on version pair
        def sourceMajorMinor = majorMinor(ext.sourceVersion)
        def targetMajorMinor = majorMinor(ext.targetVersion)

        if (sourceMajorMinor == '1.21' && targetMajorMinor == '1.20') {
            return new V1_21ToV1_20NbtConverter()
        }

        return new DefaultNbtConverter()
    }

    private static String majorMinor(String version) {
        def parts = version.split('\\.')
        if (parts.length >= 2) {
            return "${parts[0]}.${parts[1]}"
        }
        return version
    }

    /**
     * Map Minecraft version strings to DataVersion integers.
     * See: https://minecraft.wiki/w/Data_version
     */
    private static final Map<String, Integer> DATA_VERSIONS = [
        '1.20'  : 3463,
        '1.20.1': 3465,
        '1.20.2': 3578,
        '1.20.3': 3698,
        '1.20.4': 3700,
        '1.20.5': 3837,
        '1.20.6': 3839,
        '1.21'  : 3953,
        '1.21.1': 3955,
        '1.21.2': 4080,
        '1.21.3': 4082,
        '1.21.4': 4189,
    ]

    static int getDataVersion(String mcVersion) {
        def dv = DATA_VERSIONS[mcVersion]
        if (dv == null) {
            throw new GradleException("Unknown Minecraft version for DataVersion mapping: ${mcVersion}. Set converterClass explicitly or add mapping.")
        }
        return dv
    }
}
