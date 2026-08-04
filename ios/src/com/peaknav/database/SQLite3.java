package com.peaknav.database;

import org.robovm.rt.bro.Bro;
import org.robovm.rt.bro.annotation.Bridge;
import org.robovm.rt.bro.annotation.Library;
import org.robovm.rt.bro.annotation.Pointer;
import org.robovm.rt.bro.ptr.LongPtr;

/**
 * The slice of the C SQLite API this app needs, bound straight to the system library.
 *
 * <p>Every iOS device ships {@code libsqlite3}, so nothing is bundled - this binds to the
 * one already there. It exists because the other two platforms cannot lend theirs: the
 * desktop uses the JDBC driver (RoboVM has no JDBC, and the driver's natives are x86/ARM
 * Linux and macOS), and Android uses its own framework classes.
 *
 * <p>Only the prepare/bind/step/finalize core is bound, which is all
 * {@link MapSqliteIOS} uses. Handles are passed as {@code @Pointer long} because they are
 * opaque to Java; the one place that matters is {@link #SQLITE_TRANSIENT}, below.
 */
@Library("sqlite3")
public final class SQLite3 {

    static {
        Bro.bind(SQLite3.class);
    }

    private SQLite3() {
    }

    // ---------------------------------------------------------------- result codes

    public static final int SQLITE_OK = 0;
    public static final int SQLITE_ROW = 100;
    public static final int SQLITE_DONE = 101;

    /**
     * Tells SQLite to copy the string being bound before returning.
     *
     * <p>{@code SQLITE_TRANSIENT} is defined as {@code ((sqlite3_destructor_type)-1)}, and
     * the -1 is the whole point: without it SQLite keeps the caller's pointer, which here
     * belongs to a temporary buffer the JVM is free to move or free the moment the call
     * returns. The result would be text that is intact in testing and corrupt under memory
     * pressure - so this constant is not an optimisation to reconsider.
     */
    private static final long SQLITE_TRANSIENT = -1L;

    // ---------------------------------------------------------------- connection

    /** {@code int sqlite3_open(const char *filename, sqlite3 **ppDb)} */
    @Bridge(symbol = "sqlite3_open")
    public static native int open(String filename, LongPtr db);

    /** {@code int sqlite3_close(sqlite3*)} */
    @Bridge(symbol = "sqlite3_close")
    public static native int close(@Pointer long db);

    /** {@code const char *sqlite3_errmsg(sqlite3*)} */
    @Bridge(symbol = "sqlite3_errmsg")
    public static native String errmsg(@Pointer long db);

    /**
     * {@code int sqlite3_exec(sqlite3*, const char *sql, callback, void*, char **errmsg)}
     *
     * <p>For statements with no results and no parameters - CREATE TABLE, BEGIN, COMMIT.
     * The callback and error-message pointers are passed as null; a failure is reported by
     * the return code, and {@link #errmsg} says what it was.
     */
    @Bridge(symbol = "sqlite3_exec")
    public static native int exec(@Pointer long db, String sql, @Pointer long callback,
                                  @Pointer long callbackArg, @Pointer long errmsg);

    // ---------------------------------------------------------------- statements

    /** {@code int sqlite3_prepare_v2(sqlite3*, const char *sql, int n, sqlite3_stmt**, const char**)} */
    @Bridge(symbol = "sqlite3_prepare_v2")
    public static native int prepare(@Pointer long db, String sql, int nByte,
                                     LongPtr statement, @Pointer long tail);

    /** {@code int sqlite3_step(sqlite3_stmt*)} - SQLITE_ROW, SQLITE_DONE, or an error. */
    @Bridge(symbol = "sqlite3_step")
    public static native int step(@Pointer long statement);

    /** {@code int sqlite3_reset(sqlite3_stmt*)} - reuse a prepared statement. */
    @Bridge(symbol = "sqlite3_reset")
    public static native int reset(@Pointer long statement);

    /** {@code int sqlite3_finalize(sqlite3_stmt*)} */
    @Bridge(symbol = "sqlite3_finalize")
    public static native int finalizeStatement(@Pointer long statement);

    // ---------------------------------------------------------------- binding (1-based)

    /** {@code int sqlite3_bind_int(sqlite3_stmt*, int index, int value)} */
    @Bridge(symbol = "sqlite3_bind_int")
    public static native int bindInt(@Pointer long statement, int index, int value);

    /** {@code int sqlite3_bind_int64(sqlite3_stmt*, int index, sqlite3_int64 value)} */
    @Bridge(symbol = "sqlite3_bind_int64")
    public static native int bindLong(@Pointer long statement, int index, long value);

    /** {@code int sqlite3_bind_null(sqlite3_stmt*, int index)} */
    @Bridge(symbol = "sqlite3_bind_null")
    public static native int bindNull(@Pointer long statement, int index);

    @Bridge(symbol = "sqlite3_bind_text")
    private static native int bindText(@Pointer long statement, int index, String value,
                                       int length, @Pointer long destructor);

    /** Binds a string, copied by SQLite - see {@link #SQLITE_TRANSIENT}. */
    public static int bindString(long statement, int index, String value) {
        return bindText(statement, index, value, -1, SQLITE_TRANSIENT);
    }

    // ---------------------------------------------------------------- reading (0-based)

    /** {@code int sqlite3_column_int(sqlite3_stmt*, int column)} */
    @Bridge(symbol = "sqlite3_column_int")
    public static native int columnInt(@Pointer long statement, int column);

    /** {@code sqlite3_int64 sqlite3_column_int64(sqlite3_stmt*, int column)} */
    @Bridge(symbol = "sqlite3_column_int64")
    public static native long columnLong(@Pointer long statement, int column);

    /** {@code const unsigned char *sqlite3_column_text(sqlite3_stmt*, int column)} */
    @Bridge(symbol = "sqlite3_column_text")
    public static native String columnString(@Pointer long statement, int column);
}
