package at.idling.idli;

import java.util.List;

/**
 * An item with its full catalog subtree: composition amounts (per basis,
 * sorted by metricId) and servings (sorted by id).
 */
public record ItemDto(long id, String name, long basisAmount, String basisUnit, List<ItemAmountDto> amounts,
		List<ServingDto> servings) {
}
