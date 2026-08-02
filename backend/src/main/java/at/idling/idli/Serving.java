package at.idling.idli;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * A named quantity of an item, in the item's basis unit: "whole bar" = 50 (g).
 * Deliberately carries no metric amounts — those are computed from the item's
 * composition at log time (ADR-0008).
 */
@Entity
public class Serving {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long itemId;

	private String name;

	private long quantity;

	protected Serving() {
	}

	public Serving(Long itemId, String name, long quantity) {
		this.itemId = itemId;
		this.name = name;
		this.quantity = quantity;
	}

	public Long getId() {
		return id;
	}

	public Long getItemId() {
		return itemId;
	}

	public String getName() {
		return name;
	}

	public long getQuantity() {
		return quantity;
	}

	public void update(String name, long quantity) {
		this.name = name;
		this.quantity = quantity;
	}

}
