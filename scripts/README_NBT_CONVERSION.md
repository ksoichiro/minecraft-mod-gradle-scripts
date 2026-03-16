# NBT Structure Multi-Version Conversion

## Overview

This directory contains tools for converting Minecraft structure NBT files between versions to support multi-version mod development.

**Problem:** NBT structure files created in Minecraft 1.21.1 use the new Data Components format for items, which is incompatible with Minecraft 1.20.1 (which uses the legacy NBT tag format).

**Solution:** Automated conversion script that transforms 1.21.1 structure files to 1.20.1 format during the build process.

## Key Changes (1.20.5+ vs 1.20.4)

Minecraft 1.20.5 introduced a major change in item data storage:

| Aspect | 1.20.4 and Earlier | 1.20.5+ (1.21.1) |
|--------|-------------------|------------------|
| Item ID | `id` (string) | `id` (namespaced string) |
| Count | `Count` (byte) | `count` (integer) |
| Slot | `Slot` (byte) | `slot` (integer) |
| Item Data | `tag: {...}` | `components: {"minecraft:...": ...}` |

### Example: Chest with Enchanted Diamond Sword

**1.21.1 Format:**
```snbt
Items: [
  {
    slot: 0,
    item: {
      id: "minecraft:diamond_sword",
      count: 1,
      components: {
        "minecraft:enchantments": {
          levels: {
            "minecraft:sharpness": 5
          }
        }
      }
    }
  }
]
```

**1.20.1 Format:**
```snbt
Items: [
  {
    Slot: 0b,
    id: "minecraft:diamond_sword",
    Count: 1b,
    tag: {
      Enchantments: [
        {id: "minecraft:sharpness", lvl: 5s}
      ]
    }
  }
]
```

## Files

### `convert_nbt_1_21_to_1_20.py`

Python script for converting NBT structure files from 1.21.1 to 1.20.1 format.

**Features:**
- Converts Data Components → NBT tags
- Handles enchantments, display names, lore, damage, unbreakable, etc.
- Batch mode for converting entire directories
- Detailed logging

**Requirements:**
- Python 3
- `nbtlib` package: `pip3 install nbtlib`

**Usage:**

```bash
# Convert single file
python3 convert_nbt_1_21_to_1_20.py <input.nbt> <output.nbt>

# Batch convert directory
python3 convert_nbt_1_21_to_1_20.py --batch <input_dir> <output_dir>

# Example: Convert all structures
python3 convert_nbt_1_21_to_1_20.py --batch \
  common/1.21.1/src/main/resources/data/chronodawn/structure \
  common/1.20.1/src/main/resources/data/chronodawn/structures
```

## Build Integration

The conversion is automatically integrated into the Gradle build process via each `common/{version}/build.gradle`:

### Gradle Task: `convertNbtStructures`

**Behavior:**
- **For 1.20.1 builds:** Automatically converts all NBT files from `common/1.21.1/src/main/resources/.../structure/` to `common/1.20.1/src/main/resources/.../structures/`
- **For 1.21.1+ builds:** Skipped (uses original files)

**Usage:**

```bash
# Build for 1.20.1 (conversion runs automatically)
./gradlew build1_20_1

# Build for 1.21.1 (conversion skipped)
./gradlew build1_21_1

# Build all versions
./gradlew buildAll

# Run conversion task manually
./gradlew :common:convertNbtStructures -Ptarget_mc_version=1.20.1
```

### Build Process Flow

```
1. User runs: ./gradlew build1_20_1
2. Gradle detects target_mc_version=1.20.1
3. convertNbtStructures task runs:
   - Checks Python 3 and nbtlib availability
   - Converts all NBT files in resources-1.21.1/structure/
   - Outputs to resources-1.20.1/structures/
4. processResources task includes converted files
5. Mod JAR built with 1.20.1-compatible structures
```

## Conversion Details

### Supported Component Types

| 1.21.1 Component | 1.20.1 NBT Tag |
|-----------------|---------------|
| `minecraft:custom_data` | `tag` (direct copy) |
| `minecraft:enchantments` | `tag.Enchantments` |
| `minecraft:custom_name` | `tag.display.Name` |
| `minecraft:lore` | `tag.display.Lore` |
| `minecraft:damage` | `tag.Damage` |
| `minecraft:unbreakable` | `tag.Unbreakable` |

