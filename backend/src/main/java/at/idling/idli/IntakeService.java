package at.idling.idli;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class IntakeService {

	private final MetricRepository metricRepository;
	private final EntryRepository entryRepository;
	private final ZoneId zone;

	public IntakeService(MetricRepository metricRepository, EntryRepository entryRepository,
			@Value("${idli.zone:Europe/Vienna}") String zone) {
		this.metricRepository = metricRepository;
		this.entryRepository = entryRepository;
		this.zone = ZoneId.of(zone);
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

	public void delete(long entryId) {
		if (!entryRepository.existsById(entryId)) {
			throw new EntryNotFoundException(entryId);
		}
		entryRepository.deleteById(entryId);
	}

	public DayViewDto day(LocalDate date) {
		Instant from = date.atStartOfDay(zone).toInstant();
		Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
		List<Entry> entries = entryRepository
				.findByLoggedAtGreaterThanEqualAndLoggedAtLessThanOrderByLoggedAtDesc(from, to);

		Map<Long, Long> totalByMetricId = entries.stream()
				.collect(Collectors.groupingBy(Entry::getMetricId, Collectors.summingLong(Entry::getAmount)));
		List<TotalDto> totals = metricRepository.findAllById(totalByMetricId.keySet()).stream()
				.sorted(Comparator.comparing(Metric::getId))
				.map(metric -> new TotalDto(metric.getId(), metric.getName(), metric.getCanonicalUnit(),
						totalByMetricId.get(metric.getId())))
				.toList();

		return new DayViewDto(date, entries.stream().map(this::toDto).toList(), totals);
	}

	private EntryDto toDto(Entry entry) {
		return new EntryDto(entry.getId(), entry.getMetricId(), entry.getAmount(), entry.getLoggedAt());
	}

}
