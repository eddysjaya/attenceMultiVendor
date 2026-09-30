package id.andalan.attendance.domain.pay;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Satu braket tarif lembur. {@code widthMinutes} adalah LEBAR braket (bukan titik jam dinding);
 * null berarti sisa tanpa batas. Pengali hanya dibawa untuk payroll; mesin mengeluarkan {@code rateCode}.
 */
public record OtBracket(Integer widthMinutes, BigDecimal multiplier, String rateCode) {

    public OtBracket {
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(rateCode, "rateCode");
        if (widthMinutes != null && widthMinutes <= 0) {
            throw new IllegalArgumentException("lebar braket harus positif: " + widthMinutes);
        }
    }
}
