package org.clyze.doop.common;

import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.Flushable;
import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public class Database implements Closeable, Flushable {
    private static final char SEP = '\t';
    private static final char EOL = '\n';
    private static final int SQLITE_COMMIT_INTERVAL = 10_000;
    private static final String SQLITE_ENV = "DOOP_FACTS_SQLITE";
    private static final String DATABASE_ONLY_ENV = "DOOP_DATABASE_ONLY";
    /** Default name used when the caller enables the SQLite facts backend. */
    private static final String DEFAULT_SQLITE_NAME = "DoopFacts.sqlite";

    private final Map<String, Writer> _writers;
    private final Connection _connection;
    private final Map<String, PreparedStatement> _statements;
    private final Map<String, Integer> _arities;
    private final Object _writeLock = new Object();
    private final String directory;
    private int _pendingRows;
    private boolean _closed;

    /**
     * Generate a database object, which can be used to write facts.
     *
     * @param directory     the output directory
     */
    public Database(String directory) throws IOException {
        this(directory, true);
    }

    /**
     * Generate a database object, which can be used to write facts.
     *
     * @param directory     the output directory
     * @param initWriters   if false, no facts can be written (dummy database)
     */
    public Database(String directory, boolean initWriters) throws IOException {
        this.directory = directory;

        if (!initWriters) {
            this._writers = null;
            this._connection = null;
            this._statements = null;
            this._arities = null;
            return;
        }

        File sqliteFile = sqliteFactsFile(directory);
        if (sqliteFile == null) {
            this._connection = null;
            this._statements = null;
            this._arities = null;
            this._writers = new HashMap<>();
            for (PredicateFile predicateFile : EnumSet.allOf(PredicateFile.class))
                _writers.put(predicateFile.toString(), predicateFile.getWriter(new File(directory), ".facts"));
        } else {
            this._writers = null;
            this._statements = new HashMap<>();
            this._arities = new HashMap<>();
            this._connection = openSQLite(sqliteFile);
        }
    }

    public static boolean isSQLiteFactsEnabled() {
        String value = System.getenv(SQLITE_ENV);
        return value != null && !value.trim().isEmpty() &&
                !"0".equals(value.trim()) && !"false".equalsIgnoreCase(value.trim());
    }

    public static boolean isDatabaseOnly() {
        String value = System.getenv(DATABASE_ONLY_ENV);
        return value != null && !value.trim().isEmpty() &&
                !"0".equals(value.trim()) && !"false".equalsIgnoreCase(value.trim());
    }

    /**
     * Resolve the configured SQLite file. The values "1" and "true" select
     * a database in the current facts directory, which also works when facts
     * are copied for an --input-id analysis. Other relative values are resolved
     * against the facts directory.
     */
    public static File sqliteFactsFile(String directory) {
        String value = System.getenv(SQLITE_ENV);
        if (value == null || value.trim().isEmpty() ||
                "0".equals(value.trim()) || "false".equalsIgnoreCase(value.trim()))
            return null;
        value = value.trim();
        if ("1".equals(value) || "true".equalsIgnoreCase(value))
            return new File(directory, DEFAULT_SQLITE_NAME).getAbsoluteFile();
        File configured = new File(value);
        return configured.isAbsolute() ? configured : new File(directory, value).getAbsoluteFile();
    }

    /**
     * Remove tuples from a previously initialized SQLite facts database.
     * This is used when Doop retries fact generation: the database file must
     * survive directory setup so a caller can pre-create empty input schemas,
     * but no rows from the failed run may leak into the new one.
     */
    public static void resetSQLiteFacts(String directory) throws IOException {
        File sqliteFile = sqliteFactsFile(directory);
        if (sqliteFile == null || !sqliteFile.isFile())
            return;
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getAbsolutePath())) {
                connection.setAutoCommit(false);
                java.util.ArrayList<String> names = new java.util.ArrayList<>();
                try (Statement query = connection.createStatement();
                     ResultSet tables = query.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                    while (tables.next())
                        names.add(tables.getString(1));
                }
                for (String name : names)
                    try (Statement clear = connection.createStatement()) {
                        clear.executeUpdate("DELETE FROM " + quoteIdentifier(name));
                    }
                connection.commit();
            }
        } catch (ClassNotFoundException | SQLException exc) {
            throw new IOException("Could not reset SQLite facts database " + sqliteFile + ": " + exc.getMessage(), exc);
        }
    }

    private static Connection openSQLite(File sqliteFile) throws IOException {
        File parent = sqliteFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
            throw new IOException("Could not create SQLite facts directory: " + parent);

        Connection connection = null;
        try {
            // Fat JAR assembly does not preserve every service descriptor.
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=30000");
                // DELETE keeps the facts directory self-contained.  WAL would
                // leave -wal/-shm sidecars that are easy to miss when facts are
                // copied for a second analysis.
                statement.execute("PRAGMA journal_mode=DELETE");
                statement.execute("PRAGMA synchronous=NORMAL");
            }
            connection.setAutoCommit(false);
            return connection;
        } catch (ClassNotFoundException | SQLException exc) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException suppressed) {
                    exc.addSuppressed(suppressed);
                }
            }
            throw new IOException("Could not open SQLite facts database " + sqliteFile + ": " + exc.getMessage(), exc);
        }
    }

    public String getDirectory() {
        return directory;
    }

    /** Ensure that a relation exists even when it has no rows. */
    public void ensureTable(String relation, int arity) throws IOException {
        validateRelation(relation);
        if (arity < 1)
            throw new IllegalArgumentException("Fact relation arity must be positive: " + arity);
        if (_writers == null && _connection == null)
            return;

        synchronized (_writeLock) {
            ensureOpen();
            if (_connection != null) {
                try {
                    ensureSQLiteTable(relation, arity);
                } catch (SQLException exc) {
                    throw sqlIOException("Could not initialize fact relation " + relation, exc);
                }
            } else if (!_writers.containsKey(relation)) {
                File factsFile = new File(directory, relation + ".facts");
                File parent = factsFile.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
                    throw new IOException("Could not create facts directory: " + parent);
                _writers.put(relation, new FileWriter(factsFile, true));
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (_writeLock) {
            if (_closed)
                return;
            IOException failure = null;
            if (_connection != null) {
                try {
                    _connection.commit();
                } catch (SQLException exc) {
                    failure = sqlIOException("Could not commit SQLite facts", exc);
                }
                for (PreparedStatement statement : _statements.values()) {
                    try {
                        statement.close();
                    } catch (SQLException exc) {
                        failure = appendFailure(failure, sqlIOException("Could not close SQLite fact statement", exc));
                    }
                }
                try {
                    _connection.close();
                } catch (SQLException exc) {
                    failure = appendFailure(failure, sqlIOException("Could not close SQLite facts", exc));
                }
            } else if (_writers != null) {
                for (Writer writer : _writers.values()) {
                    try {
                        writer.close();
                    } catch (IOException exc) {
                        failure = appendFailure(failure, exc);
                    }
                }
            }
            _closed = true;
            if (failure != null)
                throw failure;
        }
    }

    @Override
    public void flush() throws IOException {
        synchronized (_writeLock) {
            if (_writers == null && _connection == null)
                return;
            ensureOpen();
            if (_connection != null) {
                try {
                    _connection.commit();
                    _pendingRows = 0;
                } catch (SQLException exc) {
                    throw sqlIOException("Could not flush SQLite facts", exc);
                }
            } else {
                for (Writer writer : _writers.values())
                    writer.flush();
            }
        }
    }

    private String addColumn(String column) {
        // Quote some special characters.
        final char SLASH = '"';
        final char EOL = '\n';
        final char TAB = '\t';
        if ((column.indexOf(SLASH) >= 0) ||
            (column.indexOf(EOL)   >= 0) ||
            (column.indexOf(TAB)   >= 0)) {
            // Assume at most 5 special characters will be rewritten
            // before updating the capacity.
            StringBuilder sb = new StringBuilder(column.length() + 5);
            for (char c : column.toCharArray())
                switch (c) {
                case SLASH:
                    sb.append("\\\\\"");
                    break;
                case EOL:
                    sb.append("\\\\n");
                    break;
                case TAB:
                    sb.append("\\\\t");
                    break;
                default:
                    sb.append(c);
                }
            return sb.toString();
        } else
            return column;
    }

    public void add(PredicateFile predicateFile, String arg, String... args) {
        String[] columns = new String[args.length + 1];
        columns[0] = arg;
        System.arraycopy(args, 0, columns, 1, args.length);
        add(predicateFile.toString(), columns);
    }

    /** Write one tuple to a relation not represented by {@link PredicateFile}. */
    public void add(String relation, String... columns) {
        if (_writers == null && _connection == null)
            return;
        validateRelation(relation);
        if (columns.length < 1)
            throw new IllegalArgumentException("Fact tuple must contain at least one column");

        synchronized (_writeLock) {
            ensureOpenUnchecked();
            try {
                if (_connection != null) {
                    PreparedStatement statement = sqliteStatement(relation, columns.length);
                    for (int index = 0; index < columns.length; index++) {
                        if (columns[index] == null)
                            throw new IllegalArgumentException("Fact tuple contains a null column");
                        // SQLite stores the logical value directly.  Escaping
                        // is only needed by the legacy tab-separated writer.
                        statement.setString(index + 1, columns[index]);
                    }
                    statement.executeUpdate();
                    if (++_pendingRows >= SQLITE_COMMIT_INTERVAL) {
                        _connection.commit();
                        _pendingRows = 0;
                    }
                } else {
                    ensureTable(relation, columns.length);
                    StringBuilder line = new StringBuilder();
                    for (int index = 0; index < columns.length; index++) {
                        if (index != 0)
                            line.append(SEP);
                        line.append(addColumn(columns[index]));
                    }
                    line.append(EOL);
                    _writers.get(relation).write(line.toString());
                }
            } catch (IOException | SQLException exc) {
                throw new RuntimeException("Could not write fact relation " + relation + ": " + exc.getMessage(), exc);
            }
        }
    }

    private PreparedStatement sqliteStatement(String relation, int arity) throws SQLException {
        ensureSQLiteTable(relation, arity);
        PreparedStatement statement = _statements.get(relation);
        if (statement == null) {
            StringBuilder sql = new StringBuilder("INSERT INTO ")
                    .append(quoteIdentifier(relation)).append(" VALUES (");
            for (int index = 0; index < arity; index++) {
                if (index != 0)
                    sql.append(',');
                sql.append('?');
            }
            statement = _connection.prepareStatement(sql.append(')').toString());
            _statements.put(relation, statement);
        }
        return statement;
    }

    private void ensureSQLiteTable(String relation, int arity) throws SQLException {
        Integer knownArity = _arities.get(relation);
        if (knownArity != null) {
            requireArity(relation, knownArity, arity);
            return;
        }

        String type = null;
        try (PreparedStatement query = _connection.prepareStatement(
                "SELECT type FROM sqlite_master WHERE name = ? AND type IN ('table', 'view')")) {
            query.setString(1, relation);
            try (ResultSet result = query.executeQuery()) {
                if (result.next())
                    type = result.getString(1);
            }
        }
        if ("view".equals(type))
            throw new SQLException("Fact relation is not writable because it is a view: " + relation);
        if (type == null) {
            StringBuilder create = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                    .append(quoteIdentifier(relation)).append(" (");
            for (int index = 0; index < arity; index++) {
                if (index != 0)
                    create.append(',');
                create.append(quoteIdentifier("c" + index)).append(" TEXT NOT NULL");
            }
            try (Statement statement = _connection.createStatement()) {
                statement.execute(create.append(')').toString());
            }
        }

        try (Statement statement = _connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM " + quoteIdentifier(relation) + " LIMIT 0")) {
            ResultSetMetaData metadata = result.getMetaData();
            int actualArity = metadata.getColumnCount();
            requireArity(relation, actualArity, arity);
            for (int index = 0; index < actualArity; index++) {
                String expected = "c" + index;
                if (!expected.equals(metadata.getColumnLabel(index + 1)))
                    throw new SQLException("Fact relation " + relation + " column " + (index + 1) +
                            " is named " + metadata.getColumnLabel(index + 1) + "; expected " + expected);
            }
            _arities.put(relation, actualArity);
        }
    }

    private static void requireArity(String relation, int actual, int expected) throws SQLException {
        if (actual != expected)
            throw new SQLException("Fact relation " + relation + " has " + actual +
                    " columns; attempted to write " + expected);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static void validateRelation(String relation) {
        if (relation == null || !relation.matches("[A-Za-z_][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("Invalid fact relation name: " + relation);
    }

    private void ensureOpen() throws IOException {
        if (_closed)
            throw new IOException("Fact database is closed");
    }

    private void ensureOpenUnchecked() {
        if (_closed)
            throw new IllegalStateException("Fact database is closed");
    }

    private static IOException sqlIOException(String message, SQLException exc) {
        return new IOException(message + ": " + exc.getMessage(), exc);
    }

    private static IOException appendFailure(IOException failure, IOException next) {
        if (failure == null)
            return next;
        failure.addSuppressed(next);
        return failure;
    }
}
