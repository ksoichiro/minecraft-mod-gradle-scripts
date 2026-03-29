package com.github.ksoichiro.mcmod

import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec

class ProdRunTasks {

    static void register(Project project, McmodExtension ext) {
        project.subprojects { sub ->
            sub.afterEvaluate {
                if (sub.name in ['fabric', 'neoforge']) {
                    registerForSubproject(sub, ext)
                }
            }
        }
    }

    private static void registerForSubproject(Project project, McmodExtension ext) {
        def prodBaseDir = ext.prodRun.baseDir
        def prodCacheDir = "${prodBaseDir}/cache"
        def mcVersion = project.ext.minecraft_version
        def taskGroupName = ext.projectDisplayName.toLowerCase() + ' prod'

        // ============================================================
        // Task: downloadMinecraft
        // ============================================================
        project.tasks.register('downloadMinecraft') {
            group = taskGroupName
            description = "Download vanilla Minecraft client, libraries, and assets for ${mcVersion}"

            doLast {
                def versionJson = getVersionJson(prodCacheDir, mcVersion, project)

                // 1. Client JAR
                project.logger.lifecycle("=== Client JAR ===")
                def clientJar = project.file("${prodCacheDir}/client/${mcVersion}.jar")
                downloadFile(versionJson.downloads.client.url, clientJar, project)
                if (clientJar.exists()) {
                    project.logger.lifecycle("  OK: ${clientJar.name}")
                }

                // 2. Libraries (filtered by OS rules)
                project.logger.lifecycle("=== Libraries ===")
                def librariesDir = project.file("${prodCacheDir}/libraries")
                def libCount = 0
                versionJson.libraries.each { lib ->
                    if (!isLibraryAllowed(lib)) return

                    if (lib.downloads?.artifact) {
                        def dest = project.file("${librariesDir}/${lib.downloads.artifact.path}")
                        downloadFile(lib.downloads.artifact.url, dest, project)
                        libCount++
                    }

                    // Native classifier JARs (for MC 1.18.2 and earlier)
                    if (lib.natives) {
                        def currentOs = getMojangOs()
                        def nativeClassifier = lib.natives[currentOs]
                        if (nativeClassifier) {
                            // Replace ${arch} placeholder used in some Windows native classifiers
                            nativeClassifier = nativeClassifier.replace('${arch}',
                                System.getProperty('os.arch') == 'aarch64' ? 'arm64' : '64')
                            def nativeArtifact = lib.downloads?.classifiers?.get(nativeClassifier)
                            if (nativeArtifact) {
                                def dest = project.file("${librariesDir}/${nativeArtifact.path}")
                                downloadFile(nativeArtifact.url, dest, project)
                            }
                        }
                    }
                }
                project.logger.lifecycle("  ${libCount} libraries ready")

                // 3. Assets (index + objects, shared across MC versions with same asset index)
                project.logger.lifecycle("=== Assets ===")
                def assetsDir = project.file("${prodCacheDir}/assets")
                def assetIndex = versionJson.assetIndex
                def indexFile = project.file("${assetsDir}/indexes/${assetIndex.id}.json")
                downloadFile(assetIndex.url, indexFile, project)

                def objects = new JsonSlurper().parse(indexFile).objects
                def total = objects.size()
                def downloaded = 0
                def skipped = 0
                objects.each { name, obj ->
                    def hash = obj.hash
                    def prefix = hash.substring(0, 2)
                    def dest = project.file("${assetsDir}/objects/${prefix}/${hash}")
                    if (dest.exists()) {
                        skipped++
                    } else {
                        downloadFile("https://resources.download.minecraft.net/${prefix}/${hash}", dest, project)
                        downloaded++
                    }
                    if ((downloaded + skipped) % 500 == 0) {
                        project.logger.lifecycle("  Assets: ${downloaded + skipped}/${total}")
                    }
                }
                project.logger.lifecycle("  Assets complete: ${total} total (${downloaded} downloaded, ${skipped} cached)")
            }
        }

        // ============================================================
        // Fabric-specific tasks
        // ============================================================
        if (project.name == 'fabric') {
            project.configurations {
                prodMods {
                    canBeResolved = true
                    canBeConsumed = false
                    transitive = false
                }
            }

            project.dependencies {
                prodMods "net.fabricmc.fabric-api:fabric-api:${project.ext.fabric_api_version}"
                prodMods "dev.architectury:architectury-fabric:${project.ext.architectury_api_version}"
            }

            // --- Task: downloadFabricLoader ---
            project.tasks.register('downloadFabricLoader') {
                group = taskGroupName
                description = "Download Fabric Loader and dependencies for ${mcVersion}"

                doLast {
                    project.logger.lifecycle("=== Fabric Loader ===")
                    def profile = getFabricProfile(prodCacheDir, mcVersion, project.ext.fabric_loader_version, project)
                    def fabricLibsDir = project.file("${prodCacheDir}/fabric-loader-libs")

                    profile.libraries.each { lib ->
                        def path = mavenCoordToPath(lib.name)
                        def baseUrl = lib.url ?: 'https://libraries.minecraft.net/'
                        // Ensure base URL ends with /
                        if (!baseUrl.endsWith('/')) baseUrl += '/'
                        def dest = project.file("${fabricLibsDir}/${path}")
                        downloadFile("${baseUrl}${path}", dest, project)
                    }
                    project.logger.lifecycle("  ${profile.libraries.size()} Fabric libraries ready")
                }
            }

            // --- Task: setupProdMods ---
            project.tasks.register('setupProdMods') {
                group = taskGroupName
                description = "Copy built mod JAR and dependencies to mods/ for Fabric ${mcVersion}"
                dependsOn 'remapJar'

                doLast {
                    def modsDir = project.file("${prodBaseDir}/instances/fabric-${mcVersion}/mods")
                    if (modsDir.exists()) modsDir.deleteDir()
                    modsDir.mkdirs()

                    // Built mod JAR
                    def modJar = project.tasks.named('remapJar').get().archiveFile.get().asFile
                    project.ant.copy(file: modJar, todir: modsDir)
                    project.logger.lifecycle("  Mod: ${modJar.name}")

                    // Dependency mods (Fabric API, Architectury API)
                    project.configurations.prodMods.files.each { f ->
                        project.ant.copy(file: f, todir: modsDir)
                        project.logger.lifecycle("  Dep: ${f.name}")
                    }
                }
            }

            // --- Task: runProd ---
            project.tasks.register('runProd', JavaExec) {
                group = taskGroupName
                description = "Launch Minecraft in production-like environment (Fabric ${mcVersion})"
                dependsOn 'downloadMinecraft', 'downloadFabricLoader', 'setupProdMods'

                doFirst {
                    def versionJson = getVersionJson(prodCacheDir, mcVersion, project)
                    def profile = getFabricProfile(prodCacheDir, mcVersion, project.ext.fabric_loader_version, project)
                    def instanceDir = project.file("${prodBaseDir}/instances/fabric-${mcVersion}")
                    instanceDir.mkdirs()

                    // Build classpath
                    def cp = []

                    // Fabric Loader and its dependencies (ASM, Mixin, intermediary)
                    def fabricLibsDir = project.file("${prodCacheDir}/fabric-loader-libs")
                    // Collect Fabric Loader artifact IDs (group:artifact) to deduplicate against vanilla
                    def fabricArtifactIds = [] as Set
                    profile.libraries.each { lib ->
                        def path = mavenCoordToPath(lib.name)
                        def f = project.file("${fabricLibsDir}/${path}")
                        if (f.exists()) cp.add(f)
                        // Extract group:artifact from Maven coordinate (group:artifact:version[:classifier])
                        def parts = lib.name.split(':')
                        if (parts.length >= 2) {
                            fabricArtifactIds.add("${parts[0]}:${parts[1]}")
                        }
                    }

                    // Vanilla client JAR
                    cp.add(project.file("${prodCacheDir}/client/${mcVersion}.jar"))

                    // Vanilla MC libraries (filtered by OS rules, excluding those already provided by Fabric Loader)
                    def vanillaLibs = getLibraryFiles(versionJson, prodCacheDir, project)
                    vanillaLibs.each { File f ->
                        // Check if this vanilla library's artifact is already provided by Fabric Loader
                        // by matching the directory structure (group/artifact/version/artifact-version.jar)
                        def pathParts = f.absolutePath.replace('\\', '/').split('/')
                        def isDuplicate = false
                        // Library path ends with: .../group/artifact/version/artifact-version.jar
                        // Walk up from filename to find group:artifact
                        if (pathParts.length >= 4) {
                            def artifact = pathParts[pathParts.length - 3]
                            def groupParts = []
                            // Find the 'libraries' marker to reconstruct group path
                            def libIdx = -1
                            for (int i = pathParts.length - 4; i >= 0; i--) {
                                if (pathParts[i] == 'libraries') {
                                    libIdx = i
                                    break
                                }
                            }
                            if (libIdx >= 0) {
                                def group = pathParts[(libIdx + 1)..(pathParts.length - 4)].join('.')
                                isDuplicate = fabricArtifactIds.contains("${group}:${artifact}")
                            }
                        }
                        if (!isDuplicate) cp.add(f)
                    }

                    cp = patchLwjglForArm64(cp, prodCacheDir, project)
                    classpath = project.files(cp)
                    mainClass.set(profile.mainClass)

                    // Game arguments
                    args '--gameDir', instanceDir.absolutePath
                    args '--assetsDir', project.file("${prodCacheDir}/assets").absolutePath
                    args '--assetIndex', versionJson.assetIndex.id
                    args '--version', mcVersion
                    args '--username', 'Dev'
                    args '--accessToken', '0'

                    // JVM arguments
                    jvmArgs '-Xmx2G', '-Xms512M'

                    // macOS requires -XstartOnFirstThread for LWJGL/OpenGL
                    if (getMojangOs() == 'osx') {
                        jvmArgs '-XstartOnFirstThread'
                    }

                    // Extract native libraries for old MC versions (pre-1.19)
                    def nativesDir = new File(instanceDir, "natives")
                    nativesDir.mkdirs()
                    extractNatives(versionJson, nativesDir, prodCacheDir, project)
                    jvmArgs "-Djava.library.path=${nativesDir.absolutePath}"

                    workingDir = instanceDir

                    project.logger.lifecycle("=== runProd (Fabric ${mcVersion}) ===")
                    project.logger.lifecycle("  Game dir: ${instanceDir}")
                    project.logger.lifecycle("  Classpath entries: ${cp.size()}")
                }
            }
        }

        // ============================================================
        // NeoForge-specific tasks
        // ============================================================
        if (project.name == 'neoforge') {
            project.configurations {
                prodMods {
                    canBeResolved = true
                    canBeConsumed = false
                    transitive = false
                }
            }

            project.dependencies {
                prodMods "dev.architectury:architectury-neoforge:${project.ext.architectury_api_version}"
            }

            def neoforgeVersion = project.ext.neoforge_version
            def neoforgeInstallDir = project.file("${prodCacheDir}/neoforge-install-${mcVersion}")
            def neoforgeVersionId = "neoforge-${neoforgeVersion}"

            // --- Task: installNeoForge ---
            project.tasks.register('installNeoForge') {
                group = taskGroupName
                description = "Install NeoForge ${neoforgeVersion} for ${mcVersion}"
                dependsOn 'downloadMinecraft'

                doLast {
                    def versionJsonFile = new File(neoforgeInstallDir,
                        "versions/${neoforgeVersionId}/${neoforgeVersionId}.json")
                    if (versionJsonFile.exists()) {
                        project.logger.lifecycle("  NeoForge already installed: ${neoforgeVersionId}")
                        return
                    }

                    project.logger.lifecycle("=== Installing NeoForge ${neoforgeVersion} ===")
                    neoforgeInstallDir.mkdirs()

                    // Download installer
                    def installerJar = project.file("${prodCacheDir}/installers/neoforge-${neoforgeVersion}-installer.jar")
                    downloadFile(
                        "https://maven.neoforged.net/releases/net/neoforged/neoforge/${neoforgeVersion}/neoforge-${neoforgeVersion}-installer.jar",
                        installerJar,
                        project
                    )

                    // Place vanilla files for the installer
                    def vanillaVersionDir = new File(neoforgeInstallDir, "versions/${mcVersion}")
                    vanillaVersionDir.mkdirs()
                    project.ant.copy(file: project.file("${prodCacheDir}/versions/${mcVersion}.json"),
                              tofile: new File(vanillaVersionDir, "${mcVersion}.json"),
                              overwrite: false)
                    project.ant.copy(file: project.file("${prodCacheDir}/client/${mcVersion}.jar"),
                              tofile: new File(vanillaVersionDir, "${mcVersion}.jar"),
                              overwrite: false)

                    // Copy vanilla libraries so the installer can find them
                    def vanillaLibsDir = project.file("${prodCacheDir}/libraries")
                    if (vanillaLibsDir.exists()) {
                        project.ant.copy(todir: new File(neoforgeInstallDir, "libraries")) {
                            fileset(dir: vanillaLibsDir)
                        }
                    }

                    // Create dummy launcher profile required by the installer
                    def profileFile = new File(neoforgeInstallDir, "launcher_profiles.json")
                    if (!profileFile.exists()) {
                        profileFile.text = '{"profiles":{}}'
                    }

                    // Run installer
                    project.logger.lifecycle("  Running NeoForge installer...")
                    def launcher = project.javaToolchains.launcherFor {
                        languageVersion = project.ext.java_version
                    }.get()
                    project.exec {
                        commandLine launcher.executablePath.asFile.absolutePath,
                            '-jar', installerJar.absolutePath,
                            '--installClient', neoforgeInstallDir.absolutePath
                    }

                    if (!versionJsonFile.exists()) {
                        throw new org.gradle.api.GradleException("NeoForge installer did not create expected version JSON: ${versionJsonFile}")
                    }
                    project.logger.lifecycle("  NeoForge installed successfully")
                }
            }

            // --- Task: setupProdMods ---
            project.tasks.register('setupProdMods') {
                group = taskGroupName
                description = "Copy built mod JAR and dependencies to mods/ for NeoForge ${mcVersion}"
                dependsOn 'remapJar'

                doLast {
                    def modsDir = project.file("${prodBaseDir}/instances/neoforge-${mcVersion}/mods")
                    if (modsDir.exists()) modsDir.deleteDir()
                    modsDir.mkdirs()

                    // Built mod JAR
                    def modJar = project.tasks.named('remapJar').get().archiveFile.get().asFile
                    project.ant.copy(file: modJar, todir: modsDir)
                    project.logger.lifecycle("  Mod: ${modJar.name}")

                    // Dependency mods (Architectury API)
                    project.configurations.prodMods.files.each { f ->
                        project.ant.copy(file: f, todir: modsDir)
                        project.logger.lifecycle("  Dep: ${f.name}")
                    }
                }
            }

            // --- Task: runProd ---
            project.tasks.register('runProd', JavaExec) {
                group = taskGroupName
                description = "Launch Minecraft in production-like environment (NeoForge ${mcVersion})"
                dependsOn 'downloadMinecraft', 'installNeoForge', 'setupProdMods'

                doFirst {
                    def vanillaJson = getVersionJson(prodCacheDir, mcVersion, project)
                    def loaderJsonFile = new File(neoforgeInstallDir,
                        "versions/${neoforgeVersionId}/${neoforgeVersionId}.json")
                    def loaderJson = new JsonSlurper().parse(loaderJsonFile)
                    def mergedJson = mergeVersionJsons(loaderJson, vanillaJson)

                    def instanceDir = project.file("${prodBaseDir}/instances/neoforge-${mcVersion}")
                    instanceDir.mkdirs()
                    def nativesDir = new File(instanceDir, "natives")
                    nativesDir.mkdirs()
                    extractNatives(vanillaJson, nativesDir, prodCacheDir, project)
                    def librariesDir = new File(neoforgeInstallDir, "libraries")

                    // Build classpath from installer libraries only.
                    // Do NOT add vanilla client JAR — NeoForge uses its own processed client
                    // module via the -p (module path) JVM arguments from the version JSON.
                    def cp = getInstallerLibraryFiles(mergedJson, neoforgeInstallDir)
                    cp = patchLwjglForArm64(cp, prodCacheDir, project)
                    classpath = project.files(cp)

                    mainClass.set(mergedJson.mainClass)

                    // Placeholder substitutions for version JSON arguments
                    def placeholders = [
                        '${library_directory}'  : librariesDir.absolutePath,
                        '${classpath_separator}': File.pathSeparator,
                        '${version_name}'       : neoforgeVersionId,
                        '${natives_directory}'  : nativesDir.absolutePath,
                        '${launcher_name}'      : 'GradleProdRun',
                        '${launcher_version}'   : '1.0',
                        '${auth_player_name}'   : 'Dev',
                        '${version_type}'       : 'release',
                        '${game_directory}'     : instanceDir.absolutePath,
                        '${assets_root}'        : project.file("${prodCacheDir}/assets").absolutePath,
                        '${assets_index_name}'  : vanillaJson.assetIndex.id,
                        '${auth_uuid}'          : '00000000-0000-0000-0000-000000000000',
                        '${auth_access_token}'  : '0',
                        '${clientid}'           : '0',
                        '${auth_xuid}'          : '0',
                        '${user_type}'          : 'legacy',
                        '${user_properties}'    : '{}',
                    ]

                    // JVM arguments from version JSON + memory settings
                    jvmArgs '-Xmx2G', '-Xms512M'
                    jvmArgs processArguments(mergedJson.arguments.jvm, placeholders)

                    // Game arguments from version JSON
                    args processArguments(mergedJson.arguments.game, placeholders)

                    workingDir = instanceDir

                    project.logger.lifecycle("=== runProd (NeoForge ${mcVersion}) ===")
                    project.logger.lifecycle("  Game dir: ${instanceDir}")
                    project.logger.lifecycle("  Main class: ${mergedJson.mainClass}")
                    project.logger.lifecycle("  Classpath entries: ${cp.size()}")
                }
            }
        }
    }

