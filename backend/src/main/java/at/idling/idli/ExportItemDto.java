package at.idling.idli;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * An item in the export file: composition per basis quantity plus its
 * servings (ADR-0008).
 */
public record ExportItemDto(@NotBlank String name, @NotNull @Positive Long basisAmount, @NotBlank String basisUnit,
		@NotEmpty List<@Valid ExportItemAmountDto> amounts, @NotNull List<@Valid ExportServingDto> servings) {
}
