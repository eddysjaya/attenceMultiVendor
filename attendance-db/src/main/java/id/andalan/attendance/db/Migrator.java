package id.andalan.attendance.db;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Menjalankan migrasi Flyway.
 *
 * <pre>
 * ATTENDANCE_DB_URL=jdbc:postgresql://localhost:5432/attendance_dev \
 * ATTENDANCE_DB_USER=attendance ATTENDANCE_DB_PASSWORD=... \
 * java -jar attendance-db-migrator.jar migrate|info|validate
 * </pre>
 */
public final class Migrator {

    private Migrator() {
    }

    public static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                // Migrasi memuat JSON dalam dollar-quote ($json$...$json$); "${" di sana bukan placeholder.
                .placeholderReplacement(false)
                .cleanDisabled(true)
                .load();
    }

    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "migrate";
        var ds = new PGSimpleDataSource();
        ds.setUrl(requireEnv("ATTENDANCE_DB_URL"));
        ds.setUser(requireEnv("ATTENDANCE_DB_USER"));
        ds.setPassword(requireEnv("ATTENDANCE_DB_PASSWORD"));

        Flyway flyway = flyway(ds);
        switch (command) {
            case "migrate" -> {
                var result = flyway.migrate();
                System.out.printf("migrasi diterapkan: %d, versi skema: %s%n",
                        result.migrationsExecuted, result.targetSchemaVersion);
            }
            case "info" -> {
                for (var m : flyway.info().all()) {
                    System.out.printf("%-6s %-40s %s%n", m.getVersion(), m.getDescription(), m.getState());
                }
            }
            case "validate" -> {
                flyway.validate();
                System.out.println("valid");
            }
            default -> {
                System.err.println("perintah tidak dikenal: " + command + " (migrate|info|validate)");
                System.exit(2);
            }
        }
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            System.err.println("variabel lingkungan wajib belum diisi: " + name);
            System.exit(2);
        }
        return v;
    }
}