    // --- Helper: Download file with caching ---
    // Skips download if file already exists. Delete run-prod/cache/ to force re-download.
    private static void downloadFile(String url, File dest, Project project) {
        if (dest.exists()) return
        dest.parentFile.mkdirs()
        project.logger.lifecycle("  Downloading: ${dest.name}")
        project.ant.get(src: url, dest: dest, verbose: false)
    }

    // --- Helper: Get current OS name for Mojang manifest rules ---
    private static String getMojangOs() {
        def osName = System.getProperty('os.name').toLowerCase()
        if (osName.contains('mac')) return 'osx'
        if (osName.contains('win')) return 'windows'
        return 'linux'
    }

    // --- Helper: Check if a library passes Mojang OS rules ---
    private static boolean isLibraryAllowed(Object lib) {
        if (!lib.rules) return true
        def allowed = false
        def currentOs = getMojangOs()
        lib.rules.each { rule ->
            def osMatch = true
            if (rule.os) {
                osMatch = (rule.os.name == currentOs)
                if (osMatch && rule.os.arch) {
                    osMatch = (rule.os.arch == System.getProperty('os.arch'))
                }
            }
            if (osMatch) {
                allowed = (rule.action == 'allow')
            }
        }
        return allowed
    }

    // --- Helper: Parse and cache Mojang version JSON ---
    private static Object getVersionJson(String prodCacheDir, String version, Project project) {
        def manifestFile = project.file("${prodCacheDir}/manifest/version_manifest_v2.json")
        downloadFile('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json', manifestFile, project)

        def manifest = new JsonSlurper().parse(manifestFile)
        def versionEntry = manifest.versions.find { it.id == version }
        if (!versionEntry) {
            throw new org.gradle.api.GradleException("Minecraft version ${version} not found in Mojang manifest. " +
                "Delete ${manifestFile} to refresh.")
        }

        def versionFile = project.file("${prodCacheDir}/versions/${version}.json")
        downloadFile(versionEntry.url, versionFile, project)
        return new JsonSlurper().parse(versionFile)
    }

