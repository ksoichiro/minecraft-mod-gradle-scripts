package com.github.ksoichiro.mcmod

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project

class ReleaseCurseForgeTasks {
    static void register(Project project, McmodExtension ext) {
        project.tasks.register('releaseCurseForge') {
            group = ext.projectDisplayName.toLowerCase() + ' release'
            description = 'Release JARs to CurseForge'

            doLast {
                def curseforgeToken = System.getenv('CURSEFORGE_TOKEN')
                if (!curseforgeToken) {
                    throw new GradleException('CURSEFORGE_TOKEN environment variable is not set.')
                }

                def projectId = ext.releaseCurseForge.projectId
                def projectName = ext.projectDisplayName
                def archivesName = ext.archivesName
                def apiBase = 'https://minecraft.curseforge.com/api'

                def releaseDir = project.file("${project.rootDir}/build/release")
                def jarPattern = ~/${archivesName}-(\d+(?:\.\d+)+)\+(\d+(?:\.\d+)+)-([a-z]+)\.jar/

                def jars = []
                if (project.hasProperty('jar')) {
                    jars.add(new File(releaseDir, project.property('jar').toString()))
                } else {
                    releaseDir.listFiles()?.findAll { it.name.endsWith('.jar') }?.sort()?.each { jars.add(it) }
                }

                if (jars.isEmpty()) {
                    throw new GradleException("No JAR files found in ${releaseDir}. Run collectJars first.")
                }

                project.logger.lifecycle("Fetching CurseForge game version types...")
                def versionTypes = cfApiGet("${apiBase}/game/version-types", curseforgeToken)
                def versionsJson = cfApiGet("${apiBase}/game/versions", curseforgeToken)

                jars.each { jarFile ->
                    def matcher = jarPattern.matcher(jarFile.name)
                    if (!matcher.matches()) {
                        throw new GradleException("Invalid JAR file name: ${jarFile.name}")
                    }
                    def jarModVersion = matcher.group(1)
                    def gameVersion = matcher.group(2)
                    def loader = matcher.group(3)

                    project.logger.lifecycle("Releasing ${jarFile.name} (mod: ${jarModVersion}, mc: ${gameVersion}, loader: ${loader})")

                    def cfLoader
                    switch (loader) {
                        case 'fabric': cfLoader = 'Fabric'; break
                        case 'neoforge': cfLoader = 'NeoForge'; break
                        case 'forge': cfLoader = 'Forge'; break
                        default: throw new GradleException("Unknown loader: ${loader}")
                    }

                    // Try exact version first (e.g., "Minecraft 26.1"), then major (e.g., "Minecraft 1.21")
                    def mcMajor = gameVersion.substring(0, gameVersion.lastIndexOf('.'))
                    def mcTypeId = versionTypes.find { it.name == "Minecraft ${gameVersion}" }?.id
                    if (!mcTypeId) {
                        mcTypeId = versionTypes.find { it.name == "Minecraft ${mcMajor}" }?.id
                    }
                    if (!mcTypeId) {
                        throw new GradleException("Could not find version type for Minecraft ${gameVersion} or ${mcMajor}")
                    }

                    def gameVersionId = versionsJson.find { it.name == gameVersion && it.gameVersionTypeID == mcTypeId }?.id
                    if (!gameVersionId) {
                        throw new GradleException("Could not find CurseForge game version ID for ${gameVersion}")
                    }

                    def loaderVersionId = versionsJson.find { it.name == cfLoader }?.id
                    if (!loaderVersionId) {
                        throw new GradleException("Could not find CurseForge version ID for loader ${cfLoader}")
                    }

                    def changelog = ChangelogUtils.readChangelog(project.rootDir, jarModVersion)

                    def metadata = JsonOutput.toJson([
                        changelog: changelog,
                        changelogType: 'markdown',
                        displayName: "${projectName} ${jarModVersion}",
                        gameVersions: [gameVersionId, loaderVersionId],
                        releaseType: 'release'
                    ])

                    def boundary = "----GradleBoundary${System.currentTimeMillis()}"
                    def url = new URL("${apiBase}/projects/${projectId}/upload-file")
                    def conn = url.openConnection()
                    conn.doOutput = true
                    conn.setRequestMethod('POST')
                    conn.setRequestProperty('X-Api-Token', curseforgeToken)
                    conn.setRequestProperty('Content-Type', "multipart/form-data; boundary=${boundary}")

                    conn.outputStream.withStream { os ->
                        os.write("--${boundary}\r\n".bytes)
                        os.write("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n".bytes)
                        os.write("${metadata}\r\n".bytes)

                        os.write("--${boundary}\r\n".bytes)
                        os.write("Content-Disposition: form-data; name=\"file\"; filename=\"${jarFile.name}\"\r\n".bytes)
                        os.write("Content-Type: application/java-archive\r\n\r\n".bytes)
                        jarFile.withInputStream { is -> os << is }
                        os.write("\r\n".bytes)

                        os.write("--${boundary}--\r\n".bytes)
                    }

                    def responseCode = conn.responseCode
                    if (responseCode >= 200 && responseCode < 300) {
                        project.logger.lifecycle("SUCCESS: ${jarFile.name} released to CurseForge")
                    } else {
                        def errorBody = conn.errorStream?.text ?: 'No error body'
                        throw new GradleException("CurseForge upload failed (HTTP ${responseCode}): ${errorBody}")
                    }
                }
            }
        }
    }

    private static Object cfApiGet(String urlString, String token) {
        def url = new URL(urlString)
        def conn = url.openConnection()
        conn.setRequestProperty('X-Api-Token', token)
        return new JsonSlurper().parseText(conn.inputStream.text)
    }
}
