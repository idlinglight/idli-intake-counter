package at.idling.idli;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * One metric's amount per basis quantity of an item (in the metric's canonical
 * unit, ADR-0002): "2281 kJ per 100 g". At most one row per (item, metric).
 */
@Entity
public class ItemAmount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long itemId;

	private Long metricId;

	private long amount;

	protected ItemAmount() {
	}

	public ItemAmount(Long itemId, Long metricId, long amount) {
		this.itemId = itemId;
		this.metricId = metricId;
		this.amount = amount;
	}

	public Long getId() {
		return id;
	}

	public Long getItemId() {
		return itemId;
	}

	public Long getMetricId() {
		return metricId;
	}

	public long getAmount() {
		return amount;
	}

}
