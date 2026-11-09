package ro.sellfluence.app;

import ch.claudio.db.DBPass;
import ro.sellfluence.support.Arguments;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static ro.sellfluence.support.UsefulMethods.homeDirectory;

/** Replaces local business data with a remote snapshot while preserving local users and passkeys. */
public class RefreshLocalDB {
    private static final String SOURCE_ALIAS = "emagRemote";
    private static final String TARGET_ALIAS = "emagLocal";
    private static final List<String> AUTH_TABLES = List.of("app_user", "webauthn_credential", "webauthn_challenge");
    private static final List<String> SAVED_AUTH = List.of("app_user", "webauthn_credential",
            "app_user_id_seq", "webauthn_credential_id_seq");
    private static final List<String> TOOLS = List.of("pg_dump", "pg_restore", "psql");
    private static final Pattern TOOL_VERSION = Pattern.compile("\\(PostgreSQL\\) (\\d+)");
    private static final Pattern TOC_ENTRY = Pattern.compile("^\\d+;\\s+\\d+\\s+\\d+\\s+.+$");
    private static final Pattern MATERIALIZED_VIEW_DATA = Pattern.compile(
            "^\\d+;\\s+\\d+\\s+\\d+\\s+MATERIALIZED VIEW DATA\\s+.+$");
    static final String OTHER_CLIENT_SESSIONS_CONDITION = """
            datname = current_database() AND pid <> pg_backend_pid()
                AND backend_type = 'client backend'
            """;
    private static final String ACTIVE_CLIENTS_MESSAGE =
            "Other local client connections are active; stop Server and disconnect database clients first";
    private static final String TABLES_QUERY = """
            SELECT c.relname FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY c.relname COLLATE "C"
            """;
    private static final String LAYOUT_QUERY = """
            SELECT md5(COALESCE(jsonb_agg(jsonb_build_array(c.relname, a.attname,
                tn.nspname, t.typname, a.atttypmod, a.attnotnull, a.attidentity, a.attgenerated)
                ORDER BY c.relname COLLATE "C", a.attnum)::text, ''))
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
            JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
            JOIN pg_catalog.pg_namespace tn ON tn.oid = t.typnamespace
            WHERE n.nspname = 'public' AND c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
            """;

    public static void main(String[] args) throws Exception {
        var arguments = new Arguments(args);
        for (String argument : args) {
            if (!argument.equals("--help") && !argument.startsWith("--pg-bin=")
                    && !argument.startsWith("--backup-dir=")) {
                throw new IllegalArgumentException("Supported options: --pg-bin=PATH, --backup-dir=PATH, --help");
            }
        }
        if (arguments.hasFlag("help")) {
            System.out.println("Refresh emagLocal from emagRemote, preserving local users and passkeys.");
            System.out.println("Stop the local application first. Options: --pg-bin=PATH --backup-dir=PATH");
            return;
        }
        for (String option : List.of("pg-bin", "backup-dir")) {
            if (arguments.hasOption(option) && arguments.getOption(option).isBlank()) {
                throw new IllegalArgumentException("--" + option + " requires a nonblank path");
            }
        }
        Map<String, DBPass> aliases = new HashMap<>();
        for (var entry : DBPass.getAll()) {
            if (aliases.putIfAbsent(entry.alias(), entry) != null) {
                throw new IllegalArgumentException("Duplicate database alias: " + entry.alias());
            }
        }
        var source = aliases.get(SOURCE_ALIAS);
        var target = aliases.get(TARGET_ALIAS);
        if (source == null || target == null) {
            throw new IllegalArgumentException("Both emagRemote and emagLocal must exist in ~/Secrets/dbpass.txt");
        }
        Path pgBin = arguments.hasOption("pg-bin") ? Paths.get(arguments.getOption("pg-bin")) : null;
        Path backups = arguments.hasOption("backup-dir") ? Paths.get(arguments.getOption("backup-dir"))
                : homeDirectory().resolve("Backups").resolve("postgres").resolve("refresh-local");
        Path result = refresh(source, target, pgBin, backups);
        System.out.println("Local refresh complete. Backups and logs: " + result);
        System.out.println("Restart the local application and sign in with your existing local passkey.");
    }

