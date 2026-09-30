package id.andalan.attendance.domain.roster;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** Bagian data situs yang dibutuhkan roster: zona waktu IANA dan template default (nullable). */
public record SiteRoster(UUID siteId, ZoneId zone, UUID defaultTemplateId) {

    public SiteRoster {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(zone, "zone");
    }
}
