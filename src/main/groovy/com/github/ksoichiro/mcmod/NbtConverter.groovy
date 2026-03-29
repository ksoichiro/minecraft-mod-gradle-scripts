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