    // --- Helper: Collect library files for the current platform ---
    private static List<File> getLibraryFiles(Object versionJson, String prodCacheDir, Project project) {
        def librariesDir = project.file("${prodCacheDir}/libraries")
        def files = []
        versionJson.libraries.each { lib ->
            if (!isLibraryAllowed(lib)) return
            if (lib.downloads?.artifact) {
                def f = project.file("${librariesDir}/${lib.downloads.artifact.path}")
                if (f.exists()) files.add(f)
            }
        }
        return files
    }

    // --- Helper: Convert Maven coordinate to relative path ---
    // Supports classifier (group:artifact:version:classifier) and @ext notation.
    // e.g. "org.ow2.asm:asm:9.9" -> "org/ow2/asm/asm/9.9/asm-9.9.jar"
    // e.g. "net.neoforged:mergetool:2.0.0:api" -> "net/neoforged/mergetool/2.0.0/mergetool-2.0.0-api.jar"
    // e.g. "com.example:lib:1.0@zip" -> "com/example/lib/1.0/lib-1.0.zip"
    private static String mavenCoordToPath(String coord) {
        def extension = 'jar'
        def coordBody = coord
        if (coord.contains('@')) {
            extension = coord.substring(coord.indexOf('@') + 1)
            coordBody = coord.substring(0, coord.indexOf('@'))
        }
        def parts = coordBody.split(':')
        def group = parts[0]
        def artifact = parts[1]
        def version = parts[2]
        def classifier = parts.length > 3 ? parts[3] : null
        def filename = classifier
            ? "${artifact}-${version}-${classifier}.${extension}"
            : "${artifact}-${version}.${extension}"
        return "${group.replace('.', '/')}/${artifact}/${version}/${filename}"
    }