    static Path refresh(DBPass source, DBPass target, Path pgBin, Path backupDir) throws Exception {
        var remote = endpoint(source, SOURCE_ALIAS);
        var local = endpoint(target, TARGET_ALIAS);
        if (!isLoopback(local.host())) {
            throw new IllegalArgumentException("emagLocal must connect to a loopback host");
        }
        if (sameDatabase(remote, local)) {
            throw new IllegalArgumentException("Source and target must be different databases");
        }
        Path binaries = findTools(pgBin);
        String lockKey = Integer.toUnsignedString((local.port() + "/" + local.database()).hashCode(), 16);
        Path lockPath = Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve("supplierDB-refresh-" + lockKey + ".lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Refresh lock must not be a symbolic link");
        try (var channel = FileChannel.open(lockPath,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            final java.nio.channels.FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw new IllegalStateException("A refresh for this local database is already running", e);
            }
            if (lock == null) throw new IllegalStateException("A refresh for this local database is already running");
            try (lock) {
                return refreshLocked(remote, local, binaries, backupDir.toAbsolutePath().normalize());
            }
        }
    }

    private static Path refreshLocked(Endpoint remote, Endpoint local, Path binaries, Path backupDir) throws Exception {
        System.out.println("Checking remote and local database layouts (read-only).");
        Snapshot source = inspect(remote, false);
        Snapshot target = inspect(local, true);
        if (source.version() != target.version() || !source.tables().equals(target.tables())
                || !source.layout().equals(target.layout())) {
            throw new IllegalArgumentException("Source and target must have matching schema versions and table layouts");
        }
        Files.createDirectories(backupDir);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        Path run = Files.createTempDirectory(backupDir, "refresh_" + timestamp + "_",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        int requiredVersion = Math.max(source.serverVersion(), target.serverVersion());
        for (String tool : TOOLS) {
            Path log = runStage(run, "check-" + tool, List.of(binaries.resolve(tool).toString(), "--version"), null, false);
            var matcher = TOOL_VERSION.matcher(Files.readString(log));
            if (!matcher.find() || Integer.parseInt(matcher.group(1)) < requiredVersion) {
                throw new IllegalArgumentException(tool + " must support PostgreSQL " + requiredVersion
                        + "; choose a newer directory with --pg-bin. Log: " + log);
            }
        }

        Path remoteDump = privateFile(run.resolve("remote.dump"));
        var dump = command(binaries, "pg_dump", remote, "--format=custom", "--file", remoteDump.toString());
        for (String table : AUTH_TABLES) dump.add("--exclude-table-data=public." + table);
        runStage(run, "dump-remote", dump, remote, true);
        Path remoteToc = validateArchive(run, binaries, remoteDump, "validate-remote");

        Path localDump = privateFile(run.resolve("local-before.dump"));
        runStage(run, "backup-local", command(binaries, "pg_dump", local,
                "--format=custom", "--file", localDump.toString()), local, false);
        validateArchive(run, binaries, localDump, "validate-local-backup");

        Path authDump = privateFile(run.resolve("local-auth.dump"));
        var auth = command(binaries, "pg_dump", local, "--format=custom", "--data-only", "--strict-names",
                "--file", authDump.toString());
        for (String table : SAVED_AUTH) auth.add("--table=public." + table);
        runStage(run, "backup-local-auth", auth, local, false);
        validateArchive(run, binaries, authDump, "validate-local-auth");
        for (Path archive : List.of(remoteDump, localDump, authDump)) {
            Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("r--------"));
        }

        var restoreLists = splitMaterializedViews(run, remoteToc);
        var generated = new ArrayList<Path>(restoreLists);
        Path remoteSql = privateFile(run.resolve("remote.sql"));
        generated.add(remoteSql);
        runStage(run, "render-remote", List.of(binaries.resolve("pg_restore").toString(),
                "--clean", "--if-exists", "--no-owner", "--no-acl", "--no-tablespaces",
                "--use-list", restoreLists.getFirst().toString(),
                "--file", remoteSql.toString(), remoteDump.toString()), null, false);
        Path authSql = privateFile(run.resolve("local-auth.sql"));
        generated.add(authSql);
        runStage(run, "render-local-auth", List.of(binaries.resolve("pg_restore").toString(),
                "--data-only", "--no-owner", "--no-acl", "--no-tablespaces",
                "--file", authSql.toString(), authDump.toString()), null, false);
        var materializedViewSql = new ArrayList<Path>();
        for (int index = 1; index < restoreLists.size(); index++) {
            Path viewSql = privateFile(run.resolve("materialized-view-" + index + ".sql"));
            generated.add(viewSql);
            materializedViewSql.add(viewSql);
            runStage(run, "render-materialized-view-" + index, List.of(binaries.resolve("pg_restore").toString(),
                    "--no-owner", "--no-acl", "--no-tablespaces", "--use-list", restoreLists.get(index).toString(),
                    "--file", viewSql.toString(), remoteDump.toString()), null, false);
        }

        Path resetSql = privateFile(run.resolve("reset-public.sql"));
        generated.add(resetSql);
        Files.writeString(resetSql, """
                DO $refresh$
                BEGIN
                    IF EXISTS (SELECT 1 FROM pg_catalog.pg_stat_activity WHERE %s) THEN
                        RAISE EXCEPTION '%s';
                    END IF;
                END;
                $refresh$;
                DROP SCHEMA public CASCADE;
                CREATE SCHEMA public AUTHORIZATION CURRENT_USER;
                """.formatted(OTHER_CLIENT_SESSIONS_CONDITION, ACTIVE_CLIENTS_MESSAGE));
        Path verifySql = privateFile(run.resolve("verify.sql"));
        generated.add(verifySql);
        Files.writeString(verifySql, verificationSql(source, target, local));
        Path analyzeSql = privateFile(run.resolve("analyze.sql"));
        generated.add(analyzeSql);
        Files.writeString(analyzeSql, "ANALYZE;\n");
        var restore = command(binaries, "psql", local,
                "-X", "--single-transaction", "--set=ON_ERROR_STOP=on",
                "--file", resetSql.toString(), "--file", remoteSql.toString(),
                "--file", authSql.toString(), "--file", analyzeSql.toString());
        // Each dependent materialized view sees statistics for the data restored before it.
        for (Path viewSql : materializedViewSql) {
            restore.addAll(List.of("--file", viewSql.toString(), "--file", analyzeSql.toString()));
        }
        restore.addAll(List.of("--file", verifySql.toString()));
        runStage(run, "restore-local", restore, local, false);
        for (Path file : generated) Files.delete(file);
        return run;
    }