### Files Without block_entities

If a structure file does not contain `block_entities` (e.g., structures without chests/barrels), it is copied as-is without modification.

## Testing

### Test with Sample Data

```bash
# Create test NBT with container items
python3 << 'EOF'
import nbtlib
from nbtlib import tag

test_nbt = nbtlib.File({
    "DataVersion": tag.Int(3955),
    "size": tag.List[tag.Int]([tag.Int(5), tag.Int(5), tag.Int(5)]),
    "entities": tag.List[tag.Compound]([]),
    "blocks": tag.List[tag.Compound]([]),
    "palette": tag.List[tag.Compound]([]),
    "block_entities": tag.List[tag.Compound]([
        tag.Compound({
            "id": tag.String("minecraft:chest"),
            "x": tag.Int(2),
            "y": tag.Int(1),
            "z": tag.Int(2),
            "Items": tag.List[tag.Compound]([
                tag.Compound({
                    "slot": tag.Int(0),
                    "item": tag.Compound({
                        "id": tag.String("minecraft:diamond_sword"),
                        "count": tag.Int(1),
                        "components": tag.Compound({
                            "minecraft:enchantments": tag.Compound({
                                "levels": tag.Compound({
                                    "minecraft:sharpness": tag.Int(5)
                                })
                            })
                        })
                    })
                })
            ])
        })
    ])
})

test_nbt.save("test_1_21.nbt", gzipped=True)
print("Created test file: test_1_21.nbt")
EOF

# Convert test file
python3 scripts/convert_nbt_1_21_to_1_20.py test_1_21.nbt test_1_20.nbt

# Verify conversion
python3 << 'EOF'
import nbtlib

nbt_1_20 = nbtlib.load("test_1_20.nbt")
be = nbt_1_20["block_entities"][0]
item = be["Items"][0]

print(f"Slot: {item['Slot']} (type: {type(item['Slot']).__name__})")
print(f"id: {item['id']}")
print(f"Count: {item['Count']} (type: {type(item['Count']).__name__})")
print(f"tag: {list(item['tag'].keys())}")
print(f"Enchantments: {item['tag']['Enchantments']}")
EOF
```

Expected output:
```
Slot: Byte(0) (type: Byte)
id: minecraft:diamond_sword
Count: Byte(1) (type: Byte)
tag: ['Enchantments']
Enchantments: [{'id': 'minecraft:sharpness', 'lvl': Short(5)}]
```

## Maintenance

### Adding New Component Types

To support additional item components:

1. Add conversion logic to `convert_components_to_tag()` in `convert_nbt_1_21_to_1_20.py`
2. Follow the existing pattern:
   - Check if component exists
   - Convert to appropriate NBT tag type
   - Add to `tag_data` dictionary
3. Test with sample data

### Troubleshooting

**Error: `nbtlib not installed`**
```bash
pip3 install nbtlib
```

**Error: `Python 3 with nbtlib is required`**
- Ensure Python 3 is in your PATH
- Install nbtlib: `pip3 install nbtlib`

**Conversion produces incorrect results**
- Check that input file is valid 1.21.1 format
- Verify component structure matches expected format
- Enable debug logging by modifying script

## References

- [Data component format – Minecraft Wiki](https://minecraft.wiki/w/Data_component_format)
- [Item format/Before 1.20.5 – Minecraft Wiki](https://minecraft.wiki/w/Item_format/Before_1.20.5)
- [NBT format – Minecraft Wiki](https://minecraft.wiki/w/NBT_format)
- [nbtlib Documentation](https://github.com/vberlier/nbtlib)

## Future Enhancements

Possible improvements:

1. **Bidirectional Conversion:** Add 1.20.1 → 1.21.1 conversion support
2. **More Component Types:** Support additional components as Minecraft adds them
3. **Validation:** Add schema validation for input/output files
4. **Performance:** Optimize for large structure files (>1MB)
5. **Testing:** Add automated tests with comprehensive test cases
