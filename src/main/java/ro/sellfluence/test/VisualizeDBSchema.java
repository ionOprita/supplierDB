package ro.sellfluence.test;

import ch.claudio.db.DBPass;
import org.schemaspy.LayoutFolder;
import org.schemaspy.SchemaAnalyzer;
import org.schemaspy.cli.CommandLineArgumentParser;
import org.schemaspy.cli.CommandLineArguments;
import org.schemaspy.cli.ConfigFileArgumentParser;
import org.schemaspy.cli.DefaultProviderFactory;
import org.schemaspy.cli.SchemaSpyRunner;
import org.schemaspy.connection.ScExceptionChecked;
import org.schemaspy.connection.ScNullChecked;
import org.schemaspy.connection.ScSimple;
import org.schemaspy.input.dbms.ConnectionConfig;
import org.schemaspy.input.dbms.ConnectionURLBuilder;
import org.schemaspy.input.dbms.DriverFromConfig;
import org.schemaspy.input.dbms.service.DatabaseServiceFactory;
import org.schemaspy.input.dbms.service.SqlService;
import org.schemaspy.output.xml.dom.XmlProducerUsingDOM;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * VisualizeDBSchema is responsible for generating a visual representation of a database schema
 * using SchemaSpy and opening the resulting HTML report in the default browser.
 * This class interacts with the database credentials stored in the user's `dbpass.txt` file and
 * constructs the necessary configurations to execute SchemaSpy.
 *
 * <p>The main workflow consists of extracting database connection information, setting up
 * SchemaSpy arguments, and executing the SchemaSpy process to analyse the database schema.</p>
 *
 * <p>NOTE: The <a href="https://graphviz.org/">graphviz</a> package needs to be installed.</p>
 */
public class VisualizeDBSchema {

    private static final Pattern urlPattern = Pattern.compile("jdbc:postgresql://([^:]+):(\\d+)/(.*)");
    private static final String dbAlias = "emagLocal";

    public static void run(String... args) {
        CommandLineArgumentParser commandLineArgumentParser =
                new CommandLineArgumentParser(
                        new DefaultProviderFactory(
                                new ConfigFileArgumentParser(args).configFile()
                        ).defaultProvider(),
                        args
                );
        CommandLineArguments arguments = commandLineArgumentParser.commandLineArguments();
        SqlService sqlService = new SqlService();
        final ConnectionConfig connectionConfig = arguments.getConnectionConfig();
        final ConnectionURLBuilder urlBuilder = new ConnectionURLBuilder(connectionConfig);
        SchemaSpyRunner schemaSpyRunner = new SchemaSpyRunner(
                new SchemaAnalyzer(
                        sqlService,
                        new DatabaseServiceFactory(sqlService),
                        arguments,
                        new XmlProducerUsingDOM(),
                        new LayoutFolder(SchemaAnalyzer.class.getClassLoader()),
                        new ScExceptionChecked(
                                urlBuilder,
                                new ScNullChecked(
                                        urlBuilder,
                                        new ScSimple(
                                                connectionConfig,
                                                urlBuilder,
                                                new DriverFromConfig(connectionConfig)
                                        )
                                )
                        )
                ),
                arguments,
                args
        );
        schemaSpyRunner.run();
    }

    public static void main(String[] args) throws Exception {
        var db = DBPass.findDB(dbAlias);
        var m = urlPattern.matcher(db.connect());
        var tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        var dbSchemaDir = tmp.resolve(dbAlias + ".dbschema");
        if (m.matches()) {
            String host = m.group(1);
            String port = m.group(2);
            String dbName = m.group(3);

            var home = Paths.get(System.getProperty("user.home"));
            var m2cache = home.resolve(".m2").resolve("repository");
            var postgresql = m2cache.resolve("org").resolve("postgresql").resolve("postgresql");
            try (Stream<Path> pathStream = Files.list(postgresql)) {
                var version = pathStream.filter(p -> p.getFileName().toString().matches("[\\d.]+")).sorted().toList().getLast();
                var jar = postgresql.resolve(version).resolve("postgresql-" + version.getFileName().toString() + ".jar");
                run(
                        "-t",
                        "pgsql11",
                        "-dp",
                        jar.toString(),
                        "-db",
                        dbName,
                        "-host",
                        host,
                        "-port",
                        port,
                        "-u",
                        db.user(),
                        "-p",
                        db.pw(),
                        "-o",
                        dbSchemaDir.toString()
                );
                Desktop.getDesktop().browse(dbSchemaDir.toUri().resolve("index.html"));
            }
        }
    }
}