    // --- Helper: Fetch and cache Fabric Loader profile from Meta API ---
    private static Object getFabricProfile(String prodCacheDir, String gameVersion, String loaderVersion, Project project) {
        def profileFile = project.file("${prodCacheDir}/fabric-profile/${gameVersion}-${loaderVersion}.json")
        downloadFile(
            "https://meta.fabricmc.net/v2/versions/loader/${gameVersion}/${loaderVersion}/profile/json",
            profileFile,
            project
        )
        return new JsonSlurper().parse(profileFile)
    }

    // --- Helper: Merge loader version JSON with vanilla JSON (inheritsFrom processing) ---
    private static Map mergeVersionJsons(Object loaderJson, Object vanillaJson) {
        def merged = [:]
        merged.mainClass = loaderJson.mainClass
        merged.libraries = []
        if (loaderJson.libraries) merged.libraries.addAll(loaderJson.libraries)
        if (vanillaJson.libraries) merged.libraries.addAll(vanillaJson.libraries)
        merged.arguments = [jvm: [], game: []]
        if (loaderJson.arguments?.jvm) merged.arguments.jvm.addAll(loaderJson.arguments.jvm)
        if (vanillaJson.arguments?.jvm) merged.arguments.jvm.addAll(vanillaJson.arguments.jvm)
        if (loaderJson.arguments?.game) merged.arguments.game.addAll(loaderJson.arguments.game)
        if (vanillaJson.arguments?.game) merged.arguments.game.addAll(vanillaJson.arguments.game)
        return merged
    }

