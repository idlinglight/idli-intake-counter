package at.idling.idli;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * The full-database export file (ADR-0004): everything needed to rebuild the
 * database from nothing, as one self-contained JSON document. Import consumes
 * exactly what export emits. Entries reference metrics by name, not id —
 * ids are generated and not preserved across a re-import.
 *
 * @param formatVersion see {@link ExportImportService#FORMAT_VERSION}; import
 *                      rejects any other value
 * @param exportedAt    when the export was taken; informational on import
 */
public record ExportDto(@NotNull Integer formatVersion, Instant exportedAt,
		@NotNull List<@Valid ExportMetricDto> metrics, @NotNull List<@Valid ExportEntryDto> entries) {
}
