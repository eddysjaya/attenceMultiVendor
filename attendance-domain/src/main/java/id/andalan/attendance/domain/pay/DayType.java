package id.andalan.attendance.domain.pay;

/** Jenis hari sipil, sama dengan {@code calendar_days.day_type}. */
public enum DayType {
    WEEKDAY,
    WEEKLY_REST,
    PUBLIC_HOLIDAY;

    public boolean isRestDay() {
        return this != WEEKDAY;
    }
}
