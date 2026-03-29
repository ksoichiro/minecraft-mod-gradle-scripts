# NBT Conversion Tasks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the Python NBT conversion script into a Gradle task integrated into the mcmod plugin, with pluggable converter support and processResources integration.

**Architecture:** Add `NbtConverter` interface with `byte[]` I/O (library-agnostic), a default converter (DataVersion-only update), and a 1.21→1.20 converter using [Querz NBT](https://github.com/Querz/NBT). Register `convertNbt` task via `NbtConversionTasks` following the existing feature-flag pattern.

**Tech Stack:** Groovy, Gradle Plugin API, [Querz NBT 6.1](https://jitpack.io/p/Querz/NBT) (via JitPack), Spock for tests.

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `build.gradle` | Add JitPack repository and Querz NBT dependency |
| Modify | `src/main/groovy/com/github/ksoichiro/mcmod/McmodExtension.groovy` | Add `NbtConversionExtension` inner class and `nbtConversion` field |
| Modify | `src/main/groovy/com/github/ksoichiro/mcmod/McmodPlugin.groovy` | Register `NbtConversionTasks` when enabled |
| Create | `src/main/groovy/com/github/ksoichiro/mcmod/NbtConverter.groovy` | Converter interface |
| Create | `src/main/groovy/com/github/ksoichiro/mcmod/DefaultNbtConverter.groovy` | DataVersion-only converter |
| Create | `src/main/groovy/com/github/ksoichiro/mcmod/V1_21ToV1_20NbtConverter.groovy` | 1.21→1.20 conversion logic |
| Create | `src/main/groovy/com/github/ksoichiro/mcmod/NbtConversionTasks.groovy` | Task registration and processResources integration |
| Modify | `src/test/groovy/com/github/ksoichiro/mcmod/McmodPluginTest.groovy` | Task registration tests |
| Create | `src/test/groovy/com/github/ksoichiro/mcmod/DefaultNbtConverterTest.groovy` | DefaultNbtConverter unit tests |
| Create | `src/test/groovy/com/github/ksoichiro/mcmod/V1_21ToV1_20NbtConverterTest.groovy` | V1_21ToV1_20NbtConverter unit tests |

---

### Task 1: Add Querz NBT dependency

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Add JitPack repository and dependency**

In `build.gradle`, add JitPack to `repositories` and add the NBT dependency:

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation gradleApi()
    implementation localGroovy()
    implementation 'com.github.Querz:NBT:6.1'
    testImplementation platform('org.spockframework:spock-bom:2.3-groovy-3.0')
    testImplementation 'org.spockframework:spock-core'
}
```

- [ ] **Step 2: Verify dependency resolves**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew dependencies --configuration compileClasspath`
Expected: Output includes `com.github.Querz:NBT:6.1`

- [ ] **Step 3: Commit**

```bash
git add build.gradle
git commit -m "build: add Querz NBT dependency via JitPack"
```

---

### Task 2: Create NbtConverter interface

**Files:**
- Create: `src/main/groovy/com/github/ksoichiro/mcmod/NbtConverter.groovy`

- [ ] **Step 1: Create the interface**

```groovy
package com.github.ksoichiro.mcmod

/**
 * Interface for converting NBT structure files between Minecraft versions.
 * Input and output are raw gzip-compressed NBT bytes to avoid
 * coupling consumers to a specific NBT library.
 */
interface NbtConverter {
    /**
     * Convert an NBT structure file.
     *
     * @param input gzip-compressed NBT bytes
     * @param targetDataVersion the Minecraft DataVersion to set in the output
     * @return gzip-compressed NBT bytes of the converted structure
     */
    byte[] convert(byte[] input, int targetDataVersion)
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew compileGroovy`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/groovy/com/github/ksoichiro/mcmod/NbtConverter.groovy
git commit -m "feat: add NbtConverter interface"
```

---

### Task 3: Implement DefaultNbtConverter

**Files:**
- Create: `src/test/groovy/com/github/ksoichiro/mcmod/DefaultNbtConverterTest.groovy`
- Create: `src/main/groovy/com/github/ksoichiro/mcmod/DefaultNbtConverter.groovy`

- [ ] **Step 1: Write the failing test**

```groovy
package com.github.ksoichiro.mcmod

import net.querz.nbt.io.NBTUtil
import net.querz.nbt.io.NamedTag
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.IntTag
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class DefaultNbtConverterTest extends Specification {

    @TempDir
    Path tempDir

    def "converts DataVersion only, preserving all other data"() {
        given:
        def root = new CompoundTag()
        root.putInt("DataVersion", 3953)
        root.putString("author", "test")
        def inputBytes = nbtToBytes(root)

        when:
        def converter = new DefaultNbtConverter()
        def outputBytes = converter.convert(inputBytes, 3465)

        then:
        def result = bytesToNbt(outputBytes)
        result.getInt("DataVersion") == 3465
        result.getString("author") == "test"
    }

    def "handles NBT without DataVersion field"() {
        given:
        def root = new CompoundTag()
        root.putString("author", "test")
        def inputBytes = nbtToBytes(root)

        when:
        def converter = new DefaultNbtConverter()
        def outputBytes = converter.convert(inputBytes, 3465)

        then:
        def result = bytesToNbt(outputBytes)
        result.getInt("DataVersion") == 3465
        result.getString("author") == "test"
    }

    def "produces deterministic output for same input"() {
        given:
        def root = new CompoundTag()
        root.putInt("DataVersion", 3953)
        root.putString("data", "value")
        def inputBytes = nbtToBytes(root)

        when:
        def converter = new DefaultNbtConverter()
        def output1 = converter.convert(inputBytes, 3465)
        def output2 = converter.convert(inputBytes, 3465)

        then:
        output1 == output2
    }

    // Helper: CompoundTag -> gzip-compressed bytes
    private byte[] nbtToBytes(CompoundTag tag) {
        def file = tempDir.resolve("temp.nbt").toFile()
        NBTUtil.write(new NamedTag("", tag), file)
        return file.bytes
    }

    // Helper: gzip-compressed bytes -> CompoundTag
    private CompoundTag bytesToNbt(byte[] bytes) {
        def file = tempDir.resolve("result.nbt").toFile()
        file.bytes = bytes
        return NBTUtil.read(file).getTag() as CompoundTag
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew test --tests "com.github.ksoichiro.mcmod.DefaultNbtConverterTest"`
Expected: FAIL — `DefaultNbtConverter` class not found

- [ ] **Step 3: Write minimal implementation**

```groovy
package com.github.ksoichiro.mcmod

import net.querz.nbt.io.NBTDeserializer
import net.querz.nbt.io.NBTSerializer
import net.querz.nbt.tag.CompoundTag

class DefaultNbtConverter implements NbtConverter {

    @Override
    byte[] convert(byte[] input, int targetDataVersion) {
        def namedTag = new NBTDeserializer(true).fromBytes(input)
        def root = namedTag.getTag() as CompoundTag

        root.putInt("DataVersion", targetDataVersion)

        return serializeToBytes(namedTag)
    }

    /**
     * Serialize a NamedTag to gzip-compressed bytes with deterministic output.
     * Uses mtime=0 in gzip header for reproducibility.
     */
    protected static byte[] serializeToBytes(net.querz.nbt.io.NamedTag namedTag) {
        def bos = new ByteArrayOutputStream()
        new NBTSerializer(true).toStream(namedTag, bos)
        return bos.toByteArray()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew test --tests "com.github.ksoichiro.mcmod.DefaultNbtConverterTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/com/github/ksoichiro/mcmod/DefaultNbtConverter.groovy src/test/groovy/com/github/ksoichiro/mcmod/DefaultNbtConverterTest.groovy
git commit -m "feat: implement DefaultNbtConverter with DataVersion-only update"
```

---

### Task 4: Implement V1_21ToV1_20NbtConverter

**Files:**
- Create: `src/test/groovy/com/github/ksoichiro/mcmod/V1_21ToV1_20NbtConverterTest.groovy`
- Create: `src/main/groovy/com/github/ksoichiro/mcmod/V1_21ToV1_20NbtConverter.groovy`

- [ ] **Step 1: Write the failing tests**

```groovy
package com.github.ksoichiro.mcmod

import net.querz.nbt.io.NBTUtil
import net.querz.nbt.io.NamedTag
import net.querz.nbt.tag.ByteTag
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.IntTag
import net.querz.nbt.tag.ListTag
import net.querz.nbt.tag.ShortTag
import net.querz.nbt.tag.StringTag
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class V1_21ToV1_20NbtConverterTest extends Specification {

    @TempDir
    Path tempDir

    def "converts DataVersion"() {
        given:
        def root = createStructureWithBlockEntities([])
        root.putInt("DataVersion", 3953)
        def inputBytes = nbtToBytes(root)

        when:
        def converter = new V1_21ToV1_20NbtConverter()
        def outputBytes = converter.convert(inputBytes, 3465)

        then:
        def result = bytesToNbt(outputBytes)
        result.getInt("DataVersion") == 3465
    }

    def "converts item with components to tag format in block_entities"() {
        given:
        // 1.21.1 format: {slot: 0, item: {id: "minecraft:stone", count: 1, components: {minecraft:custom_data: {foo: "bar"}}}}
        def components = new CompoundTag()
        def customData = new CompoundTag()
        customData.putString("foo", "bar")
        components.put("minecraft:custom_data", customData)

        def itemData = new CompoundTag()
        itemData.putString("id", "minecraft:diamond_sword")
        itemData.putInt("count", 1)
        itemData.put("components", components)

        def item = new CompoundTag()
        item.putInt("slot", 0)
        item.put("item", itemData)

        def items = new ListTag<>(CompoundTag.class)
        items.add(item)

        def blockEntity = new CompoundTag()
        blockEntity.put("Items", items)

        def root = createStructureWithBlockEntities([blockEntity])
        def inputBytes = nbtToBytes(root)

        when:
        def converter = new V1_21ToV1_20NbtConverter()
        def outputBytes = converter.convert(inputBytes, 3465)

        then:
        def result = bytesToNbt(outputBytes)
        def resultBe = result.getListTag("block_entities").get(0) as CompoundTag
        def resultItems = resultBe.getListTag("Items")
        def resultItem = resultItems.get(0) as CompoundTag

        // 1.20.1 format: {Slot: 0b, id: "minecraft:diamond_sword", Count: 1b, tag: {foo: "bar"}}
        resultItem.getByte("Slot") == (byte) 0
        resultItem.getString("id") == "minecraft:diamond_sword"
        resultItem.getByte("Count") == (byte) 1
        def tagData = resultItem.getCompoundTag("tag")
        tagData.getString("foo") == "bar"
    }

    def "converts enchantments in components"() {
        given:
        def levels = new CompoundTag()
        levels.putInt("minecraft:sharpness", 5)
        def enchantments = new CompoundTag()
        enchantments.put("levels", levels)

        def components = new CompoundTag()
        components.put("minecraft:enchantments", enchantments)

        def itemData = new CompoundTag()
        itemData.putString("id", "minecraft:diamond_sword")
        itemData.putInt("count", 1)
        itemData.put("components", components)

        def item = new CompoundTag()
        item.putInt("slot", 0)
        item.put("item", itemData)

        def items = new ListTag<>(CompoundTag.class)
        items.add(item)

        def blockEntity = new CompoundTag()
        blockEntity.put("Items", items)

        def root = createStructureWithBlockEntities([blockEntity])
        def inputBytes = nbtToBytes(root)

        when:
        def converter = new V1_21ToV1_20NbtConverter()
        def outputBytes = converter.convert(inputBytes, 3465)

        then:
        def result = bytesToNbt(outputBytes)
        def resultBe = result.getListTag("block_entities").get(0) as CompoundTag
        def resultItem = resultBe.getListTag("Items").get(0) as CompoundTag
        def tag = resultItem.getCompoundTag("tag")
        def enchList = tag.getListTag("Enchantments")
        enchList.size() == 1
        def ench = enchList.get(0) as CompoundTag
        ench.getString("id") == "minecraft:sharpness"
        ench.getShort("lvl") == (short) 5
    }

    def "converts display name and lore in components"() {
        given:
        def components = new CompoundTag()
        components.putString("minecraft:custom_name", '{"text":"My Sword"}')
        def lore = new ListTag<>(StringTag.class)
        lore.addString("line1")
        lore.addString("line2")
        components.put("minecraft:lore", lore)

        def itemData = new CompoundTag()
        itemData.putString("id", "minecraft:stone")
        itemData.putInt("count", 1)
        itemData.put("components", components)

        def item = new CompoundTag()
        item.putInt("slot", 0)
        item.put("item", itemData)

        def items = new ListTag<>(CompoundTag.class)
        items.add(item)

        def blockEntity = new CompoundTag()
        blockEntity.put("Items", items)

        def root = createStructureWithBlockEntities([blockEntity])
        def inputBytes = nbtToBytes(root)

        when:
        def converter = new V1_21ToV1_20NbtConverter()
        def outputBytes = converter.convert(inputBytes, 3465)

        then:
        def result = bytesToNbt(outputBytes)
        def resultBe = result.getListTag("block_entities").get(0) as CompoundTag
        def resultItem = resultBe.getListTag("Items").get(0) as CompoundTag
        def tag = resultItem.getCompoundTag("tag")
        def display = tag.getCompoundTag("display")
        display.getString("Name") == '{"text":"My Sword"}'
        def resultLore = display.getListTag("Lore")
        resultLore.size() == 2
    }

    def "converts damage and unbreakable in components"() {
        given:
        def components = new CompoundTag()
        components.putInt("minecraft:damage", 42)
        def unbreakable = new CompoundTag()
        unbreakable.putByte("show_in_tooltip", (byte) 1)
        components.put("minecraft:unbreakable", unbreakable)

        def itemData = new CompoundTag()
        itemData.putString("id", "minecraft:stone")
        itemData.putInt("count", 1)
        itemData.put("components", components)

        def item = new CompoundTag()
        item.putInt("slot", 0)
        item.put("item", itemData)

        def items = new ListTag<>(CompoundTag.class)
        items.add(item)

        def blockEntity = new CompoundTag()
        blockEntity.put("Items", items)

        def root = createStructureWithBlockEntities([blockEntity])
        def inputBytes = nbtToBytes(root)

        when:
        def converter = new V1_21ToV1_20NbtConverter()
        def outputBytes = converter.convert(inputBytes, 3465)

        then:
        def result = bytesToNbt(outputBytes)
        def resultBe = result.getListTag("block_entities").get(0) as CompoundTag
        def resultItem = resultBe.getListTag("Items").get(0) as CompoundTag
        def tag = resultItem.getCompoundTag("tag")
        tag.getInt("Damage") == 42
        tag.getByte("Unbreakable") == (byte) 1
    }

    def "converts inline blocks[].nbt items"() {
        given:
        def components = new CompoundTag()
        def customData = new CompoundTag()
        customData.putString("key", "value")
        components.put("minecraft:custom_data", customData)

        def itemData = new CompoundTag()
        itemData.putString("id", "minecraft:stone")
        itemData.putInt("count", 3)
        itemData.put("components", components)

        def item = new CompoundTag()
        item.putInt("slot", 1)
        item.put("item", itemData)

        def items = new ListTag<>(CompoundTag.class)
        items.add(item)

        def blockNbt = new CompoundTag()
        blockNbt.put("Items", items)

        def block = new CompoundTag()
        block.put("nbt", blockNbt)

        def blocks = new ListTag<>(CompoundTag.class)
        blocks.add(block)

        def root = new CompoundTag()
        root.putInt("DataVersion", 3953)
        root.put("blocks", blocks)
        def inputBytes = nbtToBytes(root)

        when:
        def converter = new V1_21ToV1_20NbtConverter()
        def outputBytes = converter.convert(inputBytes, 3465)

        then:
        def result = bytesToNbt(outputBytes)
        def resultBlock = result.getListTag("blocks").get(0) as CompoundTag
        def resultNbt = resultBlock.getCompoundTag("nbt")
        def resultItem = resultNbt.getListTag("Items").get(0) as CompoundTag
        resultItem.getByte("Slot") == (byte) 1
        resultItem.getString("id") == "minecraft:stone"
        resultItem.getByte("Count") == (byte) 3
        def tag = resultItem.getCompoundTag("tag")
        tag.getString("key") == "value"
    }

    def "converts legacy format items (count lowercase to Count byte)"() {
        given:
        // Legacy/mixed format: {Slot: 0b, id: "minecraft:stone", count: 3}
        def item = new CompoundTag()
        item.putByte("Slot", (byte) 0)
        item.putString("id", "minecraft:stone")
        item.putInt("count", 3)

        def items = new ListTag<>(CompoundTag.class)
        items.add(item)

        def blockEntity = new CompoundTag()
        blockEntity.put("Items", items)

        def root = createStructureWithBlockEntities([blockEntity])
        def inputBytes = nbtToBytes(root)

        when:
        def converter = new V1_21ToV1_20NbtConverter()
        def outputBytes = converter.convert(inputBytes, 3465)

        then:
        def result = bytesToNbt(outputBytes)
        def resultBe = result.getListTag("block_entities").get(0) as CompoundTag
        def resultItem = resultBe.getListTag("Items").get(0) as CompoundTag
        resultItem.getByte("Slot") == (byte) 0
        resultItem.getString("id") == "minecraft:stone"
        resultItem.getByte("Count") == (byte) 3
    }

    def "produces deterministic output"() {
        given:
        def root = createStructureWithBlockEntities([])
        root.putInt("DataVersion", 3953)
        def inputBytes = nbtToBytes(root)

        when:
        def converter = new V1_21ToV1_20NbtConverter()
        def output1 = converter.convert(inputBytes, 3465)
        def output2 = converter.convert(inputBytes, 3465)

        then:
        output1 == output2
    }

    // --- Helpers ---

    private CompoundTag createStructureWithBlockEntities(List<CompoundTag> entities) {
        def root = new CompoundTag()
        root.putInt("DataVersion", 3953)
        def beList = new ListTag<>(CompoundTag.class)
        entities.each { beList.add(it) }
        root.put("block_entities", beList)
        return root
    }

    private byte[] nbtToBytes(CompoundTag tag) {
        def file = tempDir.resolve("temp.nbt").toFile()
        NBTUtil.write(new NamedTag("", tag), file)
        return file.bytes
    }

    private CompoundTag bytesToNbt(byte[] bytes) {
        def file = tempDir.resolve("result.nbt").toFile()
        file.bytes = bytes
        return NBTUtil.read(file).getTag() as CompoundTag
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew test --tests "com.github.ksoichiro.mcmod.V1_21ToV1_20NbtConverterTest"`
Expected: FAIL — `V1_21ToV1_20NbtConverter` class not found

- [ ] **Step 3: Write implementation**

```groovy
package com.github.ksoichiro.mcmod

import net.querz.nbt.io.NBTDeserializer
import net.querz.nbt.io.NBTSerializer
import net.querz.nbt.io.NamedTag
import net.querz.nbt.tag.ByteTag
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.IntTag
import net.querz.nbt.tag.ListTag
import net.querz.nbt.tag.ShortTag
import net.querz.nbt.tag.StringTag
import net.querz.nbt.tag.Tag

class V1_21ToV1_20NbtConverter implements NbtConverter {

    @Override
    byte[] convert(byte[] input, int targetDataVersion) {
        def namedTag = new NBTDeserializer(true).fromBytes(input)
        def root = namedTag.getTag() as CompoundTag

        root.putInt("DataVersion", targetDataVersion)

        // Convert separate block_entities field
        if (root.containsKey("block_entities")) {
            def blockEntities = root.getListTag("block_entities")
            if (blockEntities != null && blockEntities.size() > 0) {
                def converted = new ListTag<>(CompoundTag.class)
                for (int i = 0; i < blockEntities.size(); i++) {
                    converted.add(convertBlockEntity(blockEntities.get(i) as CompoundTag))
                }
                root.put("block_entities", converted)
            }
        }

        // Convert inline blocks[].nbt
        if (root.containsKey("blocks")) {
            def blocks = root.getListTag("blocks")
            if (blocks != null) {
                for (int i = 0; i < blocks.size(); i++) {
                    def block = blocks.get(i) as CompoundTag
                    if (block.containsKey("nbt")) {
                        def blockNbt = block.getCompoundTag("nbt")
                        if (blockNbt.containsKey("Items")) {
                            block.put("nbt", convertBlockEntity(blockNbt))
                        }
                    }
                }
            }
        }

        return DefaultNbtConverter.serializeToBytes(namedTag)
    }

    private static CompoundTag convertBlockEntity(CompoundTag blockEntity) {
        if (!blockEntity.containsKey("Items")) {
            return blockEntity
        }
        def items = blockEntity.getListTag("Items")
        if (items == null || items.size() == 0) {
            return blockEntity
        }

        def convertedItems = new ListTag<>(CompoundTag.class)
        for (int i = 0; i < items.size(); i++) {
            def item = items.get(i) as CompoundTag
            if (item.containsKey("item")) {
                // New 1.21.1 format: {slot, item: {id, count, components}}
                convertedItems.add(convertItem121To120(item))
            } else {
                // Legacy/mixed format: {Slot, id, count}
                convertedItems.add(convertLegacyItemFormat(item))
            }
        }

        def result = new CompoundTag()
        blockEntity.forEach { key, value -> result.put(key, value) }
        result.put("Items", convertedItems)
        return result
    }

    private static CompoundTag convertItem121To120(CompoundTag item121) {
        def item120 = new CompoundTag()

        // slot (int) -> Slot (byte)
        if (item121.containsKey("slot")) {
            item120.putByte("Slot", (byte) item121.getInt("slot"))
        }

        // Extract item data from nested "item" field
        def itemData = item121.getCompoundTag("item")
        if (itemData == null) return item120

        // id -> id (string)
        if (itemData.containsKey("id")) {
            item120.putString("id", itemData.getString("id"))
        }

        // count (int) -> Count (byte)
        if (itemData.containsKey("count")) {
            item120.putByte("Count", (byte) itemData.getInt("count"))
        }

        // components -> tag
        if (itemData.containsKey("components")) {
            def components = itemData.getCompoundTag("components")
            if (components != null && components.size() > 0) {
                def tagData = convertComponentsToTag(components)
                if (tagData.size() > 0) {
                    item120.put("tag", tagData)
                }
            }
        }

        return item120
    }

    private static CompoundTag convertLegacyItemFormat(CompoundTag item) {
        def item120 = new CompoundTag()

        // Copy Slot (ensure Byte type)
        if (item.containsKey("Slot")) {
            def slotTag = item.get("Slot")
            if (slotTag instanceof ByteTag) {
                item120.put("Slot", slotTag)
            } else {
                item120.putByte("Slot", (byte) ((Tag) slotTag).asInt())
            }
        }

        // Copy id
        if (item.containsKey("id")) {
            item120.putString("id", item.getString("id"))
        }

        // count -> Count (byte)
        if (item.containsKey("count")) {
            item120.putByte("Count", (byte) item.getInt("count"))
        } else if (item.containsKey("Count")) {
            def countTag = item.get("Count")
            if (countTag instanceof ByteTag) {
                item120.put("Count", countTag)
            } else {
                item120.putByte("Count", (byte) ((Tag) countTag).asInt())
            }
        }

        // Copy tag if present
        if (item.containsKey("tag")) {
            item120.put("tag", item.getCompoundTag("tag"))
        }

        return item120
    }

    private static CompoundTag convertComponentsToTag(CompoundTag components) {
        def tagData = new CompoundTag()

        // minecraft:custom_data -> direct copy to tag
        if (components.containsKey("minecraft:custom_data")) {
            def customData = components.getCompoundTag("minecraft:custom_data")
            if (customData != null) {
                customData.forEach { key, value -> tagData.put(key, value) }
            }
        }

        // minecraft:enchantments -> tag.Enchantments
        if (components.containsKey("minecraft:enchantments")) {
            def enchantComp = components.getCompoundTag("minecraft:enchantments")
            if (enchantComp != null && enchantComp.containsKey("levels")) {
                def levels = enchantComp.getCompoundTag("levels")
                if (levels != null) {
                    def enchantments = new ListTag<>(CompoundTag.class)
                    levels.forEach { enchId, level ->
                        def ench = new CompoundTag()
                        ench.putString("id", enchId)
                        ench.putShort("lvl", (short) ((Tag) level).asInt())
                        enchantments.add(ench)
                    }
                    tagData.put("Enchantments", enchantments)
                }
            }
        }

        // minecraft:custom_name / minecraft:lore -> tag.display
        def displayData = new CompoundTag()
        if (components.containsKey("minecraft:custom_name")) {
            displayData.putString("Name", components.getString("minecraft:custom_name"))
        }
        if (components.containsKey("minecraft:lore")) {
            displayData.put("Lore", components.get("minecraft:lore"))
        }
        if (displayData.size() > 0) {
            tagData.put("display", displayData)
        }

        // minecraft:damage -> tag.Damage
        if (components.containsKey("minecraft:damage")) {
            tagData.putInt("Damage", components.getInt("minecraft:damage"))
        }

        // minecraft:unbreakable -> tag.Unbreakable
        if (components.containsKey("minecraft:unbreakable")) {
            def unbreakable = components.getCompoundTag("minecraft:unbreakable")
            if (unbreakable != null) {
                tagData.putByte("Unbreakable", (byte) 1)
            }
        }

        return tagData
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew test --tests "com.github.ksoichiro.mcmod.V1_21ToV1_20NbtConverterTest"`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/com/github/ksoichiro/mcmod/V1_21ToV1_20NbtConverter.groovy src/test/groovy/com/github/ksoichiro/mcmod/V1_21ToV1_20NbtConverterTest.groovy
git commit -m "feat: implement V1_21ToV1_20NbtConverter"
```

---

### Task 5: Add NbtConversionExtension to McmodExtension

**Files:**
- Modify: `src/main/groovy/com/github/ksoichiro/mcmod/McmodExtension.groovy`

- [ ] **Step 1: Add NbtConversionExtension inner class and field**

Add the following to `McmodExtension.groovy`:

1. Add field alongside existing sub-extensions:

```groovy
final NbtConversionExtension nbtConversion = new NbtConversionExtension()
```

2. Add DSL method alongside existing ones:

```groovy
void nbtConversion(Action<? super NbtConversionExtension> action) {
    action.execute(nbtConversion)
}
```

3. Add inner class alongside existing sub-extension classes:

```groovy
static class NbtConversionExtension {
    boolean enabled = false
    String sourceVersion
    String targetVersion
    String inputDir
    String outputDir
    Class<? extends NbtConverter> converterClass
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew compileGroovy`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/groovy/com/github/ksoichiro/mcmod/McmodExtension.groovy
git commit -m "feat: add NbtConversionExtension to McmodExtension"
```

---

### Task 6: Implement NbtConversionTasks

**Files:**
- Create: `src/main/groovy/com/github/ksoichiro/mcmod/NbtConversionTasks.groovy`
- Modify: `src/main/groovy/com/github/ksoichiro/mcmod/McmodPlugin.groovy`

- [ ] **Step 1: Create NbtConversionTasks**

```groovy
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
            project.sourceSets.matching { it.name == 'main' }.configureEach {
                resources.srcDir(outputDir)
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
```

- [ ] **Step 2: Register in McmodPlugin**

In `McmodPlugin.groovy`, add inside the `afterEvaluate` block, after the `prodRun` block:

```groovy
if (ext.nbtConversion.enabled) {
    NbtConversionTasks.register(project, ext)
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew compileGroovy`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/groovy/com/github/ksoichiro/mcmod/NbtConversionTasks.groovy src/main/groovy/com/github/ksoichiro/mcmod/McmodPlugin.groovy
git commit -m "feat: implement NbtConversionTasks with processResources integration"
```

---

### Task 7: Add plugin integration tests

**Files:**
- Modify: `src/test/groovy/com/github/ksoichiro/mcmod/McmodPluginTest.groovy`

- [ ] **Step 1: Write failing tests**

Add the following test methods to `McmodPluginTest.groovy`:

```groovy
def "nbtConversion task is NOT registered when disabled (default)"() {
    given:
    Project project = ProjectBuilder.builder().build()
    project.ext.set('supported_mc_versions', '1.20.1')

    when:
    project.plugins.apply('com.github.ksoichiro.mcmod')
    project.evaluate()

    then:
    project.tasks.findByName('convertNbt') == null
}

def "nbtConversion task is registered when enabled"() {
    given:
    Project project = ProjectBuilder.builder().build()
    project.ext.set('supported_mc_versions', '1.20.1')

    when:
    project.plugins.apply('com.github.ksoichiro.mcmod')
    project.plugins.apply('java')
    def ext = project.extensions.getByType(McmodExtension)
    ext.nbtConversion.enabled = true
    ext.nbtConversion.sourceVersion = '1.21.1'
    ext.nbtConversion.targetVersion = '1.20.1'
    ext.nbtConversion.inputDir = 'src/main/resources/data/testmod/structure'
    ext.nbtConversion.outputDir = 'build/generated/nbt'
    project.evaluate()

    then:
    project.tasks.findByName('convertNbt') != null
}

def "nbtConversion requires sourceVersion and targetVersion"() {
    given:
    Project project = ProjectBuilder.builder().build()
    project.ext.set('supported_mc_versions', '1.20.1')

    when:
    project.plugins.apply('com.github.ksoichiro.mcmod')
    project.plugins.apply('java')
    def ext = project.extensions.getByType(McmodExtension)
    ext.nbtConversion.enabled = true
    ext.nbtConversion.inputDir = 'src/main/resources'
    ext.nbtConversion.outputDir = 'build/generated/nbt'
    project.evaluate()

    then:
    thrown(GradleException)
}

def "nbtConversion requires inputDir and outputDir"() {
    given:
    Project project = ProjectBuilder.builder().build()
    project.ext.set('supported_mc_versions', '1.20.1')

    when:
    project.plugins.apply('com.github.ksoichiro.mcmod')
    project.plugins.apply('java')
    def ext = project.extensions.getByType(McmodExtension)
    ext.nbtConversion.enabled = true
    ext.nbtConversion.sourceVersion = '1.21.1'
    ext.nbtConversion.targetVersion = '1.20.1'
    project.evaluate()

    then:
    thrown(GradleException)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew test`
Expected: Some tests FAIL (the ones relying on tasks that were just implemented should pass now)

Note: If all tests pass, this means the prior tasks were implemented correctly. Proceed to step 3.

- [ ] **Step 3: Run all tests to verify they pass**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew test`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/groovy/com/github/ksoichiro/mcmod/McmodPluginTest.groovy
git commit -m "test: add nbtConversion task registration tests"
```

---

### Task 8: Run full test suite and verify

- [ ] **Step 1: Run all tests**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew clean test`
Expected: All tests PASS

- [ ] **Step 2: Verify build**

Run: `cd /Users/ksoichiro/src/github.com/ksoichiro/minecraft-mod-gradle-scripts && ./gradlew build`
Expected: BUILD SUCCESSFUL
