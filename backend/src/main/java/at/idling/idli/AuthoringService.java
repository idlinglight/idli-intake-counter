package at.idling.idli;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Catalog authoring: metrics (create-only), items with their per-basis
 * composition, and servings (ADR-0008). All name-conflict checks are
 * check-then-insert; the DB's unique constraints are the backstop — a true
 * race would surface as a 500, which is acceptable for a single-user app.
 */
@Service
public class AuthoringService {

	private final MetricRepository metricRepository;
	private final ItemRepository itemRepository;
	private final ItemAmountRepository itemAmountRepository;
	private final ServingRepository servingRepository;

	public AuthoringService(MetricRepository metricRepository, ItemRepository itemRepository,
			ItemAmountRepository itemAmountRepository, ServingRepository servingRepository) {
		this.metricRepository = metricRepository;
		this.itemRepository = itemRepository;
		this.itemAmountRepository = itemAmountRepository;
		this.servingRepository = servingRepository;
	}

	@Transactional
	public MetricDto createMetric(NewMetricRequest request) {
		if (metricRepository.existsByName(request.name())) {
			throw new NameConflictException("metric", request.name());
		}
		Metric saved = metricRepository.save(new Metric(request.name(), request.canonicalUnit()));
		return new MetricDto(saved.getId(), saved.getName(), saved.getCanonicalUnit());
	}

	// REPEATABLE_READ for the same reason as ExportImportService.export():
	// the three findAll()s must share one snapshot, or an import committing
	// between them leaves items rendered with empty or stale composition.
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<ItemDto> items() {
		Map<Long, List<ItemAmount>> amountsByItemId = itemAmountRepository.findAll().stream()
				.collect(Collectors.groupingBy(ItemAmount::getItemId));
		Map<Long, List<Serving>> servingsByItemId = servingRepository.findAll().stream()
				.collect(Collectors.groupingBy(Serving::getItemId));
		return itemRepository.findAll().stream()
				.sorted(Comparator.comparing(Item::getId))
				.map(item -> toDto(item, amountsByItemId.getOrDefault(item.getId(), List.of()),
						servingsByItemId.getOrDefault(item.getId(), List.of())))
				.toList();
	}

	@Transactional
	public ItemDto createItem(ItemRequest request) {
		validateAmounts(request);
		if (itemRepository.existsByName(request.name())) {
			throw new NameConflictException("item", request.name());
		}
		Item item = itemRepository.save(new Item(request.name(), request.basisAmount(), request.basisUnit()));
		List<ItemAmount> amounts = saveAmounts(item.getId(), request);
		return toDto(item, amounts, List.of());
	}

	@Transactional
	public ItemDto replaceItem(long itemId, ItemRequest request) {
		validateAmounts(request);
		Item item = itemRepository.findById(itemId).orElseThrow(() -> new ItemNotFoundException(itemId));
		if (!item.getName().equals(request.name()) && itemRepository.existsByName(request.name())) {
			throw new NameConflictException("item", request.name());
		}
		item.rename(request.name());
		item.rebase(request.basisAmount(), request.basisUnit());
		itemRepository.save(item);
		itemAmountRepository.deleteByItemIdInBulk(itemId);
		List<ItemAmount> amounts = saveAmounts(itemId, request);
		return toDto(item, amounts, servingRepository.findByItemId(itemId));
	}

	public void deleteItem(long itemId) {
		if (itemRepository.deleteItemById(itemId) == 0) {
			throw new ItemNotFoundException(itemId);
		}
	}

	@Transactional
	public ServingDto addServing(long itemId, ServingRequest request) {
		if (!itemRepository.existsById(itemId)) {
			throw new ItemNotFoundException(itemId);
		}
		if (servingRepository.findByItemIdAndName(itemId, request.name()).isPresent()) {
			throw new NameConflictException("serving", request.name());
		}
		Serving saved = servingRepository.save(new Serving(itemId, request.name(), request.quantity()));
		return toDto(saved);
	}

	@Transactional
	public ServingDto replaceServing(long servingId, ServingRequest request) {
		Serving serving = servingRepository.findById(servingId)
				.orElseThrow(() -> new ServingNotFoundException(servingId));
		boolean taken = servingRepository.findByItemIdAndName(serving.getItemId(), request.name())
				.filter(other -> !other.getId().equals(servingId))
				.isPresent();
		if (taken) {
			throw new NameConflictException("serving", request.name());
		}
		serving.update(request.name(), request.quantity());
		return toDto(servingRepository.save(serving));
	}

	public void deleteServing(long servingId) {
		if (servingRepository.deleteServingById(servingId) == 0) {
			throw new ServingNotFoundException(servingId);
		}
	}

	private void validateAmounts(ItemRequest request) {
		Set<Long> metricIds = new HashSet<>();
		for (ItemAmountDto amount : request.amounts()) {
			if (!metricIds.add(amount.metricId())) {
				throw new InvalidItemException("duplicate metric id in amounts: " + amount.metricId());
			}
		}
		// One batched lookup instead of one existsById per row; the loop below
		// keeps the first-offender-in-request-order error semantics.
		Set<Long> knownIds = metricRepository.findAllById(metricIds).stream()
				.map(Metric::getId)
				.collect(Collectors.toSet());
		for (ItemAmountDto amount : request.amounts()) {
			if (!knownIds.contains(amount.metricId())) {
				throw new UnknownMetricException(amount.metricId());
			}
		}
	}

	private List<ItemAmount> saveAmounts(long itemId, ItemRequest request) {
		return itemAmountRepository.saveAll(request.amounts().stream()
				.map(amount -> new ItemAmount(itemId, amount.metricId(), amount.amount()))
				.toList());
	}

	private ItemDto toDto(Item item, List<ItemAmount> amounts, List<Serving> servings) {
		return new ItemDto(item.getId(), item.getName(), item.getBasisAmount(), item.getBasisUnit(),
				amounts.stream()
						.sorted(Comparator.comparing(ItemAmount::getMetricId))
						.map(amount -> new ItemAmountDto(amount.getMetricId(), amount.getAmount()))
						.toList(),
				servings.stream()
						.sorted(Comparator.comparing(Serving::getId))
						.map(this::toDto)
						.toList());
	}

	private ServingDto toDto(Serving serving) {
		return new ServingDto(serving.getId(), serving.getName(), serving.getQuantity());
	}

}
