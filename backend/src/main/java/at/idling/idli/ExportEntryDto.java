package at.idling.idli;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.UUID;

/**
 * @param metric the metric's (unique) name — see {@link ExportDto} on why not the id
 * @param amount in the metric's canonical unit (ADR-0002)
 * @param label  snapshotted serving-log label; formatVersion ≥ 3, always
 *               together with {@code group}, absent on ad-hoc entries
 *               (NON_NULL keeps those serializing exactly as version 2 did)
 * @param group  shared by the entries of one serving-log action; UUID so a
 *               malformed value fails at parse time, not at restore time
 */
public record ExportEntryDto(@NotBlank String metric, @NotNull @Positive Long amount, @NotNull Instant loggedAt,
		@JsonInclude(JsonInclude.Include.NON_NULL) String label,
		@JsonInclude(JsonInclude.Include.NON_NULL) UUID group) {
}
