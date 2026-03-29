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
        blockEntity.entrySet().forEach { entry -> result.put(entry.getKey(), entry.getValue()) }
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
                customData.entrySet().forEach { entry -> tagData.put(entry.getKey(), entry.getValue()) }
            }
        }

        // minecraft:enchantments -> tag.Enchantments
        if (components.containsKey("minecraft:enchantments")) {
            def enchantComp = components.getCompoundTag("minecraft:enchantments")
            if (enchantComp != null && enchantComp.containsKey("levels")) {
                def levels = enchantComp.getCompoundTag("levels")
                if (levels != null) {
                    def enchantments = new ListTag<>(CompoundTag.class)
                    levels.entrySet().forEach { entry ->
                        def ench = new CompoundTag()
                        ench.putString("id", entry.getKey())
                        ench.putShort("lvl", (short) ((Tag) entry.getValue()).asInt())
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