    // --- Helper: Evaluate argument rules from version JSON ---
    // Returns true if the argument should be included. Feature-gated args are excluded.
    private static boolean evalArgumentRules(Object rules) {
        if (!rules) return true
        def currentOs = getMojangOs()
        def allowed = false
        for (rule in rules) {
            // Skip feature-gated args (e.g., demo mode, custom resolution)
            if (rule.features) return false
            def osMatch = true
            if (rule.os) {
                osMatch = (rule.os.name == currentOs)
                if (osMatch && rule.os.arch) {
                    osMatch = System.getProperty('os.arch').contains(rule.os.arch)
                }
            }
            if (osMatch) {
                allowed = (rule.action == 'allow')
            }
        }
        return allowed
    }

    // --- Helper: Substitute placeholder strings ---
    private static String substitutePlaceholders(String str, Map placeholders) {
        def result = str
        placeholders.each { key, value ->
            result = result.replace(key, value)
        }
        return result
    }

    // --- Helper: Extract native libraries from classifier JARs ---
    // For MC versions using the old natives system (pre-1.19), extracts platform-specific
    // native libraries (.dylib, .dll, .so) from classifier JARs to the specified directory.
    // On macOS ARM64, LWJGL < 3.3.1 doesn't ship ARM64 natives, so this helper
    // downloads LWJGL 3.3.1 ARM64 natives from Maven Central as a replacement.
    private static void extractNatives(Object versionJson, File nativesDir, String prodCacheDir, Project project) {
        def librariesDir = project.file("${prodCacheDir}/libraries")
        def currentOs = getMojangOs()
        def isArm64Mac = (currentOs == 'osx' && System.getProperty('os.arch') == 'aarch64')
        def lwjglArm64Modules = [] as Set

        versionJson.libraries.each { lib ->
            if (!isLibraryAllowed(lib)) return
            if (!lib.natives) return

            def nativeClassifier = lib.natives[currentOs]
            if (!nativeClassifier) return

            nativeClassifier = nativeClassifier.replace('${arch}',
                System.getProperty('os.arch') == 'aarch64' ? 'arm64' : '64')

            def nativeArtifact = lib.downloads?.classifiers?.get(nativeClassifier)
            if (!nativeArtifact) return

            // On ARM64 Mac, collect old LWJGL modules for later ARM64 native patching
            if (isArm64Mac && lib.name?.startsWith('org.lwjgl')) {
                def parts = lib.name.split(':')
                if (parts.length >= 3) {
                    def version = parts[2]
                    if (version.startsWith('3.2.') || version == '3.3.0') {
                        lwjglArm64Modules.add(parts[1])
                        return // Skip extracting x86_64 natives; ARM64 patch below will handle it
                    }
                }
            }

            def nativeJar = new File(librariesDir, nativeArtifact.path)
            if (!nativeJar.exists()) return

            def excludePatterns = lib.extract?.exclude ?: []

            project.ant.unzip(src: nativeJar, dest: nativesDir) {
                patternset {
                    excludePatterns.each { p ->
                        exclude(name: "${p}**")
                    }
                }
            }
        }

        // Download and extract LWJGL 3.3.1 ARM64 natives for old MC versions on Apple Silicon
        if (!lwjglArm64Modules.isEmpty()) {
            def lwjglArm64Version = '3.3.1'
            def arm64CacheDir = project.file("${prodCacheDir}/lwjgl-arm64")

            lwjglArm64Modules.each { module ->
                def path = "org/lwjgl/${module}/${lwjglArm64Version}/${module}-${lwjglArm64Version}-natives-macos-arm64.jar"
                def dest = new File(arm64CacheDir, path)
                downloadFile("https://repo1.maven.org/maven2/${path}", dest, project)

                if (dest.exists()) {
                    // Extract to temp dir, then flatten .dylib files to natives root.
                    // LWJGL 3.3.1 JARs use nested paths (macos/arm64/org/lwjgl/...)
                    // but LWJGL 3.2.x expects flat .dylib files in java.library.path.
                    def tempDir = new File(nativesDir, '.arm64-extract-tmp')
                    project.ant.unzip(src: dest, dest: tempDir) {
                        patternset {
                            exclude(name: 'META-INF/**')
                        }
                    }
                    tempDir.eachFileRecurse(groovy.io.FileType.FILES) { f ->
                        if (f.name.endsWith('.dylib')) {
                            project.ant.copy(file: f, todir: nativesDir, overwrite: true)
                        }
                    }
                    project.ant.delete(dir: tempDir)
                }
            }
            project.logger.lifecycle("  Patched ${lwjglArm64Modules.size()} LWJGL modules with ARM64 natives")
        }
    }