    private record Endpoint(DBPass credentials, String host, int port, String database) {
    }

    private record SequenceState(long value, boolean called) {
    }

    private record Snapshot(int version, int serverVersion, List<String> tables, String layout,
                            String usersHash, String credentialsHash, SequenceState usersSequence,
                            SequenceState credentialsSequence) {
    }

    private static Endpoint endpoint(DBPass credentials, String alias) {
        if (credentials == null || !alias.equals(credentials.alias())) {
            throw new IllegalArgumentException("Expected database alias " + alias);
        }
        String connect = credentials.connect();
        if (connect == null || !connect.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException(alias + " requires a PostgreSQL JDBC URL with an explicit host");
        }
        final URI uri;
        try {
            uri = URI.create(connect.substring(5));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(alias + " has an invalid PostgreSQL JDBC URL");
        }
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || host.isBlank() || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || path == null || path.length() < 2
                || path.substring(1).contains("/") || path.contains("=") || path.chars().anyMatch(Character::isISOControl)
                || credentials.user() == null || credentials.user().isBlank()
                || credentials.user().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(alias + " requires a single-host JDBC URL without URL options or credentials");
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException(alias + " has an invalid port");
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return new Endpoint(credentials, host.toLowerCase(Locale.ROOT), port, path.substring(1));
    }

