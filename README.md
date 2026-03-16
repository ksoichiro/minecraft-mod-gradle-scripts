# minecraft-mod-gradle-scripts

Reusable Gradle scripts for multi-version Minecraft mod development with Architectury.

## Files

| File | Description |
|------|-------------|
| `gradle/multi-version-tasks.gradle` | Clean, build, test, run, and release tasks for all supported MC versions |
| `gradle/resource-validation.gradle` | JSON syntax validation and asset cross-reference checks (blockstate/model/texture) |
| `gradle/prod-run.gradle` | Production-like environment runner for Fabric and NeoForge |
| `scripts/convert_nbt_1_21_to_1_20.py` | NBT structure converter from 1.21.1 to 1.20.1 format |
| `scripts/README_NBT_CONVERSION.md` | Documentation for the NBT conversion script |

## Integration

### 1. Add as a Git submodule

```bash
git submodule add <repo-url> gradle-scripts
```

### 2. Apply scripts in your root `build.gradle`

```groovy
apply from: "${rootDir}/gradle-scripts/gradle/multi-version-tasks.gradle"
apply from: "${rootDir}/gradle-scripts/gradle/resource-validation.gradle"
```

For platform subprojects (fabric/neoforge `build.gradle`):

```groovy
apply from: "${rootDir}/gradle-scripts/gradle/prod-run.gradle"
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

## License

LGPL-3.0