    // --- Helper: Replace LWJGL < 3.3.1 JARs with 3.3.1 ARM64 versions on the classpath ---
    // On macOS ARM64, old LWJGL versions lack ARM64 support. This helper replaces both
    // the API JARs and adds native JARs on the classpath so versions match.
    private static List<File> patchLwjglForArm64(List<File> classpathFiles, String prodCacheDir, Project project) {
        if (getMojangOs() != 'osx' || System.getProperty('os.arch') != 'aarch64') {
            return classpathFiles
        }

        // Detect old LWJGL API JARs (e.g., lwjgl-3.2.1.jar, lwjgl-glfw-3.2.1.jar)
        def oldLwjglPattern = ~/^(lwjgl(?:-[a-z]+)*)-3\.(?:2\.\d+|3\.0)\.jar$/
        def modulesToReplace = [] as Set
        classpathFiles.each { f ->
            def m = oldLwjglPattern.matcher(f.name)
            if (m.matches()) {
                modulesToReplace.add(m.group(1))
            }
        }

        if (modulesToReplace.isEmpty()) return classpathFiles

        def lwjglVersion = '3.3.1'
        def arm64CacheDir = project.file("${prodCacheDir}/lwjgl-arm64")

        // Filter out all old LWJGL JARs (API + native classifier JARs)
        def filtered = classpathFiles.findAll { f ->
            !(f.name.startsWith('lwjgl') && (f.name.contains('-3.2.') || f.name.contains('-3.3.0')))
        }

        // Add LWJGL 3.3.1 API JARs as replacements
        modulesToReplace.each { module ->
            def apiPath = "org/lwjgl/${module}/${lwjglVersion}/${module}-${lwjglVersion}.jar"
            def apiDest = new File(arm64CacheDir, apiPath)
            downloadFile("https://repo1.maven.org/maven2/${apiPath}", apiDest, project)
            if (apiDest.exists()) filtered.add(apiDest)
        }

        project.logger.lifecycle("  Replaced ${modulesToReplace.size()} LWJGL modules with ${lwjglVersion} for ARM64: ${modulesToReplace}")
        return filtered
    }

