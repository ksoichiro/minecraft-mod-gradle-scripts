package com.github.ksoichiro.mcmod

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project

class ReleaseModrinthTasks {
    static void register(Project project, McmodExtension ext) {
        project.tasks.register('releaseModrinth') {
            group = ext.projectDisplayName.toLowerCase() + ' release'
            description = 'Release JARs to Modrinth'

            doLast {
                def modrinthToken = System.getenv('MODRINTH_TOKEN')
                if (!modrinthToken) {
                    throw new GradleException('MODRINTH_TOKEN environment variable is not set.')
                }

                def projectId = ext.releaseModrinth.projectId
                def projectName = ext.projectDisplayName
                def archivesName = ext.archivesName
                def useArchitectury = project.hasProperty('architectury_api_version')
                def depArchitecturyId = ext.releaseModrinth.depArchitecturyId
                def depFabricApiId = ext.releaseModrinth.depFabricApiId

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

                jars.each { jarFile ->
                    def matcher = jarPattern.matcher(jarFile.name)
                    if (!matcher.matches()) {
                        throw new GradleException("Invalid JAR file name: ${jarFile.name}")
                    }
                    def jarModVersion = matcher.group(1)
                    def gameVersion = matcher.group(2)
                    def loader = matcher.group(3)

                    project.logger.lifecycle("Releasing ${jarFile.name} (mod: ${jarModVersion}, mc: ${gameVersion}, loader: ${loader})")

                    def changelog = ChangelogUtils.readChangelog(project.rootDir, jarModVersion)

                    def dependencies = []
                    if (useArchitectury) {
                        dependencies.add([project_id: depArchitecturyId, dependency_type: 'required'])
                    }
                    if (loader == 'fabric') {
                        dependencies.add([project_id: depFabricApiId, dependency_type: 'required'])
                    }

                    // Check for existing version
                    def checkUrl = new URL("https://api.modrinth.com/v2/project/${projectId}/version")
                    def checkConn = checkUrl.openConnection()
                    checkConn.setRequestProperty('User-Agent', 'minecraft-mod-gradle-scripts')
                    def versions = new JsonSlurper().parseText(checkConn.inputStream.text)
                    def exists = versions.any { v ->
                        v.version_number == jarModVersion &&
                        v.loaders.contains(loader) &&
                        v.game_versions.contains(gameVersion)
                    }
                    if (exists) {
                        throw new GradleException("Version ${jarModVersion} for ${loader} ${gameVersion} already exists on Modrinth.")
                    }

                    def data = JsonOutput.toJson([
                        project_id: projectId,
                        name: "${projectName} ${jarModVersion}",
                        version_number: jarModVersion,
                        version_type: 'release',
                        loaders: [loader],
                        game_versions: [gameVersion],
                        status: 'listed',
                        featured: false,
                        file_parts: ['file'],
                        changelog: changelog,
                        dependencies: dependencies
                    ])

                    def boundary = "----GradleBoundary${System.currentTimeMillis()}"
                    def url = new URL("https://api.modrinth.com/v2/version")
                    def conn = url.openConnection()
                    conn.doOutput = true
                    conn.setRequestMethod('POST')
                    conn.setRequestProperty('Authorization', modrinthToken)
                    conn.setRequestProperty('User-Agent', 'minecraft-mod-gradle-scripts')
                    conn.setRequestProperty('Content-Type', "multipart/form-data; boundary=${boundary}")

                    conn.outputStream.withStream { os ->
                        os.write("--${boundary}\r\n".bytes)
                        os.write("Content-Disposition: form-data; name=\"data\"\r\n\r\n".bytes)
                        os.write("${data}\r\n".bytes)

                        os.write("--${boundary}\r\n".bytes)
                        os.write("Content-Disposition: form-data; name=\"file\"; filename=\"${jarFile.name}\"\r\n".bytes)
                        os.write("Content-Type: application/java-archive\r\n\r\n".bytes)
                        jarFile.withInputStream { is -> os << is }
                        os.write("\r\n".bytes)

                        os.write("--${boundary}--\r\n".bytes)
                    }

                    def responseCode = conn.responseCode
                    if (responseCode >= 200 && responseCode < 300) {
                        project.logger.lifecycle("SUCCESS: ${jarFile.name} released to Modrinth")
                    } else {
                        def errorBody = conn.errorStream?.text ?: 'No error body'
                        throw new GradleException("Modrinth upload failed (HTTP ${responseCode}): ${errorBody}")
                    }
                }
            }
        }
    }
}
