package ro.sellfluence.app;

import ch.claudio.db.DBPass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RefreshLocalDBTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsANonlocalTargetBeforeOpeningConnectionsOrCreatingBackups() {
        var source = configuration("emagRemote", "source.example.invalid", "remote");
        var target = configuration("emagLocal", "target.example.invalid", "local");

        assertRejectedBeforeSideEffects(source, target);
    }

    @Test
    void rejectsTheSameSourceAndTargetDatabase() {
        var source = configuration("emagRemote", "127.0.0.1", "same");
        var target = configuration("emagLocal", "127.0.0.1", "same");

        assertRejectedBeforeSideEffects(source, target);
    }

    @Test
    void rejectsConnectionOptionsAndUnexpectedAliases() {
        var source = configuration("emagRemote", "source.example.invalid", "remote");
        var target = configuration("emagLocal", "127.0.0.1", "local");

        assertRejectedBeforeSideEffects(source,
                new DBPass(target.alias(), target.connect() + "?currentSchema=public", target.user(), target.pw()));
        assertRejectedBeforeSideEffects(new DBPass("other", source.connect(), source.user(), source.pw()), target);
    }

    @Test
    void rejectsEncodedDatabaseNamesThatCouldBecomeNativeConnectionOptions() {
        var source = configuration("emagRemote", "source.example.invalid", "remote");
        var target = configuration("emagLocal", "127.0.0.1", "local");

        assertRejectedBeforeSideEffects(new DBPass(source.alias(),
                "jdbc:postgresql://source.example.invalid:5432/host%3Dremote.example.invalid",
                source.user(), source.pw()), target);
        assertRejectedBeforeSideEffects(source, new DBPass(target.alias(),
                "jdbc:postgresql://127.0.0.1:5432/host%3Dremote.example.invalid", target.user(), target.pw()));
    }

    private void assertRejectedBeforeSideEffects(DBPass source, DBPass target) {
        var backup = temporary.resolve("not-created");
        assertThrows(IllegalArgumentException.class,
                () -> RefreshLocalDB.refresh(source, target, temporary.resolve("missing-tools"), backup));
        assertFalse(Files.exists(backup), "Invalid endpoints must be rejected before creating any backup directory");
    }

    private static DBPass configuration(String alias, String host, String database) {
        return new DBPass(alias, "jdbc:postgresql://" + host + ":5432/" + database, "fixture", "fixture");
    }

    /** These tests destroy public only in two explicitly configured local refresh_test databases. */
    @Nested
    class NativeRefresh {
        private Connection sourceConnection;
        private Connection targetConnection;
        private DBPass source;
        private DBPass target;
        private Path pgBin;
        private Path backups;

        @BeforeEach
        void createDisposableDatabases() throws Exception {
            var sourceUrl = System.getenv("REFRESH_TEST_SOURCE_URL");
            var targetUrl = System.getenv("REFRESH_TEST_TARGET_URL");
            var binaries = System.getenv("REFRESH_TEST_PG_BIN");
            assumeTrue(sourceUrl != null && targetUrl != null && binaries != null,
                    "REFRESH_TEST_SOURCE_URL, REFRESH_TEST_TARGET_URL and REFRESH_TEST_PG_BIN are required");
            requireDisposableFixture(sourceUrl);
            requireDisposableFixture(targetUrl);
            assertFalse(sourceUrl.equals(targetUrl), "Source and target fixture databases must be different");
            assertFalse(URI.create(sourceUrl.substring(5)).getPath()
                    .equals(URI.create(targetUrl.substring(5)).getPath()),
                    "Use two differently named disposable fixture databases");
            pgBin = Path.of(binaries);
            for (var command : List.of("pg_dump", "pg_restore", "psql")) {
                assertTrue(Files.isExecutable(pgBin.resolve(command)), "Missing PostgreSQL tool: " + command);
            }
            var user = System.getenv("REFRESH_TEST_DB_USER");
            var password = System.getenv("REFRESH_TEST_DB_PASSWORD");
            source = new DBPass("emagRemote", sourceUrl, user, password);
            target = new DBPass("emagLocal", targetUrl, user, password);
            sourceConnection = connect(source);
            targetConnection = connect(target);
            initializeFixture(sourceConnection, false);
            initializeFixture(targetConnection, true);
            backups = temporary.resolve("backups");
        }

        @AfterEach
        void removeDisposableSchemas() throws SQLException {
            try {
                cleanAndClose(sourceConnection);
            } finally {
                cleanAndClose(targetConnection);
            }
        }

        @Test
        void replacesBusinessDataAndPreservesEveryLocalPasskeyFieldAndSequence() throws Exception {
            execute(targetConnection, "CREATE VIEW stale_local_view AS SELECT * FROM business_records");
            var localAuth = authRows(targetConnection);
            var localSequences = authSequences(targetConnection);
            var remoteBefore = snapshot(sourceConnection);

            var backup = refresh(pgBin);

            assertNotNull(backup);
            assertTrue(Files.exists(backup));
            assertTrue(hasBackup("local-before.dump"));
            assertEquals(List.of("7:remote business"), businessRows(targetConnection));
            assertNull(scalar(targetConnection, "SELECT to_regclass('public.stale_local_view')::text"));
            assertEquals(localAuth, authRows(targetConnection));
            assertEquals(localSequences, authSequences(targetConnection));
            assertEquals("0", scalar(targetConnection, "SELECT count(*) FROM webauthn_challenge"));
            assertEquals("0", scalar(targetConnection, "SELECT count(*) FROM app_user WHERE username='remote-user'"));
            assertEquals("501", scalar(targetConnection, "SELECT nextval('app_user_id_seq')"));
            assertEquals("700", scalar(targetConnection, "SELECT nextval('webauthn_credential_id_seq')"));
            assertEquals(remoteBefore, snapshot(sourceConnection), "Refresh must not modify its source");
        }

        @Test
        void repeatedRefreshUsesTheNewRemoteSnapshotAndKeepsNewLocalPasskeys() throws Exception {
            refresh(pgBin);
            execute(targetConnection, """
                    INSERT INTO app_user(username, user_handle, role)
                    VALUES ('later-local-user', decode('112233', 'hex'), 'user');
                    INSERT INTO webauthn_credential(user_id, credential_id, public_key_cose, sign_count, label)
                    SELECT id, decode('445566', 'hex'), decode('778899', 'hex'), 909, 'New local passkey'
                    FROM app_user WHERE username='later-local-user';
                    """);
            execute(sourceConnection, "UPDATE business_records SET payload='updated remote business' WHERE id=7");
            var localAuth = authRows(targetConnection);
            var localSequences = authSequences(targetConnection);

            refresh(pgBin);

            assertEquals(List.of("7:updated remote business"), businessRows(targetConnection));
            assertEquals(localAuth, authRows(targetConnection));
            assertEquals(localSequences, authSequences(targetConnection));
            assertEquals("0", scalar(targetConnection, "SELECT count(*) FROM webauthn_challenge"));
        }

        @Test
        void populatesDependentMaterializedViewsAfterAnalyzingTheirRestoredInputs() throws Exception {
            createMaterializedViews(sourceConnection);
            createMaterializedViews(targetConnection);
            assertEquals(List.of("7:remote business:remote-user"),
                    rows(sourceConnection, "SELECT id::text || ':' || payload || ':' || username FROM second_summary"));

            refresh(pgBin);

            assertEquals(List.of("7:remote business"),
                    rows(targetConnection, "SELECT id::text || ':' || payload FROM first_summary"));
            assertEquals(List.of("7:remote business:local-admin"),
                    rows(targetConnection, "SELECT id::text || ':' || payload || ':' || username FROM second_summary"));
            assertEquals("2", scalar(targetConnection, """
                    SELECT count(*) FROM pg_matviews
                    WHERE schemaname='public' AND matviewname IN ('first_summary', 'second_summary') AND ispopulated
                    """));
            for (var relation : List.of("business_records", "first_summary", "second_summary")) {
                assertEquals("true", scalar(targetConnection,
                        "SELECT require_analyzed_relation('public." + relation + "'::regclass)::text"));
            }
        }

        @Test
        void aFailedRemoteDumpLeavesAllLocalDataAndSequencesUntouched() throws Exception {
            var before = snapshot(targetConnection);
            var failingTools = toolsWithWrapper("pg_dump", """
                    if any(Path(arg).name == 'remote.dump' for arg in args):
                        injected.write_text('remote dump failed')
                        sys.stderr.write('Simulated remote dump failure\\n')
                        sys.exit(72)
                    """);

            assertThrows(Exception.class, () -> refresh(failingTools));

            assertTrue(Files.exists(failingTools.resolve("injected")), "The remote dump stage must have been reached");
            assertEquals(before, snapshot(targetConnection));
        }

        @Test
        void aRestoreErrorRollsBackBusinessDataPasskeysChallengesAndSequenceState() throws Exception {
            var before = snapshot(targetConnection);
            var failingTools = toolsWithWrapper("pg_restore", """
                    if args and Path(args[-1]).name == 'remote.dump' and '--file' in args:
                        result = subprocess.run([real, *args])
                        if result.returncode:
                            sys.exit(result.returncode)
                        output = Path(args[args.index('--file') + 1])
                        with output.open('a') as stream:
                            stream.write('\\nSELECT 1 / 0;\\n')
                        injected.write_text('remote SQL restore failed')
                        sys.exit(0)
                    """);

            assertThrows(Exception.class, () -> refresh(failingTools));

            assertTrue(Files.exists(failingTools.resolve("injected")), "A SQL error must have been injected after dump rendering");
            assertEquals(before, snapshot(targetConnection));
            assertTrue(hasBackup("local-before.dump"));
        }

        @Test
        void incompatibleSchemasAreRejectedWithoutReplacingTheLocalDatabase() throws Exception {
            execute(sourceConnection, "ALTER TABLE business_records ADD COLUMN unexpected_remote_column TEXT");
            var before = snapshot(targetConnection);

            assertThrows(Exception.class, () -> refresh(pgBin));

            assertEquals(before, snapshot(targetConnection));
        }

        @Test
        void incompatibleVersionsAreRejectedWithoutReplacingTheLocalDatabase() throws Exception {
            execute(sourceConnection, "UPDATE version_info SET version=47");
            var before = snapshot(targetConnection);

            assertThrows(Exception.class, () -> refresh(pgBin));

            assertEquals(before, snapshot(targetConnection));
        }

        @Test
        void anOpenLocalApplicationConnectionPreventsReplacement() throws Exception {
            var before = snapshot(targetConnection);

            var failure = assertThrows(IllegalStateException.class,
                    () -> RefreshLocalDB.refresh(source, target, pgBin, backups));

            assertEquals("Other local database sessions are active; stop the application first", failure.getMessage());
            assertEquals(before, snapshot(targetConnection));
        }

        private Path refresh(Path tools) throws Exception {
            targetConnection.close();
            targetConnection = null;
            try {
                return RefreshLocalDB.refresh(source, target, tools, backups);
            } finally {
                targetConnection = connect(target);
            }
        }

        private Path toolsWithWrapper(String command, String injectedLogic) throws Exception {
            var directory = Files.createDirectory(temporary.resolve("tools-" + command));
            for (var tool : List.of("pg_dump", "pg_restore", "psql")) {
                if (!tool.equals(command)) Files.createSymbolicLink(directory.resolve(tool), pgBin.resolve(tool));
            }
            var real = pgBin.resolve(command).toRealPath().toString();
            var wrapper = "#!/usr/bin/env python3\nimport subprocess, sys\nfrom pathlib import Path\n"
                    + "real = " + pythonString(real) + "\nargs = sys.argv[1:]\n"
                    + "injected = Path(" + pythonString(directory.resolve("injected").toString()) + ")\n"
                    + injectedLogic + "sys.exit(subprocess.run([real, *args]).returncode)\n";
            var executable = Files.writeString(directory.resolve(command), wrapper);
            assertTrue(executable.toFile().setExecutable(true, true));
            return directory;
        }

        private boolean hasBackup(String name) throws Exception {
            if (!Files.exists(backups)) return false;
            try (var paths = Files.walk(backups)) {
                return paths.anyMatch(path -> path.getFileName().toString().equals(name) && Files.isRegularFile(path));
            }
        }
    }

    private static void requireDisposableFixture(String url) {
        assertTrue(url.startsWith("jdbc:postgresql://"));
        var uri = URI.create(url.substring(5));
        assertTrue(Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost()),
                "Refresh integration fixtures must be local");
        assertTrue(uri.getPath().substring(1).contains("refresh_test"),
                "Fixture database names must contain refresh_test; public will be recreated");
        assertTrue(uri.getQuery() == null && uri.getFragment() == null && uri.getUserInfo() == null);
    }

    private static Connection connect(DBPass database) throws SQLException {
        var properties = new Properties();
        if (database.user() != null) properties.setProperty("user", database.user());
        if (database.pw() != null) properties.setProperty("password", database.pw());
        return DriverManager.getConnection(database.connect(), properties);
    }

    private static void initializeFixture(Connection connection, boolean local) throws Exception {
        resetPublic(connection);
        execute(connection, """
                CREATE TABLE version_info(version INTEGER PRIMARY KEY, date TIMESTAMPTZ NOT NULL);
                INSERT INTO version_info VALUES (46, TIMESTAMPTZ '2026-10-28 10:00:00+00');
                CREATE TABLE business_records(id INTEGER PRIMARY KEY, payload TEXT NOT NULL);
                """);
        var migration = Class.forName("ro.sellfluence.db.versions.EmagMirrorDBVersion30")
                .getDeclaredMethod("version30", Connection.class);
        migration.setAccessible(true);
        migration.invoke(null, connection);
        if (local) {
            execute(connection, """
                    INSERT INTO business_records VALUES (99, 'old local business');
                    INSERT INTO app_user(id, username, user_handle, created_at, updated_at, role) VALUES
                        (41, 'local-admin', decode('010203', 'hex'), '2026-01-01 00:00:00+00', '2026-02-01 00:00:00+00', 'admin'),
                        (42, 'local-pending', decode('040506', 'hex'), '2026-01-02 00:00:00+00', '2026-02-02 00:00:00+00', NULL);
                    INSERT INTO webauthn_credential(id, user_id, credential_id, public_key_cose, sign_count,
                        aaguid, transports, is_discoverable, backup_eligible, backup_state, label,
                        is_revoked, created_at, last_used_at)
                    VALUES (71, 41, decode('aabbcc', 'hex'), decode('ddeeff', 'hex'), 77,
                        '00112233-4455-6677-8899-aabbccddeeff', ARRAY['internal','usb'], false,
                        true, false, 'Local key label', true, '2026-01-03 00:00:00+00', '2026-02-03 00:00:00+00');
                    SELECT setval('app_user_id_seq', 500, true);
                    SELECT setval('webauthn_credential_id_seq', 700, false);
                    """);
        } else {
            execute(connection, """
                    INSERT INTO business_records VALUES (7, 'remote business');
                    INSERT INTO app_user(id, username, user_handle, role)
                    VALUES (41, 'remote-user', decode('998877', 'hex'), 'user');
                    INSERT INTO webauthn_credential(id, user_id, credential_id, public_key_cose, sign_count)
                    VALUES (71, 41, decode('665544', 'hex'), decode('332211', 'hex'), 9999);
                    SELECT setval('app_user_id_seq', 9000, true);
                    SELECT setval('webauthn_credential_id_seq', 9500, true);
                    """);
        }
        execute(connection, """
                INSERT INTO webauthn_challenge(flow, user_id, rp_id, origin, options_json, expires_at)
                VALUES ('authentication', 41, 'localhost', 'https://localhost', '{"challenge":"stale"}', now()+INTERVAL '1 day');
                """);
    }

    private static void createMaterializedViews(Connection connection) throws SQLException {
        execute(connection, """
                CREATE FUNCTION public.require_analyzed_relation(relation REGCLASS) RETURNS BOOLEAN
                LANGUAGE plpgsql AS $guard$
                DECLARE
                    relation_schema TEXT;
                    relation_name TEXT;
                    estimated_rows REAL;
                BEGIN
                    SELECT n.nspname, c.relname, c.reltuples
                    INTO relation_schema, relation_name, estimated_rows
                    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.oid=relation;
                    IF estimated_rows < 0 OR NOT EXISTS (
                        SELECT 1 FROM pg_stats WHERE schemaname=relation_schema AND tablename=relation_name
                    ) THEN
                        RAISE EXCEPTION 'Materialized view input % has not been analyzed', relation;
                    END IF;
                    RETURN TRUE;
                END;
                $guard$;
                CREATE MATERIALIZED VIEW public.first_summary AS
                SELECT id, payload, public.require_analyzed_relation('public.business_records'::regclass) AS base_analyzed
                FROM public.business_records WITH NO DATA;
                CREATE MATERIALIZED VIEW public.second_summary AS
                SELECT v.id, v.payload, u.username,
                    public.require_analyzed_relation('public.first_summary'::regclass) AS dependency_analyzed
                FROM public.first_summary v CROSS JOIN public.app_user u WHERE u.id=41 WITH NO DATA;
                ANALYZE public.business_records;
                REFRESH MATERIALIZED VIEW public.first_summary;
                ANALYZE public.first_summary;
                REFRESH MATERIALIZED VIEW public.second_summary;
                ANALYZE public.second_summary;
                """);
    }

    private static List<String> authRows(Connection connection) throws SQLException {
        var result = new ArrayList<String>();
        for (var table : List.of("app_user", "webauthn_credential")) {
            result.addAll(rows(connection, "SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY id"));
        }
        return result;
    }

    private static List<String> authSequences(Connection connection) throws SQLException {
        var result = new ArrayList<String>();
        for (var sequence : List.of("app_user_id_seq", "webauthn_credential_id_seq")) {
            result.add(scalar(connection, "SELECT last_value::text || ':' || is_called::text FROM " + sequence));
        }
        return result;
    }

    private static List<String> businessRows(Connection connection) throws SQLException {
        return rows(connection, "SELECT id::text || ':' || payload FROM business_records ORDER BY id");
    }

    private static List<String> snapshot(Connection connection) throws SQLException {
        var result = new ArrayList<>(authRows(connection));
        result.addAll(authSequences(connection));
        result.addAll(businessRows(connection));
        result.addAll(rows(connection, "SELECT to_jsonb(t)::text FROM webauthn_challenge t ORDER BY id"));
        result.add(scalar(connection,
                "SELECT last_value::text || ':' || is_called::text FROM webauthn_challenge_id_seq"));
        result.addAll(rows(connection, "SELECT to_jsonb(t)::text FROM version_info t ORDER BY version"));
        return result;
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            var rows = new ArrayList<String>();
            while (result.next()) rows.add(result.getString(1));
            return rows;
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        var values = rows(connection, sql);
        assertEquals(1, values.size());
        return values.getFirst();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void resetPublic(Connection connection) throws SQLException {
        execute(connection, "DROP SCHEMA public CASCADE; CREATE SCHEMA public");
    }

    private static void cleanAndClose(Connection connection) throws SQLException {
        if (connection != null) {
            try (connection) {
                resetPublic(connection);
            }
        }
    }

    private static String pythonString(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
