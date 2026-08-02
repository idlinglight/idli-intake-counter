package at.idling.idli;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * @param metric the metric's (unique) name — see {@link ExportDto} on why not the id
 * @param amount per basis quantity of the item, in the metric's canonical unit
 */
public record ExportItemAmountDto(@NotBlank String metric, @NotNull @Positive Long amount) {
}
