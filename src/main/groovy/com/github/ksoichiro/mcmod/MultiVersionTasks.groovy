package com.github.ksoichiro.mcmod

import org.gradle.api.GradleException
import org.gradle.api.Project

class MultiVersionTasks {

    static void register(Project project, McmodExtension ext) {
        def supportedVersions = ext.supportedVersions
        if (supportedVersions == null || supportedVersions.isEmpty()) return

        def hotfixVersions = ext.hotfixVersions
        def releaseVersions = supportedVersions.findAll { !hotfixVersions.containsKey(it) }
        def fabricOnlyVersions = ext.fabricOnlyVersions

        def baseName = ext.projectDisplayName.toLowerCase()
        def taskGroupName = baseName + ' build'
        def taskGroupTest = baseName + ' test'
        def taskGroupRun = baseName + ' run'
        def taskGroupVerification = baseName + ' verification'

        // ============================================================
        // Clean Tasks
        // ============================================================

        // Register clean task for each supported version
        supportedVersions.each { version ->
            def versionSuffix = version.replace('.', '_')

            project.tasks.register("clean${versionSuffix}") {
                group = taskGroupName
                description = "Clean build outputs for Minecraft ${version}"

                doLast {
                    project.exec {
                        commandLine './gradlew', 'clean', "-Ptarget_mc_version=${version}"
                    }
                }
            }
        }

        /**
         * Clean for all supported Minecraft versions
         * Usage: ./gradlew cleanAll
         *
         * Note: Hotfix versions are excluded because they share modules with their base version.
         * Cleaning the base version also cleans the modules used by hotfix versions.
         */
        project.tasks.register('cleanAll') {
            group = taskGroupName
            description = "Clean build outputs for all supported Minecraft versions (${releaseVersions.join(', ')})"

            doLast {
                releaseVersions.each { version ->
                    project.logger.lifecycle(">>> Cleaning for Minecraft ${version}...")

                    project.exec {
                        commandLine './gradlew', 'clean', "-Ptarget_mc_version=${version}"
                    }
                }

                // Clean IDE-generated bin/ directories that can interfere with builds/tests
                // (e.g., IntelliJ/Eclipse output directories with unprocessed placeholders)
                def subprojects = []
                releaseVersions.each { version ->
                    subprojects.add("common/${version}")
                    subprojects.add("fabric/${version}")
                    if (!fabricOnlyVersions.contains(version)) {
                        subprojects.add("neoforge/${version}")
                    }
                }
                def deletedCount = 0
                subprojects.each { subdir ->
                    def binDir = project.file("${subdir}/bin")
                    if (binDir.exists()) {
                        project.logger.lifecycle(">>> Deleting IDE-generated directory: ${binDir}")
                        project.delete binDir
                        deletedCount++
                    }
                }
                if (deletedCount > 0) {
                    project.logger.lifecycle(">>> Deleted ${deletedCount} IDE-generated bin/ directories")
                }

                // Clean Architectury Transformer runtime caches in run directories
                // These caches can cause "Unresolved compilation problems" errors when
                // stale transformed classes remain after code changes
                def runDirs = []
                releaseVersions.each { version ->
                    runDirs.add("fabric/${version}/run")
                    if (!fabricOnlyVersions.contains(version)) {
                        runDirs.add("neoforge/${version}/run")
                    }
                }
                def transformerDeletedCount = 0
                runDirs.each { runDir ->
                    def transformerDir = project.file("${runDir}/.architectury-transformer")
                    if (transformerDir.exists()) {
                        project.logger.lifecycle(">>> Deleting Architectury Transformer cache: ${transformerDir}")
                        project.delete transformerDir
                        transformerDeletedCount++
                    }
                }
                if (transformerDeletedCount > 0) {
                    project.logger.lifecycle(">>> Deleted ${transformerDeletedCount} Architectury Transformer cache directories")
                }

                project.logger.lifecycle('')
                project.logger.lifecycle('All versions cleaned successfully.')
            }
        }

        // ============================================================
        // Build Tasks
        // ============================================================

        // Register build task for each supported version
        supportedVersions.each { version ->
            def versionSuffix = version.replace('.', '_')

            project.tasks.register("build${versionSuffix}") {
                group = taskGroupName
                description = "Build mod for Minecraft ${version}"

                doFirst {
                    project.logger.lifecycle('')
                    project.logger.lifecycle('====================================')
                    project.logger.lifecycle("Building for Minecraft ${version}...")
                    project.logger.lifecycle('====================================')
                    project.logger.lifecycle('')
                }

                doLast {
                    project.exec {
                        commandLine './gradlew', 'clean', 'build', "-Ptarget_mc_version=${version}", '-x', 'test'
                    }
                }
            }
        }

        /**
         * Build for all supported Minecraft versions (for release)
         * Usage: ./gradlew buildAll
         *
         * Note: Hotfix versions are excluded because they share modules with their base version.
         * The base version JAR files are compatible with hotfix versions.
         * Use gameTestAll to verify hotfix version runtime compatibility.
         */
        project.tasks.register('buildAll') {
            group = taskGroupName
            description = "Build mod for all supported Minecraft versions (${releaseVersions.join(', ')}) - for release"

            doFirst {
                project.logger.lifecycle('')
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('Building for ALL supported Minecraft versions...')
                project.logger.lifecycle("Versions: ${releaseVersions.join(', ')}")
                if (!hotfixVersions.isEmpty()) {
                    project.logger.lifecycle("(${hotfixVersions.keySet().join(', ')} excluded - uses base version JAR files)")
                }
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('')
            }

            doLast {
                def maxParallel = 3
                def passed = java.util.Collections.synchronizedList([])
                def failed = java.util.Collections.synchronizedList([])

                def gradlew = new File(project.projectDir, 'gradlew').absolutePath
                def logDir = new File(project.projectDir, 'build')
                logDir.mkdirs()

                def executor = java.util.concurrent.Executors.newFixedThreadPool(maxParallel)
                def futures = []

                releaseVersions.each { version ->
                    def future = executor.submit({
                        project.logger.lifecycle(">>> Building for Minecraft ${version}...")

                        def logFile = new File(logDir, "build-${version}.log")
                        try {
                            def pb = new ProcessBuilder(
                                gradlew, 'clean', 'build',
                                "-Ptarget_mc_version=${version}", '-x', 'test',
                                '--project-cache-dir', ".gradle/build-${version}",
                                '--no-parallel'
                            )
                            pb.directory(project.projectDir)
                            pb.redirectErrorStream(true)
                            pb.redirectOutput(logFile)

                            def process = pb.start()
                            def exitCode = process.waitFor()

                            if (exitCode == 0) {
                                passed.add(version)
                                project.logger.lifecycle("SUCCESS: Minecraft ${version}")
                            } else {
                                failed.add(version)
                                project.logger.error("FAILED: Minecraft ${version} (see ${logFile.absolutePath})")
                            }
                        } catch (Exception e) {
                            failed.add(version)
                            project.logger.error("FAILED: Minecraft ${version} (${e.message})")
                        }
                    } as Runnable)
                    futures.add(future)
                }

                // Wait for all builds to complete
                futures.each { it.get() }
                executor.shutdown()

                project.logger.lifecycle('')
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('Build Summary:')
                project.logger.lifecycle('====================================================')

                if (failed.isEmpty()) {
                    project.logger.lifecycle('All versions built successfully!')
                    releaseVersions.each { version ->
                        project.logger.lifecycle("   - Minecraft ${version}")
                    }
                } else {
                    project.logger.lifecycle("${failed.size()} version(s) failed:")
                    failed.sort().each { version ->
                        project.logger.lifecycle("   - Minecraft ${version}")
                    }
                    project.logger.lifecycle('')
                    project.logger.lifecycle("Logs: ${logDir.absolutePath}/build-*.log")
                }

                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('')

                if (!failed.isEmpty()) {
                    throw new GradleException("Build failed for: ${failed.sort().join(', ')}")
                }
            }
        }

        // ============================================================
        // Run Client Tasks (convenience shortcuts)
        // ============================================================

        supportedVersions.each { version ->
            def versionSuffix = version.replace('.', '_')
            def loaders = fabricOnlyVersions.contains(version) ? ['fabric'] : ['fabric', 'neoforge']

            loaders.each { loader ->
                def loaderName = loader == 'neoforge' ? 'NeoForge' : loader.capitalize()
                def taskName = "runClient${loaderName}${versionSuffix}"

                project.tasks.register(taskName) {
                    group = taskGroupRun
                    description = "Run ${loaderName} client for Minecraft ${version}"

                    doLast {
                        def gradlew = new File(project.projectDir, 'gradlew').absolutePath
                        def cmd = [
                            gradlew,
                            ':' + loader + ':runClient',
                            '-Ptarget_mc_version=' + version,
                        ] as List<String>

                        def pb = new ProcessBuilder(cmd)
                        pb.directory(project.projectDir)
                        pb.redirectErrorStream(true)

                        def process = pb.start()

                        // Forward output preserving ANSI escape sequences
                        def outputThread = Thread.start {
                            def is = process.inputStream
                            def buf = new byte[8192]
                            int len
                            while ((len = is.read(buf)) != -1) {
                                System.out.write(buf, 0, len)
                                System.out.flush()
                            }
                        }

                        def exitCode = process.waitFor()
                        outputThread.join()
                        if (exitCode != 0) {
                            throw new GradleException("${loader} client for ${version} exited with code ${exitCode}")
                        }
                    }
                }
            }
        }

        // ============================================================
        // Test Tasks
        // ============================================================

        /**
         * Run unit tests for all supported Minecraft versions
         * Usage: ./gradlew testAll
         *
         * Runs only common module JUnit tests (not GameTests).
         * GameTests are run separately via gameTestAll.
         *
         * Note: Hotfix versions are excluded because they share modules with their base version.
         * Running tests for the base version covers the same code used by hotfix versions.
         */
        project.tasks.register('testAll') {
            group = taskGroupTest
            description = "Run unit tests for all supported Minecraft versions (${releaseVersions.join(', ')})"

            doFirst {
                project.logger.lifecycle('')
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('Running unit tests for ALL supported Minecraft versions...')
                project.logger.lifecycle("Versions: ${releaseVersions.join(', ')}")
                if (!hotfixVersions.isEmpty()) {
                    project.logger.lifecycle("(${hotfixVersions.keySet().join(', ')} excluded - shares modules with base version)")
                }
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('')
            }

            doLast {
                def failed = []

                releaseVersions.each { version ->
                    project.logger.lifecycle('')
                    project.logger.lifecycle(">>> Running unit tests for Minecraft ${version}...")
                    project.logger.lifecycle('')

                    try {
                        project.exec {
                            commandLine './gradlew', ":common-${version}:test", "-Ptarget_mc_version=${version}"
                            errorOutput = System.err
                        }
                        project.logger.lifecycle("SUCCESS: Minecraft ${version}")
                    } catch (Exception e) {
                        project.logger.error("FAILED: Minecraft ${version}")
                        failed.add(version)
                    }
                }

                project.logger.lifecycle('')
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('Unit Test Summary:')
                project.logger.lifecycle('====================================================')

                if (failed.isEmpty()) {
                    project.logger.lifecycle('All versions passed!')
                    releaseVersions.each { version ->
                        project.logger.lifecycle("   - Minecraft ${version}")
                    }
                } else {
                    project.logger.lifecycle("${failed.size()} version(s) failed:")
                    failed.each { version ->
                        project.logger.lifecycle("   - Minecraft ${version}")
                    }
                    throw new GradleException("Unit tests failed for: ${failed.join(', ')}")
                }

                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('')
            }
        }

        // ============================================================
        // GameTest Tasks
        // ============================================================

        /**
         * Run GameTests for all supported Minecraft versions and loaders
         * Usage: ./gradlew gameTestAll
         *
         * Configurations are grouped by Minecraft version. Hotfix versions that share modules
         * with a base version run sequentially in the same thread to avoid build conflicts.
         *
         * Within each version group, loaders run in a single Gradle process with
         * --parallel to avoid concurrent builds of the shared common module.
         * Each subprocess uses --project-cache-dir to isolate from the outer process.
         *
         * Log files are saved to build/gametest-<version>.log for debugging.
         */
        project.tasks.register('gameTestAll') {
            group = taskGroupTest
            description = 'Run GameTests for all supported Minecraft versions and loaders'

            doLast {
                // Build version groups dynamically:
                // - Each base version gets its own group
                // - Hotfix versions are appended to their base version's group
                def gameTestGroups = []
                def processedVersions = [] as Set

                supportedVersions.each { version ->
                    if (processedVersions.contains(version)) return

                    def group = [versions: [version]]
                    // Find any hotfix versions that map to this version
                    hotfixVersions.each { hotfix, base ->
                        if (base == version) {
                            group.versions.add(hotfix)
                            processedVersions.add(hotfix)
                        }
                    }

                    def tasks = fabricOnlyVersions.contains(version)
                        ? [':fabric:runGameTest']
                        : [':fabric:runGameTest', ':neoforge:runGameTestServer']
                    group.tasks = tasks
                    gameTestGroups.add(group)
                    processedVersions.add(version)
                }

                def passed = java.util.Collections.synchronizedList([])
                def failed = java.util.Collections.synchronizedList([])

                def gradlew = new File(project.projectDir, 'gradlew').absolutePath
                def logDir = new File(project.projectDir, 'build')
                logDir.mkdirs()

                def maxParallel = 2
                def executor = java.util.concurrent.Executors.newFixedThreadPool(maxParallel)
                def futures = []

                gameTestGroups.each { group ->
                    def groupLabel = group.versions.join('+')

                    project.logger.lifecycle(">>> Starting GameTests: versions ${groupLabel}...")

                    def future = executor.submit({
                        // Run each version in the group sequentially
                        group.versions.each { version ->
                            def moduleVersion = hotfixVersions.getOrDefault(version, version)

                            def cmd = [gradlew]
                            cmd.addAll(group.tasks)
                            cmd.addAll([
                                '-Ptarget_mc_version=' + version,
                                '--project-cache-dir', '.gradle/gametest-' + version,
                                '--parallel',
                                '--stacktrace'
                            ])

                            def logFile = new File(logDir, 'gametest-' + version + '.log')

                            // Clean stale world directories to avoid session.lock conflicts
                            // Use moduleVersion for directory path (hotfix versions use base version dir)
                            ['fabric', 'neoforge'].each { loader ->
                                def worldDir = new File(project.projectDir, loader + '/' + moduleVersion + '/run/world')
                                if (worldDir.exists()) {
                                    worldDir.deleteDir()
                                }
                            }

                            try {
                                def pb = new ProcessBuilder(cmd)
                                pb.directory(project.projectDir)
                                pb.redirectErrorStream(true)
                                pb.redirectOutput(logFile)

                                def process = pb.start()
                                def exitCode = process.waitFor()

                                if (exitCode == 0) {
                                    // Extract loader names for reporting
                                    group.tasks.each { task ->
                                        def loader = task.split(':')[1]
                                        passed.add(loader + ' ' + version)
                                    }
                                } else {
                                    group.tasks.each { task ->
                                        def loader = task.split(':')[1]
                                        failed.add(loader + ' ' + version + ' (see ' + logFile.absolutePath + ')')
                                    }
                                }
                            } catch (Exception e) {
                                failed.add(version + ' (' + e.message + ')')
                            }
                        }
                    } as Runnable)
                    futures.add(future)
                }

                // Wait for all GameTests to complete
                futures.each { it.get() }
                executor.shutdown()

                project.logger.lifecycle('')
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('GameTest Summary:')
                project.logger.lifecycle('====================================================')

                passed.sort().each { label ->
                    project.logger.lifecycle("   PASSED: ${label}")
                }
                failed.sort().each { entry ->
                    project.logger.lifecycle("   FAILED: ${entry}")
                }

                project.logger.lifecycle('')
                project.logger.lifecycle("Total: ${passed.size()} passed, ${failed.size()} failed")
                project.logger.lifecycle("Logs: ${logDir.absolutePath}/gametest-*.log")
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('')

                if (!failed.isEmpty()) {
                    throw new GradleException("GameTests failed for: ${failed.collect { it.split(' \\(')[0] }.join(', ')}")
                }
            }
        }

        // ============================================================
        // Verification Tasks
        // ============================================================

        /**
         * Run all verification tasks: cleanAll, validateResources, validateTranslations, buildAll, testAll, gameTestAll
         * Usage: ./gradlew checkAll
         *
         * This is the comprehensive verification task that runs all checks in sequence.
         * If any step fails, subsequent steps are skipped and the task fails.
         */
        project.tasks.register('checkAll') {
            group = taskGroupVerification
            description = 'Run all verification tasks: cleanAll, validateResources, validateTranslations, buildAll, testAll, gameTestAll'

            doFirst {
                project.logger.lifecycle('')
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('Running ALL verification tasks...')
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('')
            }

            doLast {
                def steps = [
                    [name: 'cleanAll', task: 'cleanAll'],
                    [name: 'validateResources', task: 'validateResources'],
                    [name: 'validateTranslations', task: 'validateTranslations'],
                    [name: 'buildAll', task: 'buildAll'],
                    [name: 'testAll', task: 'testAll'],
                    [name: 'gameTestAll', task: 'gameTestAll'],
                ]

                def passed = []
                def failed = null

                steps.each { step ->
                    if (failed != null) {
                        project.logger.lifecycle(">>> Skipping ${step.name} (previous step failed)")
                        return
                    }

                    project.logger.lifecycle('')
                    project.logger.lifecycle(">>> Running ${step.name}...")
                    project.logger.lifecycle('')

                    try {
                        project.exec {
                            commandLine './gradlew', step.task
                            errorOutput = System.err
                        }
                        passed.add(step.name)
                        project.logger.lifecycle("SUCCESS: ${step.name}")
                    } catch (Exception e) {
                        failed = step.name
                        project.logger.error("FAILED: ${step.name}")
                    }
                }

                project.logger.lifecycle('')
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('Verification Summary:')
                project.logger.lifecycle('====================================================')

                passed.each { name ->
                    project.logger.lifecycle("   PASSED: ${name}")
                }
                if (failed != null) {
                    project.logger.lifecycle("   FAILED: ${failed}")
                    def skipped = steps.findAll { s -> !passed.contains(s.name) && s.name != failed }
                    skipped.each { s ->
                        project.logger.lifecycle("   SKIPPED: ${s.name}")
                    }
                }

                project.logger.lifecycle('')
                project.logger.lifecycle("Total: ${passed.size()} passed, ${failed != null ? 1 : 0} failed, ${steps.size() - passed.size() - (failed != null ? 1 : 0)} skipped")
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('')

                if (failed != null) {
                    throw new GradleException("Verification failed at: ${failed}")
                }

                project.logger.lifecycle('All verification tasks passed!')
            }
        }

        // ============================================================
        // Release Tasks
        // ============================================================

        /**
         * Collect release JARs from all versions into build/release/
         * Usage: ./gradlew collectJars
         *
         * Looks for built JARs in each platform-version build/libs/ directory
         * and copies them to a centralized build/release/ directory.
         */
        project.tasks.register('collectJars') {
            group = taskGroupName
            description = 'Collect release JARs from all versions into build/release/'

            doLast {
                def releaseDir = project.file("${project.rootDir}/build/release")
                if (releaseDir.exists()) {
                    releaseDir.deleteDir()
                }
                releaseDir.mkdirs()

                def collected = []
                def platforms = ['fabric', 'neoforge']

                def archivesName = ext.archivesName
                def modVersion = ext.modVersion

                releaseVersions.each { version ->
                    platforms.each { platform ->
                        def libsDir = project.file("${project.rootDir}/${platform}/${version}/build/libs")
                        if (!libsDir.exists()) {
                            project.logger.warn("SKIP: ${libsDir} does not exist (not built yet?)")
                            return
                        }

                        def jarName = "${archivesName}-${modVersion}+${version}-${platform}.jar"
                        def jarFile = new File(libsDir, jarName)

                        if (jarFile.exists()) {
                            project.ant.copy(file: jarFile, todir: releaseDir)
                            collected.add(jarName)
                            project.logger.lifecycle("Collected: ${jarName}")
                        } else {
                            project.logger.warn("NOT FOUND: ${jarFile}")
                        }
                    }
                }

                project.logger.lifecycle('')
                if (collected.isEmpty()) {
                    project.logger.warn('No release JARs found. Run buildAll first.')
                } else {
                    project.logger.lifecycle("${collected.size()} JAR(s) collected into build/release/")
                }
            }
        }

        /**
         * Full release: cleanAll, buildAll, collectJars
         * Usage: ./gradlew release
         *
         * Runs the full release pipeline in sequence:
         *   1. cleanAll - Clean all build outputs
         *   2. buildAll - Build for all Minecraft versions
         *   3. collectJars - Collect JARs into build/release/
         */
        project.tasks.register('release') {
            group = taskGroupName
            description = 'Full release: cleanAll, buildAll, collectJars'

            doLast {
                def steps = ['cleanAll', 'buildAll', 'collectJars']

                steps.each { step ->
                    project.logger.lifecycle('')
                    project.logger.lifecycle(">>> Running ${step}...")
                    project.logger.lifecycle('')

                    project.exec {
                        commandLine './gradlew', step
                        errorOutput = System.err
                    }
                }

                project.logger.lifecycle('')
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('Release build completed!')
                project.logger.lifecycle('JARs collected in: build/release/')
                project.logger.lifecycle('====================================================')
                project.logger.lifecycle('')
            }
        }
    }
}
