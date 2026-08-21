package com.github.ksoichiro.mcmod

import net.querz.nbt.io.NBTDeserializer
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

    def "output is a complete gzip stream that standard readers accept"() {
        given:
        def root = new CompoundTag()
        root.putInt("DataVersion", 3953)
        root.putString("author", "test")
        def inputBytes = nbtToBytes(root)

        when:
        def outputBytes = new DefaultNbtConverter().convert(inputBytes, 3465)

        then:
        // Querz's own reader tolerates a gzip stream with no deflate trailer,
        // so a round trip through this library cannot detect a truncated file.
        // Decompress with the JDK reader instead, which is what Minecraft and
        // every other gzip consumer effectively does.
        def decompressed = new java.util.zip.GZIPInputStream(
                new ByteArrayInputStream(outputBytes)).bytes
        // The payload must also be the complete NBT document, not just a
        // non-empty prefix that happened to decompress.
        def reparsed = new NBTDeserializer(false).fromBytes(decompressed).getTag() as CompoundTag
        reparsed.getInt("DataVersion") == 3465
        reparsed.getString("author") == "test"
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
