# minecraft-mod-gradle-scripts

Reusable Gradle scripts for multi-version Minecraft mod development with Architectury.

## Files

| File | Description |
|------|-------------|
| `multi-version-tasks.gradle` | Clean, build, test, run, and release tasks for all supported MC versions |
| `resource-validation.gradle` | JSON syntax validation and asset cross-reference checks (blockstate/model/texture) |
| `prod-run.gradle` | Production-like environment runner for Fabric and NeoForge |
| `changelog-utils.gradle` | Shared changelog extraction helper (used by release scripts) |
| `release-modrinth.gradle` | Release JARs to Modrinth |
| `release-curseforge.gradle` | Release JARs to CurseForge |
| `convert_nbt_1_21_to_1_20.py` | NBT structure converter from 1.21.1 to 1.20.1 format (see [README_NBT_CONVERSION.md](README_NBT_CONVERSION.md)) |

## Integration

### Option A: Composite Build (Plugin)

#### 1. Add as a Git submodule

```bash
git submodule add https://github.com/ksoichiro/minecraft-mod-gradle-scripts.git gradle/shared
```

#### 2. Include as a Composite Build in your `settings.gradle`

```groovy
includeBuild 'gradle/shared'
```

#### 3. Apply the plugin in your root `build.gradle`

```groovy
plugins {
    id 'com.github.ksoichiro.mcmod'
}

mcmod {
    // Shared properties
    archivesName = 'mymod'
    modVersion = '1.0.0'
    supportedVersions = ['1.20.1', '1.21.1', '1.21.2', '1.21.4']
    hotfixVersions = ['1.21.3': '1.21.2']
    fabricOnlyVersions = ['1.20.1']
    projectDisplayName = 'My Mod'

    // Feature toggles (defaults shown)
    multiVersion {
        enabled = true
    }
    resourceValidation {
        enabled = true
        // sharedResourceDirs = ['common/shared/src/main/resources']
        // entityIdFile = 'common/shared/src/main/java/.../ModEntityId.java'
        // itemIdFile = 'common/shared/src/main/java/.../ModItemId.java'
        // excludedEntities = ['gear_projectile']
    }
    prodRun {
        enabled = false
        // baseDir = "${rootDir}/run-prod"
    }
    releaseModrinth {
        enabled = false
        // projectId = 'yEsoyNev'
        // depArchitecturyId = 'lhGA9TYQ'
        // depFabricApiId = 'P7dR8mSH'
    }
    releaseCurseForge {
        enabled = false
        // projectId = '1414198'
    }
}
```

Any property not set in the `mcmod` block falls back to the corresponding value in `gradle.properties` (e.g., `supported_mc_versions`, `archives_name`).

---

### Option B: Script (Legacy)

### 1. Add as a Git submodule

```bash
git submodule add https://github.com/ksoichiro/minecraft-mod-gradle-scripts.git gradle/shared
```

### 2. Apply scripts in your root `build.gradle`

```groovy
// Submodule existence check
if (!file('gradle/shared/multi-version-tasks.gradle').exists()) {
    throw new GradleException(
        "Shared Gradle scripts not found. Run: git submodule update --init"
    )
}

// Multi-version build tasks
apply from: 'gradle/shared/multi-version-tasks.gradle'

// Resource validation
apply from: 'gradle/shared/resource-validation.gradle'

// Release tasks (optional)
apply from: 'gradle/shared/release-modrinth.gradle'
apply from: 'gradle/shared/release-curseforge.gradle'
```

For platform subprojects (fabric/neoforge):

```groovy
if (project.name in ['fabric', 'neoforge']) {
    apply from: "${rootDir}/gradle/shared/prod-run.gradle"
}
```

### 3. Configure `gradle.properties`

#### Required properties

```properties
# Comma-separated list of all supported Minecraft versions
supported_mc_versions=1.20.1,1.21.1,1.21.2,1.21.3,1.21.4

# Mod archive name (used as namespace for assets)
archives_name=mymod

# Mod version (used for JAR file naming in collectJars)
mod_version=1.0.0
```

#### Optional properties

```properties
# Project display name for Gradle task groups (defaults to project.name)
release_project_name=My Mod

# Hotfix versions that share modules with a base version (format: hotfix=base)
hotfix_mc_versions=1.21.3=1.21.2

# Versions that only support Fabric (no NeoForge)
fabric_only_mc_versions=1.20.1

# Base directory for production run files (default: ${rootDir}/run-prod)
prod_base_dir=${rootDir}/run-prod

# Shared resource directories for validation (auto-detected if not set)
shared_resource_dirs=common/shared/src/main/resources,common/shared-1.21.1+/src/main/resources

# Entity/item ID files for translation validation
entity_id_file=common/shared/src/main/java/com/mymod/registry/ModEntityId.java
item_id_file=common/shared/src/main/java/com/mymod/registry/ModItemId.java

# Technical entities excluded from translation validation
excluded_entities=gear_projectile,time_arrow

# Versions to validate translations for (defaults to supported_mc_versions)
translation_versions=1.20.1,1.21.1,1.21.2,1.21.3

# Hotfix version mapping for translation validation (defaults to hotfix_mc_versions)
translation_hotfix_versions=1.21.3=1.21.2
```

#### Release properties (for release-modrinth/curseforge)

```properties
# Modrinth project ID
modrinth_project_id=yEsoyNev

# CurseForge project ID
curseforge_project_id=1414198

# Architectury API dependency is auto-detected via architectury_api_version property.
# Default Modrinth dependency IDs (override if needed):
# modrinth_dep_architectury=lhGA9TYQ
# modrinth_dep_fabric_api=P7dR8mSH
```

API tokens are read from environment variables: `MODRINTH_TOKEN`, `CURSEFORGE_TOKEN`.

## Generated Tasks

### multi-version-tasks.gradle

- `clean<VERSION>` - Clean build outputs for a specific MC version
- `cleanAll` - Clean all release versions
- `build<VERSION>` - Build for a specific MC version
- `buildAll` - Parallel build for all release versions
- `runClient<Loader><VERSION>` - Run client for a specific loader and version
- `testAll` - Run unit tests for all release versions
- `gameTestAll` - Run GameTests for all versions (with hotfix grouping)
- `checkAll` - Run full verification pipeline (clean, validate, build, test, gametest)
- `collectJars` - Collect release JARs into `build/release/`
- `release` - Full release pipeline (clean, build, collect)

### resource-validation.gradle

- `validateResources` - Check JSON syntax and blockstate/model/texture cross-references
- `validateTranslations` - Validate entity/item translation keys across versions

### prod-run.gradle

- `downloadMinecraft` - Download vanilla client, libraries, and assets
- `downloadFabricLoader` - Download Fabric Loader dependencies (Fabric only)
- `installNeoForge` - Install NeoForge via installer (NeoForge only)
- `setupProdMods` - Copy built mod and dependencies to instance
- `runProd` - Launch Minecraft in production-like environment

### release-modrinth.gradle

- `releaseModrinth` - Release all JARs in `build/release/` to Modrinth (or `-Pjar=filename.jar` for a single JAR)

### release-curseforge.gradle

- `releaseCurseForge` - Release all JARs in `build/release/` to CurseForge (or `-Pjar=filename.jar` for a single JAR)

## License

LGPL-3.0