    private static boolean isLoopback(String host) {
        if (Set.of("localhost", "::1", "0:0:0:0:0:0:0:1").contains(host)) return true;
        String[] parts = host.split("\\.");
        if (parts.length != 4 || !parts[0].equals("127")) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static boolean sameDatabase(Endpoint first, Endpoint second) {
        return first.port() == second.port() && first.database().equals(second.database())
                && (first.host().equals(second.host()) || isLoopback(first.host()) && isLoopback(second.host()));
    }

    private static Path findTools(Path supplied) {
        List<Path> candidates = new ArrayList<>();
        if (supplied != null) {
            candidates.add(supplied);
        } else {
            for (String entry : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
                if (!entry.isBlank()) candidates.add(Paths.get(entry));
            }
            candidates.add(Paths.get("/Applications/Postgres.app/Contents/Versions/latest/bin"));
            candidates.add(Paths.get("/Applications/Postgres.app/Contents/Versions/18/bin"));
            candidates.add(homeDirectory().resolve("Applications/Postgres.app/Contents/Versions/latest/bin"));
        }
        return candidates.stream().map(path -> path.toAbsolutePath().normalize())
                .filter(path -> TOOLS.stream().allMatch(tool -> Files.isExecutable(path.resolve(tool))))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Cannot find pg_dump, pg_restore and psql; supply their directory with --pg-bin"));
    }

    private static Snapshot inspect(Endpoint endpoint, boolean target) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", endpoint.credentials().user());
        if (endpoint.credentials().pw() != null) properties.setProperty("password", endpoint.credentials().pw());
        properties.setProperty("connectTimeout", "15");
        try (Connection db = DriverManager.getConnection(endpoint.credentials().connect(), properties)) {
            db.setReadOnly(true);
            db.setAutoCommit(false);
            try (var statement = db.createStatement()) {
                statement.execute("SET LOCAL TIME ZONE 'UTC'");
            }
            if (!string(db, "SELECT current_database()").equals(endpoint.database())) {
                throw new IllegalArgumentException("Connection reached an unexpected database");
            }
            if (number(db, """
                    SELECT count(*) FROM pg_catalog.pg_namespace
                    WHERE nspname <> 'public' AND nspname <> 'information_schema' AND nspname !~ '^pg_'
                    """) != 0 || number(db, "SELECT count(*) FROM pg_catalog.pg_extension WHERE extname <> 'plpgsql'") != 0
                    || number(db, """
                    SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relkind IN ('p', 'f')
                    """) != 0 || number(db, "SELECT count(*) FROM pg_catalog.pg_largeobject_metadata") != 0) {
                throw new IllegalArgumentException("Refresh supports public schemas without extra extensions, partitions or large objects");
            }
            if (target) {
                String serverAddress = string(db, "SELECT pg_catalog.host(pg_catalog.inet_server_addr())");
                if (serverAddress == null || !isLoopback(serverAddress)) {
                    throw new IllegalArgumentException("emagLocal must reach a server on a loopback address");
                }
                if (number(db, "SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE "
                        + OTHER_CLIENT_SESSIONS_CONDITION) != 0) {
                    throw new IllegalStateException(ACTIVE_CLIENTS_MESSAGE);
                }
                if (!string(db, "SELECT pg_catalog.pg_get_userbyid(datdba) FROM pg_catalog.pg_database WHERE datname = current_database()")
                        .equals(string(db, "SELECT current_user"))) {
                    throw new IllegalArgumentException("emagLocal must connect as the local database owner");
                }
            }
            List<String> tables = new ArrayList<>();
            try (var statement = db.createStatement(); var result = statement.executeQuery(TABLES_QUERY)) {
                while (result.next()) tables.add(result.getString(1));
            }
            if (!tables.containsAll(AUTH_TABLES) || !tables.contains("version_info")) {
                throw new IllegalArgumentException("Required version and authentication tables are missing");
            }
            int version = Math.toIntExact(number(db, "SELECT COALESCE(max(version), -1) FROM public.version_info"));
            if (version < 1) throw new IllegalArgumentException("Database schema version is missing or invalid");
            for (String table : AUTH_TABLES) {
                if (!("public." + table + "_id_seq").equals(string(db,
                        "SELECT pg_catalog.pg_get_serial_sequence('public." + table + "', 'id')"))) {
                    throw new IllegalArgumentException("Unsupported authentication sequence for " + table);
                }
            }
            if (number(db, """
                    SELECT count(*) FROM pg_catalog.pg_constraint
                    WHERE contype = 'f'
                      AND confrelid IN ('public.app_user'::regclass, 'public.webauthn_credential'::regclass,
                                        'public.webauthn_challenge'::regclass)
                      AND NOT (confrelid = 'public.app_user'::regclass
                          AND conrelid IN ('public.webauthn_credential'::regclass, 'public.webauthn_challenge'::regclass))
                    """) != 0) {
                throw new IllegalArgumentException("Unsupported foreign keys to authentication tables");
            }
            return new Snapshot(version, db.getMetaData().getDatabaseMajorVersion(), List.copyOf(tables),
                    string(db, LAYOUT_QUERY), target ? string(db, authHashQuery("app_user")) : null,
                    target ? string(db, authHashQuery("webauthn_credential")) : null,
                    target ? sequence(db, "app_user_id_seq") : null,
                    target ? sequence(db, "webauthn_credential_id_seq") : null);
        }
    }

