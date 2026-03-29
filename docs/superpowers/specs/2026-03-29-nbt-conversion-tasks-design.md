# NBT Conversion Tasks Design

## Overview

Convert the standalone Python script `convert_nbt_1_21_to_1_20.py` into a Gradle task integrated into the `mcmod` plugin. The task converts Minecraft structure NBT files between versions, with pluggable conversion logic.

## Extension Configuration

```groovy
mcmod {
    nbtConversion {
        enabled = true
        sourceVersion = '1.21.1'      // required
        targetVersion = '1.20.1'      // required
        inputDir = 'path/to/input'    // required
        outputDir = 'path/to/output'  // required
        converterClass = null          // optional, auto-selected if omitted
    }
}
```

Each subproject configures its own `nbtConversion` block. Subprojects where no conversion is needed simply omit the configuration or set `enabled = false`.

## Components

### NbtConverter Interface

```groovy
interface NbtConverter {
    byte[] convert(byte[] input, int targetDataVersion)
}
```

- Library-agnostic: input and output are raw NBT bytes (gzip-compressed)
- Implementations may use any NBT library internally

### DefaultNbtConverter

- Updates only the `DataVersion` field
- Used when no format changes are needed between source and target versions (copy with DataVersion update)

### V1_21ToV1_20NbtConverter

- Equivalent to the existing Python script's conversion logic
- Converts 1.21.1 data components format to 1.20.1 NBT tag format:
  - Item format: `components` -> `tag`
  - Field names: `count` (int) -> `Count` (byte), `slot` (int) -> `Slot` (byte)
  - Component conversions: enchantments, custom names, lore, damage, unbreakable
  - Container items in `block_entities` and inline `blocks[].nbt`
- Uses Querz NBT (`net.querz:nbt`) internally

### Converter Auto-Selection

When `converterClass` is not specified:

| Source -> Target | Converter |
|---|---|
| 1.21.x -> 1.20.x | `V1_21ToV1_20NbtConverter` |
| All other pairs | `DefaultNbtConverter` |

### NbtConversionTasks

Task registration class following the existing pattern (`register(project, ext)`).

## Task: `convertNbt`

- Scans `inputDir` for `.nbt` files
- Applies the selected converter to each file
- Writes converted files to `outputDir` under `build/`
- Deterministic output (fixed gzip mtime for reproducible builds)

### processResources Integration

- `convertNbt` runs before `processResources`
- `outputDir` is added as an additional resource source directory
- This ensures converted NBT files are included in the built JAR

## Dependency

Add to `build.gradle`:

```groovy
implementation 'net.querz:nbt:6.1'
```

## Testing

- Task registration and processResources integration tests in `McmodPluginTest`
- Converter unit tests with test NBT files
