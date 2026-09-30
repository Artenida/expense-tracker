package com.expensetracker.io;

/**
 * A file that could not be imported, and where. The message says why; the line number
 * is a field as well, so sprint 19's dialog can show it on its own without parsing it
 * back out of the text.
 *
 * <p>Lines are counted the way the user's editor counts them: the header is line 1, the
 * first record is line 2.
 */
public class CsvFormatException extends RuntimeException {

    private final int line;

    public CsvFormatException(int line, String problem) {
        super("line " + line + ": " + problem);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
