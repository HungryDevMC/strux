package dev.gesp.structural.recording.io;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A de-duplicating table of strings used by the binary codec.
 *
 * <p>Material ids ({@code "minecraft:oak_planks[axis=y]"}) and actor ids (player
 * UUIDs) repeat across thousands of blocks and events. Storing each one once in a
 * table and referencing it by a small integer index everywhere else is most of the
 * remaining size win after varints. A {@code null} string is encoded as index −1
 * (written as the unsigned varint 0; real indices are stored +1).
 */
final class StringTable {

    private final List<String> strings = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();

    /** Intern a string, returning its stable index. {@code null} maps to −1 (no entry). */
    int intern(String s) {
        if (s == null) {
            return -1;
        }
        Integer existing = index.get(s);
        if (existing != null) {
            return existing;
        }
        int id = strings.size();
        strings.add(s);
        index.put(s, id);
        return id;
    }

    /** The interned strings in index order (for writing the table). */
    List<String> entries() {
        return strings;
    }

    /** Build a read-side table from a list of entries. */
    static String[] of(List<String> entries) {
        return entries.toArray(new String[0]);
    }
}