    // --- Helper: Process arguments list from version JSON ---
    // Evaluates rules, substitutes placeholders, skips -cp/${classpath} (handled by JavaExec).
    private static List<String> processArguments(List argsList, Map placeholders) {
        def result = []
        def skipNext = false
        for (int i = 0; i < argsList.size(); i++) {
            if (skipNext) { skipNext = false; continue }
            def arg = argsList[i]
            if (arg instanceof Map) {
                if (!evalArgumentRules(arg.rules)) continue
                def values = arg.value instanceof List ? arg.value : [arg.value]
                values.each { v ->
                    def s = substitutePlaceholders(v.toString(), placeholders)
                    if (s != '${classpath}') result.add(s)
                }
            } else {
                def s = arg.toString()
                if (s == '-cp' || s == '--classpath') {
                    skipNext = true
                    continue
                }
                if (s == '${classpath}') continue
                result.add(substitutePlaceholders(s, placeholders))
            }
        }
        return result
    }

    // --- Helper: Collect library files from merged version JSON using install directory ---
    private static List<File> getInstallerLibraryFiles(Object mergedJson, File installDir) {
        def librariesDir = new File(installDir, 'libraries')
        def files = []
        mergedJson.libraries.each { lib ->
            if (!isLibraryAllowed(lib)) return
            File f = null
            if (lib.downloads?.artifact?.path) {
                f = new File(librariesDir, lib.downloads.artifact.path)
            } else if (lib.name) {
                def path = mavenCoordToPath(lib.name)
                f = new File(librariesDir, path)
            }
            if (f != null && f.exists()) files.add(f)
        }
        return files
    }
}