    private static String authHashQuery(String table) {
        return "SELECT md5(COALESCE(string_agg(to_jsonb(t)::text, E'\\n' ORDER BY id), '')) FROM public." + table + " t";
    }

    private static String string(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Expected a metadata result");
            return result.getString(1);
        }
    }

    private static long number(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Expected a metadata result");
            return result.getLong(1);
        }
    }

    private static SequenceState sequence(Connection db, String name) throws SQLException {
        try (var statement = db.createStatement();
             var result = statement.executeQuery("SELECT last_value, is_called FROM public." + name)) {
            if (!result.next()) throw new SQLException("Expected an authentication sequence");
            return new SequenceState(result.getLong(1), result.getBoolean(2));
        }
    }

    private static List<String> command(Path binaries, String tool, Endpoint endpoint, String... options) {
        var command = new ArrayList<>(List.of(binaries.resolve(tool).toString(), "--host", endpoint.host(),
                "--port", Integer.toString(endpoint.port()), "--username", endpoint.credentials().user(),
                "--dbname", endpoint.database(), "--no-password"));
        command.addAll(List.of(options));
        return command;
    }

    private static Path validateArchive(Path run, Path binaries, Path archive, String stage) throws Exception {
        return runStage(run, stage, List.of(binaries.resolve("pg_restore").toString(), "--list", archive.toString()), null, false);
    }

    private static List<Path> splitMaterializedViews(Path run, Path toc) throws IOException {
        var ordinaryEntries = new ArrayList<String>();
        var viewEntries = new ArrayList<String>();
        for (String line : Files.readAllLines(toc)) {
            if (MATERIALIZED_VIEW_DATA.matcher(line).matches()) {
                viewEntries.add(line);
            } else if (TOC_ENTRY.matcher(line).matches()) {
                ordinaryEntries.add(line);
            }
        }
        if (ordinaryEntries.isEmpty()) throw new IOException("Remote archive contains no ordinary restore entries");
        var lists = new ArrayList<Path>();
        Path ordinary = privateFile(run.resolve("restore-main.list"));
        Files.write(ordinary, ordinaryEntries);
        lists.add(ordinary);
        for (int index = 0; index < viewEntries.size(); index++) {
            Path view = privateFile(run.resolve("materialized-view-" + (index + 1) + ".list"));
            Files.writeString(view, viewEntries.get(index) + "\n");
            lists.add(view);
        }
        return List.copyOf(lists);
    }

    private static Path privateFile(Path path) throws IOException {
        return Files.createFile(path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    private static Path runStage(Path run, String stage, List<String> command, Endpoint endpoint, boolean readOnly)
            throws IOException, InterruptedException {
        Path log = privateFile(run.resolve(stage + ".log"));
        var builder = new ProcessBuilder(command).directory(run.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().keySet().removeIf(key -> key.startsWith("PG"));
        if (endpoint != null && endpoint.credentials().pw() != null) {
            builder.environment().put("PGPASSWORD", endpoint.credentials().pw());
        }
        builder.environment().put("PGCONNECT_TIMEOUT", "15");
        if (readOnly) builder.environment().put("PGOPTIONS", "-c default_transaction_read_only=on");
        System.out.println("Starting " + stage + ".");
        long started = System.nanoTime();
        Process process = builder.start();
        try {
            while (!process.waitFor(30, TimeUnit.SECONDS)) {
                System.out.println(stage + " still running (" + Duration.ofNanos(System.nanoTime() - started).toSeconds() + "s).");
            }
        } catch (InterruptedException e) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
        if (process.exitValue() != 0) {
            throw new IOException(stage + " failed with exit code " + process.exitValue() + ". Private log: " + log);
        }
        System.out.println("Finished " + stage + ".");
        return log;
    }

    private static String verificationSql(Snapshot source, Snapshot target, Endpoint local) {
        StringBuilder sql = new StringBuilder("SET LOCAL TIME ZONE 'UTC';\nDO $refresh$\nBEGIN\n");
        assertion(sql, "current_user = " + literal(local.credentials().user()), "Unexpected local restore role");
        assertion(sql, "(SELECT max(version) FROM public.version_info) = " + source.version(), "Restored schema version differs");
        assertion(sql, "(SELECT count(*) FROM (" + TABLES_QUERY + ") tables) = " + source.tables().size(),
                "Restored table count differs");
        assertion(sql, "(" + LAYOUT_QUERY + ") = " + literal(source.layout()), "Restored table layout differs");
        assertion(sql, "(" + authHashQuery("app_user") + ") = " + literal(target.usersHash()), "Local users were not preserved");
        assertion(sql, "(" + authHashQuery("webauthn_credential") + ") = " + literal(target.credentialsHash()),
                "Local passkeys were not preserved");
        verifySequence(sql, "app_user_id_seq", target.usersSequence());
        verifySequence(sql, "webauthn_credential_id_seq", target.credentialsSequence());
        assertion(sql, "(SELECT count(*) FROM public.webauthn_challenge) = 0", "Authentication challenges must be empty");
        assertion(sql, "NOT EXISTS (SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'public' AND c.relkind IN ('r', 'S', 'v', 'm') "
                + "AND c.relowner <> (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = current_user))",
                "Restored objects must belong to the local role");
        sql.append("PERFORM pg_catalog.setval('public.webauthn_challenge_id_seq', 1, false);\nEND;\n$refresh$;\n");
        return sql.toString();
    }

    private static void verifySequence(StringBuilder sql, String name, SequenceState state) {
        assertion(sql, "(SELECT last_value = " + state.value() + " AND is_called = " + state.called()
                + " FROM public." + name + ")", "Local authentication sequence was not preserved: " + name);
    }

    private static void assertion(StringBuilder sql, String condition, String message) {
        sql.append("IF NOT COALESCE((").append(condition).append("), false) THEN RAISE EXCEPTION ")
                .append(literal(message)).append("; END IF;\n");
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
