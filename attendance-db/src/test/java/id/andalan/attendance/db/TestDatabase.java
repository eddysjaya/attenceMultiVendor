package id.andalan.attendance.db;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Basis data PostgreSQL sementara untuk satu kelas uji: dibuat, dimigrasi, lalu di-drop.
 *
 * <p>Konfigurasi lewat variabel lingkungan (tanpa itu, uji basis data di-skip):
 * {@code ATTENDANCE_TEST_PG_HOST}, {@code _PORT} (default 5432), {@code _USER}, {@code _PASSWORD}.
 * Role butuh hak {@code CREATEDB}.
 */
public final class TestDatabase implements AutoCloseable {

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String name;
    private final PGSimpleDataSource dataSource;

    private TestDatabase(String host, int port, String user, String password) throws SQLException {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.name = "attendance_test_" + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[] {host});
        dataSource.setPortNumbers(new int[] {port});
        dataSource.setDatabaseName(name);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        try {
            Migrator.flyway(dataSource).migrate();
        } catch (RuntimeException e) {
            // Konstruktor gagal berarti close() tidak akan dipanggil: drop sekarang agar tidak tertinggal.
            try {
                close();
            } catch (SQLException dropFailure) {
                e.addSuppressed(dropFailure);
            }
            throw e;
        }
    }

    public static TestDatabase create() throws SQLException {
        String host = System.getenv("ATTENDANCE_TEST_PG_HOST");
        assumeTrue(host != null && !host.isBlank(), "ATTENDANCE_TEST_PG_HOST tidak diisi: uji basis data di-skip");
        String port = System.getenv().getOrDefault("ATTENDANCE_TEST_PG_PORT", "5432");
        return new TestDatabase(host, Integer.parseInt(port),
                System.getenv("ATTENDANCE_TEST_PG_USER"), System.getenv("ATTENDANCE_TEST_PG_PASSWORD"));
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/postgres", user, password);
    }

    @Override
    public void close() throws SQLException {
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }
}
