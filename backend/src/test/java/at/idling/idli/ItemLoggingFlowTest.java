package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Exercises ad-hoc item logging (issue #15) over real HTTP: a weighed quantity
 * in the item's basis unit is snapshotted into grouped entries exactly like a
 * serving log — same computation, same group-as-tag semantics — with the
 * quantity itself as the label ("pasta – 137 g").
 *
 * The DB is shared with the other flow-test classes (cached context, no
 * teardown): entries land on 2026-06-06 (2026-05-05 belongs to the serving
 * flow), catalog names carry an "itemlog-" prefix, every method creates its
 * own catalog, and day-view assertions filter by group id.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = TestAuth.PASSWORD_HASH_PROPERTY)
@AutoConfigureTestRestTemplate
class ItemLoggingFlowTest {

	private TestRestTemplate plainTemplate;
	private TestRestTemplate restTemplate;

	@Autowired
	void setRestTemplate(TestRestTemplate restTemplate) {
		this.plainTemplate = restTemplate;
		this.restTemplate = restTemplate.withBasicAuth(TestAuth.USERNAME, TestAuth.PASSWORD);
	}

	@Test
	void loggingRequiresAuthentication() {
		assertThat(plainTemplate.postForEntity("/api/items/1/entries", new NewItemEntryRequest(137L, null),
				String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void loggingAnAdhocQuantitySnapshotsComputedAmountsAsAGroup() {
		// Weighed-portion story: 1521 kJ + 5 g protein per 100 g, 137 g on the
		// scale → 2083.77 kJ rounds up, 6.85 g rounds up.
		ItemDto item = pasta("itemlog-pasta", 1521, 5);

		EntryGroupDto group = logItem(item.id(),
				new NewItemEntryRequest(137L, Instant.parse("2026-06-06T08:00:00Z")));

		assertThat(group.label()).isEqualTo("itemlog-pasta – 137 g");
		assertThat(group.loggedAt()).isEqualTo(Instant.parse("2026-06-06T08:00:00Z"));
		assertThat(group.entries()).extracting(EntryDto::amount).containsExactly(2084L, 7L);
		assertThat(group.entries()).allSatisfy(entry -> {
			assertThat(entry.groupId()).isEqualTo(group.groupId());
			assertThat(entry.label()).isEqualTo(group.label());
			assertThat(entry.loggedAt()).isEqualTo(group.loggedAt());
		});

		// The day view carries the group fields on the same plain entries.
		List<EntryDto> dayEntries = groupEntriesInDay(group.groupId());
		assertThat(dayEntries).hasSize(2);
		assertThat(dayEntries).allSatisfy(entry -> assertThat(entry.label()).isEqualTo("itemlog-pasta – 137 g"));
	}

	@Test
	void servingLessItemsAreLoggable() {
		// The whole point of the feature: composition alone is enough — no
		// serving was ever authored for this item.
		MetricDto energy = TestCatalog.createMetric(restTemplate, "itemlog-bare-energy", "kJ");
		ItemDto item = TestCatalog.createItem(restTemplate, "itemlog-bare", 100L, "g",
				List.of(new ItemAmountDto(energy.id(), 250L)));

		EntryGroupDto group = logItem(item.id(),
				new NewItemEntryRequest(50L, Instant.parse("2026-06-06T09:00:00Z")));

		assertThat(group.label()).isEqualTo("itemlog-bare – 50 g");
		assertThat(group.entries()).extracting(EntryDto::metricId, EntryDto::amount)
				.containsExactly(tuple(energy.id(), 125L));
	}

	@Test
	void amountRoundingToZeroRejectsTheWholeLog() {
		// 1 mg trace per 100 g: 25 g computes 0.25 → 0 — the whole log must be
		// refused, not silently thinned (ADR-0007: loud).
		MetricDto energy = TestCatalog.createMetric(restTemplate, "itemlog-zero-energy", "kJ");
		MetricDto trace = TestCatalog.createMetric(restTemplate, "itemlog-zero-trace", "mg");
		ItemDto item = TestCatalog.createItem(restTemplate, "itemlog-zero", 100L, "g",
				List.of(new ItemAmountDto(energy.id(), 2281L), new ItemAmountDto(trace.id(), 1L)));

		ResponseEntity<String> response = restTemplate.postForEntity("/api/items/" + item.id() + "/entries",
				new NewItemEntryRequest(25L, Instant.parse("2026-06-06T10:00:00Z")), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("itemlog-zero-trace").contains("refusing a silent no-op");
		// No partial group: nothing of this item reached the day.
		DayViewDto day = getDay();
		assertThat(day.entries()).noneSatisfy(
				entry -> assertThat(entry.label()).isNotNull().startsWith("itemlog-zero –"));
	}

	@Test
	void unknownItemIsA404() {
		assertThat(restTemplate.postForEntity("/api/items/999999/entries",
				new NewItemEntryRequest(137L, null), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void invalidQuantitiesAreRejected() {
		ItemDto item = pasta("itemlog-badqty", 1521, 5);

		for (Long quantity : new Long[] { null, 0L, -137L }) {
			ResponseEntity<String> response = restTemplate.postForEntity("/api/items/" + item.id() + "/entries",
					new NewItemEntryRequest(quantity, Instant.parse("2026-06-06T11:00:00Z")), String.class);
			assertThat(response.getStatusCode()).as("quantity = %s", quantity).isEqualTo(HttpStatus.BAD_REQUEST);
		}
	}

	/** kJ + g metrics and an item "per 100 g" under {@code prefix} — no servings. */
	private ItemDto pasta(String prefix, long energyPerBasis, long proteinPerBasis) {
		MetricDto energy = TestCatalog.createMetric(restTemplate, prefix + "-energy", "kJ");
		MetricDto protein = TestCatalog.createMetric(restTemplate, prefix + "-protein", "g");
		return TestCatalog.createItem(restTemplate, prefix, 100L, "g",
				List.of(new ItemAmountDto(energy.id(), energyPerBasis), new ItemAmountDto(protein.id(), proteinPerBasis)));
	}

	private EntryGroupDto logItem(long itemId, NewItemEntryRequest request) {
		ResponseEntity<EntryGroupDto> response = restTemplate.postForEntity(
				"/api/items/" + itemId + "/entries", request, EntryGroupDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private DayViewDto getDay() {
		ResponseEntity<DayViewDto> response = restTemplate.getForEntity("/api/days/2026-06-06", DayViewDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private List<EntryDto> groupEntriesInDay(UUID groupId) {
		return getDay().entries().stream().filter(entry -> groupId.equals(entry.groupId())).toList();
	}

}
