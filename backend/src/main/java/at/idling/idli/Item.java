package at.idling.idli;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * A loggable thing ("protein bar: PS White Choc"). Its metric amounts live in
 * {@link ItemAmount} rows and are expressed per basis quantity (basisAmount in
 * basisUnit, e.g. "per 100 g"), so nutrition-label data enters verbatim
 * (ADR-0008). Entries never reference items — logging copies computed amounts
 * (ADR-0007).
 */
@Entity
public class Item {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	private Long basisAmount;

	private String basisUnit;

	protected Item() {
	}

	public Item(String name, Long basisAmount, String basisUnit) {
		this.name = name;
		this.basisAmount = basisAmount;
		this.basisUnit = basisUnit;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public Long getBasisAmount() {
		return basisAmount;
	}

	public String getBasisUnit() {
		return basisUnit;
	}

	public void rename(String name) {
		this.name = name;
	}

	public void rebase(Long basisAmount, String basisUnit) {
		this.basisAmount = basisAmount;
		this.basisUnit = basisUnit;
	}

}
