package gov.uddp.core.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gov.uddp.core.connector.SourceConnector.Config;
import gov.uddp.core.studio.CsvProfiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Track C5 against real servers in Testcontainers: PostgreSQL, MySQL and an SFTP server. */
class ConnectorContainersTest {
    @TempDir
    Path work;

    private static final class MemorySecrets implements ConnectorSecrets {
        final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> get(String name) {
            return Optional.ofNullable(values.get(name));
        }

        @Override
        public void put(String name, String value) {
            values.put(name, value);
        }
    }

    private final MemorySecrets secrets = new MemorySecrets();

    private Config config(Map<String, String> values) {
        return new Config(new LinkedHashMap<>(values), secrets);
    }

    @Test
    void postgresqlTableAndQueryThroughTheGenericJdbcTemplate() throws Exception {
        try (var db = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))) {
            db.start();
            seed(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            secrets.put("pw", db.getPassword());
            var values = new LinkedHashMap<String, String>();
            values.put("dbType", "postgresql");
            values.put("host", db.getHost());
            values.put("port", Integer.toString(db.getMappedPort(5432)));
            values.put("database", db.getDatabaseName());
            values.put("user", db.getUsername());
            values.put("passwordRef", "pw");
            assertTable(values);
        }
    }

    @Test
    void mysqlTableAndQueryThroughTheGenericJdbcTemplate() throws Exception {
        try (var db = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))) {
            db.start();
            seed(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            secrets.put("pw", db.getPassword());
            var values = new LinkedHashMap<String, String>();
            values.put("dbType", "mysql");
            values.put("host", db.getHost());
            values.put("port", Integer.toString(db.getMappedPort(3306)));
            values.put("database", db.getDatabaseName());
            values.put("user", db.getUsername());
            values.put("passwordRef", "pw");
            assertTable(values);
        }
    }

    @Test
    void sftpPicksUpTheNewestMatchingFileAndArchivesItAfterSuccess() throws Exception {
        Path file = work.resolve("people.csv");
        Files.writeString(file, "id,name\n1,Ram Singh\n2,Sita Singh\n");
        try (var sftp = new GenericContainer<>(DockerImageName.parse("atmoz/sftp:alpine"))
                .withCommand("dept:pw-sftp:::upload")
                .withExposedPorts(22)
                .withCopyFileToContainer(MountableFile.forHostPath(file, 0644), "/home/dept/upload/people.csv")) {
            sftp.start();
            sftp.execInContainer("sh", "-c", "mkdir -p /home/dept/upload/archive && chmod 777 /home/dept/upload"
                    + " /home/dept/upload/archive && chmod 666 /home/dept/upload/people.csv");
            secrets.put("pw", "pw-sftp");
            var values = new LinkedHashMap<String, String>();
            values.put("host", sftp.getHost());
            values.put("port", Integer.toString(sftp.getMappedPort(22)));
            values.put("user", "dept");
            values.put("passwordRef", "pw");
            values.put("directory", "/upload");
            values.put("pattern", "*.csv");
            values.put("archiveDirectory", "/upload/archive");
            var connector = new SftpConnector(new CsvProfiler());

            // A host key must be pinned or trusted explicitly.
            assertThrows(IllegalArgumentException.class, () -> connector.test(config(values)));
            values.put("trustUnknownHost", "true");
            Config settings = config(values);

            connector.test(settings);
            assertEquals(2, connector.preview(settings, 10).rows().size());
            var delivery = connector.fetch(settings);
            assertEquals("Ram Singh", delivery.table().rows().getFirst().get("name"));
            delivery.completed().run();

            var again = assertThrows(IllegalArgumentException.class, () -> connector.fetch(settings));
            assertEquals(true, again.getMessage().contains("No file matching"));
            var archived = sftp.execInContainer("sh", "-c", "ls /home/dept/upload/archive");
            assertEquals(true, archived.getStdout().contains("people.csv"));
        }
    }

    private void assertTable(Map<String, String> base) {
        var jdbc = new JdbcConnector();
        var byTable = new LinkedHashMap<>(base);
        byTable.put("table", "people");
        jdbc.test(config(byTable));
        assertEquals(2, jdbc.preview(config(byTable), 2).rows().size());
        var all = jdbc.fetch(config(byTable)).table();
        assertEquals(3, all.rows().size());
        assertEquals("", all.rows().get(2).get("name"), "a NULL value is an empty string");

        var byQuery = new LinkedHashMap<>(base);
        byQuery.put("query", "select id, name from people where id in (1, 2) order by id");
        assertEquals(2, jdbc.fetch(config(byQuery)).table().rows().size());

        byQuery.put("passwordRef", "unknown");
        assertThrows(IllegalArgumentException.class, () -> jdbc.test(config(byQuery)));
    }

    private static void seed(String url, String user, String password) throws Exception {
        try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
            s.execute("create table people (id int primary key, name varchar(50))");
            s.execute("insert into people values (1, 'Ram Singh'), (2, 'Sita Singh'), (3, null)");
        }
    }
}
