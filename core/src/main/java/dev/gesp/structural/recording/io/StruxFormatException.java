package dev.gesp.structural.recording.io;

import java.io.IOException;

/**
 * Thrown when a {@code .strx} file is not a valid strux binary recording — a bad
 * magic number, an unsupported version, or a truncated/corrupt body. It is an
 * {@link IOException} so callers handle it alongside ordinary read failures, but its
 * distinct type lets them tell "this isn't a strux file / it's damaged" apart from a
 * disk error. The message always says concretely what was wrong, so a partial read is
 * never mistaken for a valid (but short) recording.
 */
public class StruxFormatException extends IOException {

    public StruxFormatException(String message) {
        super(message);
    }

    public StruxFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
