package dev.gesp.structural.recording.io;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Variable-length integer encoding (LEB128-style, unsigned) plus a zig-zag wrapper
 * for signed values.
 *
 * <p>Why: most numbers in a recording are small (sequence ids, list sizes, block
 * coordinate deltas of ±1). A fixed 4- or 8-byte int wastes space on every one. A
 * varint spends one byte for values under 128, two under 16384, and so on — which is
 * where the binary format gets most of its size win over JSON.
 *
 * <p>Signed values (coordinate deltas, which go negative) are zig-zag mapped first so
 * small magnitudes of either sign stay small: 0→0, -1→1, 1→2, -2→3, …
 */
final class VarInt {

    private VarInt() {}

    /** Write an unsigned long as a varint. */
    static void writeUnsigned(OutputStream out, long value) throws IOException {
        // Treat as unsigned: shift right with >>> so a "negative" long still terminates.
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) (v & 0x7F));
    }

    /** Read an unsigned varint written by {@link #writeUnsigned}. */
    static long readUnsigned(InputStream in) throws IOException {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("truncated varint");
            }
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 64) {
                throw new IOException("varint too long (corrupt stream)");
            }
        }
    }

    /** Write a signed long via zig-zag + varint. */
    static void writeSigned(OutputStream out, long value) throws IOException {
        writeUnsigned(out, (value << 1) ^ (value >> 63));
    }

    /** Read a signed long written by {@link #writeSigned}. */
    static long readSigned(InputStream in) throws IOException {
        long raw = readUnsigned(in);
        return (raw >>> 1) ^ -(raw & 1);
    }
}
