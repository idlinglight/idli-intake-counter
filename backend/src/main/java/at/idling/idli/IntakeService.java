package at.idling.idli;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class IntakeService {

	private final MetricRepository metricRepository;
	private final EntryRepository entryRepository;
	private final ServingRepository servingRepository;
	private final ItemRepository itemRepository;
	private final ItemAmountRepository itemAmountRepository;
	private final ZoneId zone;

	public IntakeService(MetricRepository metricRepository, EntryRepository entryRepository,
			ServingRepository servingRepository, ItemRepository itemRepository,
			ItemAmountRepository itemAmountRepository, ZoneId zone) {
		this.metricRepository = metricRepository;
		this.entryRepository = entryRepository;
		this.servingRepository = servingRepository;
		this.itemRepository = itemRepository;
		this.itemAmountRepository = itemAmountRepository;
		this.zone = zone;
	}

	public List<MetricDto> metrics() {
		return metricRepository.findAll().stream()
				.sorted(Comparator.comparing(Metric::getId))
				.map(metric -> new MetricDto(metric.getId(), metric.getName(), metric.getCanonicalUnit()))
				.toList();
	}

	public EntryDto log(NewEntryRequest request) {
		if (!metricRepository.existsById(request.metricId())) {
			throw new UnknownMetricException(request.metricId());
		}
		Instant loggedAt = request.loggedAt() != null ? request.loggedAt() : Instant.now();
		Entry saved = entryRepository.save(new Entry(request.metricId(), request.amount(), loggedAt));
		return toDto(saved);
	}

	/**
	 * The snapshot gesture of ADR-0007: computes the serving's per-metric
	 * amounts from the item's composition and copies them into plain entries
	 * sharing a fresh group id and a label composed here — no catalog
	 * references, so later renames/deletes never touch what was logged.
	 */
	// REPEATABLE_READ for the same reason as AuthoringService.items(): serving,
	// item and amounts must come from one snapshot, or a racing import could
	// pair a serving with a foreign item's composition.
	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public EntryGroupDto logServing(long servingId, NewEntryGroupRequest request) {
		Serving serving = servingRepository.findById(servingId)
				.orElseThrow(() -> new ServingNotFoundException(servingId));
		Item item = itemRepository.findById(serving.getItemId())
				.orElseThrow(() -> new IllegalStateException(
						"serving " + servingId + " references missing item id " + serving.getItemId()));
		List<ItemAmount> amounts = itemAmountRepository.findByItemId(item.getId()).stream()
				.sorted(Comparator.comparing(ItemAmount::getMetricId))
				.toList();
		// Belt: authoring and import both refuse composition-less items.
		if (amounts.isEmpty()) {
			throw new InvalidEntryGroupException("item '" + item.getName() + "' has no composition; nothing to log");
		}

		BigDecimal multiplier = request.multiplier() != null ? request.multiplier() : BigDecimal.ONE;
		Instant loggedAt = request.loggedAt() != null ? request.loggedAt() : Instant.now();
		UUID groupId = UUID.randomUUID();
		String label = label(item, serving, multiplier);

		// Compute everything before writing anything: a rejection must be a
		// no-op, never a partial group.
		List<Entry> entries = amounts.stream()
				.map(amount -> new Entry(amount.getMetricId(),
						computedAmount(amount, serving, multiplier, item, label), loggedAt, groupId, label))
				.toList();
		return new EntryGroupDto(groupId, label, loggedAt,
				entryRepository.saveAll(entries).stream().map(this::toDto).toList());
	}

	public void delete(long entryId) {
		if (entryRepository.deleteEntryById(entryId) == 0) {
			throw new EntryNotFoundException(entryId);
		}
	}

	public void deleteGroup(UUID groupId) {
		if (entryRepository.deleteEntryGroupById(groupId) == 0) {
			throw new EntryGroupNotFoundException(groupId);
		}
	}

	public DayViewDto today() {
		return day(LocalDate.now(zone));
	}

	public DayViewDto day(LocalDate date) {
		Instant from = date.atStartOfDay(zone).toInstant();
		Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
		List<Entry> entries = entryRepository
				.findByLoggedAtGreaterThanEqualAndLoggedAtLessThanOrderByLoggedAtDescIdDesc(from, to);

		Map<Long, Long> totalByMetricId = entries.stream()
				.collect(Collectors.groupingBy(Entry::getMetricId, Collectors.summingLong(Entry::getAmount)));
		List<TotalDto> totals = metricRepository.findAllById(totalByMetricId.keySet()).stream()
				.sorted(Comparator.comparing(Metric::getId))
				.map(metric -> new TotalDto(metric.getId(), metric.getName(), metric.getCanonicalUnit(),
						totalByMetricId.get(metric.getId())))
				.toList();

		return new DayViewDto(date, entries.stream().map(this::toDto).toList(), totals);
	}

	/** composition × quantity × multiplier ÷ basis, rounded half-up to the canonical integer. */
	private long computedAmount(ItemAmount amount, Serving serving, BigDecimal multiplier, Item item, String label) {
		long computed = BigDecimal.valueOf(amount.getAmount())
				.multiply(BigDecimal.valueOf(serving.getQuantity()))
				.multiply(multiplier)
				.divide(BigDecimal.valueOf(item.getBasisAmount()), 0, RoundingMode.HALF_UP)
				.longValueExact();
		if (computed == 0) {
			// entry.amount has check (amount > 0); silently dropping the metric
			// instead would log less than the user believes (ADR-0007: loud).
			String metricName = metricRepository.findById(amount.getMetricId())
					.map(Metric::getName)
					.orElse("#" + amount.getMetricId());
			throw new InvalidEntryGroupException(
					"logging '" + label + "' would round metric '" + metricName + "' to 0; refusing a silent no-op");
		}
		return computed;
	}

	private static String label(Item item, Serving serving, BigDecimal multiplier) {
		String base = item.getName() + " – " + serving.getName();
		if (multiplier.compareTo(BigDecimal.ONE) == 0) {
			return base;
		}
		return base + " ×" + multiplier.stripTrailingZeros().toPlainString();
	}

	private EntryDto toDto(Entry entry) {
		return new EntryDto(entry.getId(), entry.getMetricId(), entry.getAmount(), entry.getLoggedAt(),
				entry.getGroupId(), entry.getLabel());
	}

}
