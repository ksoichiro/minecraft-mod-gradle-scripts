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
