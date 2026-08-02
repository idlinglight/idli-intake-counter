package at.idling.idli;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Create/replace payload for an item: name, basis quantity and the composition
 * per basis (ADR-0008). On PUT this replaces name, basis and amounts wholesale;
 * servings are managed by their own endpoints and survive.
 *
 * @param amounts at least one — an item with nothing to log is a trap for the
 *                logging surface
 */
public record ItemRequest(@NotBlank String name, @NotNull @Positive Long basisAmount, @NotBlank String basisUnit,
		@NotEmpty List<@Valid ItemAmountDto> amounts) {
}
