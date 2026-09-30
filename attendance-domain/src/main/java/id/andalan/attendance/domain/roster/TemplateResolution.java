package id.andalan.attendance.domain.roster;

import java.util.List;
import java.util.UUID;

/** Hasil {@link TemplateResolver#resolve}: template terpilih, tidak ada shift, atau ambigu. */
public sealed interface TemplateResolution {

    /** @param sourceAssignmentId null bila berasal dari template default situs */
    record Resolved(ShiftTemplate template, UUID sourceAssignmentId) implements TemplateResolution {
    }

    record NoShift(String reason) implements TemplateResolution {
    }

    /** Lebih dari satu rentang seri; exception {@code roster_ambiguous}, tanpa instance. */
    record Ambiguous(List<UUID> assignmentIds) implements TemplateResolution {
        public Ambiguous {
            assignmentIds = assignmentIds.stream().sorted().toList();
        }
    }
}
