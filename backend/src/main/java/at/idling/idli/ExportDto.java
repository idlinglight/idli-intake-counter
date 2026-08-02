package at.idling.idli;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * The full-database export file (ADR-0004): everything needed to rebuild the
 * database from nothing, as one self-contained JSON document. Import consumes
 * exactly what export emits. Entries and item amounts reference metrics by
 * name, not id — ids are generated and not preserved across a re-import.
 *
 * @param formatVersion see {@link ExportImportService#FORMAT_VERSION}; import
 *                      also accepts older versions, normalizing on read
 *                      (ADR-0007)
 * @param exportedAt    when the export was taken; informational on import
 * @param items         deliberately not @NotNull: formatVersion 1 files lack
 *                      the key — the version-conditional check lives in
 *                      {@link ExportImportService}
 */
public record ExportDto(@NotNull Integer formatVersion, Instant exportedAt,
		@NotEmpty List<@Valid ExportMetricDto> metrics, List<@Valid ExportItemDto> items,
		@NotNull List<@Valid ExportEntryDto> entries) {
}
