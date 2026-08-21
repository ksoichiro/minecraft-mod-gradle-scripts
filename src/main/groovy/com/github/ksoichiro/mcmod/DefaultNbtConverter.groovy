package com.github.ksoichiro.mcmod

import net.querz.nbt.io.NBTDeserializer
import net.querz.nbt.io.NBTSerializer
import net.querz.nbt.tag.CompoundTag

import java.util.zip.GZIPOutputStream

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
     *
     * The gzip stream is owned and closed here rather than left to
     * NBTSerializer(true): that constructor wraps the output in a
     * GZIPOutputStream and only flushes it, so the deflate trailer is never
     * written. Querz's own reader tolerates the truncated result, but every
     * other gzip consumer - Minecraft included - rejects it.
     */
    protected static byte[] serializeToBytes(net.querz.nbt.io.NamedTag namedTag) {
        def bos = new ByteArrayOutputStream()
        new GZIPOutputStream(bos).withCloseable { gzip ->
            new NBTSerializer(false).toStream(namedTag, gzip)
        }
        return bos.toByteArray()
    }
}
