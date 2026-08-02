package at.idling.idli;

/** A serving as served by the API; quantity is in the item's basis unit. */
public record ServingDto(long id, String name, long quantity) {
}
