package id.andalan.attendance.domain.roster;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Istirahat terjadwal pada template shift, dalam jam lokal situs.
 * {@code end} sebelum {@code start} berarti istirahat melewati tengah malam.
 */
public record BreakWindow(LocalTime start, LocalTime end, boolean paid) {

    public BreakWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.equals(end)) {
            throw new IllegalArgumentException("istirahat berdurasi nol: " + start);
        }
    }
}
