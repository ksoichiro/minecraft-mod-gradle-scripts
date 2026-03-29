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
