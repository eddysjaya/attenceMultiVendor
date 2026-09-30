# Sistem Absensi Multi-Vendor

Absensi untuk terminal ZKTeco, Hikvision, dan Dahua di belakang NAT, dengan lembur kuota yang melewati tengah malam. Desain lengkap ada di [`Doc/`](Doc/):

- [`desain-sistem-absensi-multi-vendor.md`](Doc/desain-sistem-absensi-multi-vendor.md) — aturan domain, skema, API, rencana PR
- [`arsitektur-dan-stack.md`](Doc/arsitektur-dan-stack.md) — arsitektur proses, jalur NAT per vendor, stack Java 25
- [`mockup-attendance-system.html`](Doc/mockup-attendance-system.html) — mockup UI HR

## Status

| PR | Isi | Status |
|---|---|---|
| 1a | Skema PostgreSQL (Flyway), seed PayPolicy, materialisasi roster | Selesai, teruji di PostgreSQL 17 |
| 1b | Segmenter murni (kuota, blok minimum, braket, cap), kalender hari, parser PayPolicy; kasus A/B/C | Selesai, 62 uji lolos |
| 2 | Kompilator jadwal + OverlapPolicy (amplop kuota) | Berikutnya |

## Modul

| Modul | Isi |
|---|---|
| `attendance-domain` | Mesin murni tanpa I/O: roster (`TemplateResolver`, `RosterMaterializer`), kebijakan dan kalender (`PayPolicy`, `WorkCalendar`), segmenter (`Segmenter`); nanti kompilator dan matcher |
| `attendance-db` | Migrasi Flyway (`src/main/resources/db/migration`), `RosterMaterializationService`, `PayPolicyJson`, `Migrator` (JAR `attendance-db-migrator.jar`) |

## Build dan uji

Butuh JDK 25. Maven tidak perlu dipasang; pakai wrapper.

```bash
./mvnw verify            # Windows: mvnw.cmd verify
```

Bila `JAVA_HOME` menunjuk JDK lain, arahkan ke JDK 25 untuk perintah itu saja, mis. `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.1.8-hotspot" ./mvnw verify`.

Uji basis data membuat database sementara per kelas uji lalu menghapusnya. Tanpa variabel berikut, uji basis data di-skip dan hanya uji domain yang jalan:

| Variabel | Contoh |
|---|---|
| `ATTENDANCE_TEST_PG_HOST` | `127.0.0.1` |
| `ATTENDANCE_TEST_PG_PORT` | `5432` (default) |
| `ATTENDANCE_TEST_PG_USER` | role dengan hak `CREATEDB` |
| `ATTENDANCE_TEST_PG_PASSWORD` | |

Di server dev, uji lengkap dijalankan di container Maven terhadap PostgreSQL host:

```bash
set -a; . ~/.attendance-dev.env; set +a
docker run --rm --network host -u $(id -u):$(id -g) \
  -v "$PWD":/src -v ~/.m2:/var/maven/.m2 -e HOME=/var/maven -w /src \
  -e ATTENDANCE_TEST_PG_HOST=127.0.0.1 -e ATTENDANCE_TEST_PG_USER=$PGUSER -e ATTENDANCE_TEST_PG_PASSWORD=$PGPASSWORD \
  maven:3.9-eclipse-temurin-25 mvn -B -Duser.home=/var/maven verify
```

## Migrasi basis data

```bash
docker run --rm --network host \
  -v "$PWD/attendance-db/target/attendance-db-migrator.jar":/app/migrator.jar:ro \
  -e ATTENDANCE_DB_URL=jdbc:postgresql://127.0.0.1:5432/attendance_dev \
  -e ATTENDANCE_DB_USER=$PGUSER -e ATTENDANCE_DB_PASSWORD=$PGPASSWORD \
  eclipse-temurin:25-jre java -jar /app/migrator.jar migrate   # atau: info, validate
```

Migrasi yang sudah diterapkan tidak pernah diubah; perubahan skema selalu berupa file `V<n>__...sql` baru.
