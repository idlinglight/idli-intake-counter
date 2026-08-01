package at.idling.idli;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * @param metric the metric's (unique) name — see {@link ExportDto} on why not the id
 * @param amount in the metric's canonical unit (ADR-0002)
 */
public record ExportEntryDto(@NotBlank String metric, @NotNull @Positive Long amount, @NotNull Instant loggedAt) {
}
