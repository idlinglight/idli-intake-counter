package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Exercises the snapshot gesture of ADR-0007 over real HTTP: logging a serving
 * computes amounts from the catalog and copies them into grouped entries that
 * never look back.
 *
 * The DB is shared with the other flow-test classes (cached context, no
 * teardown): entries land on 2026-05-05 (2026-03-03 belongs to the water flow,
 * 2026-04-04 to export/import), catalog names carry a "servlog-" prefix, every
 * method creates its own catalog, and day-view assertions filter by group id
 * instead of asserting whole-day content.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = TestAuth.PASSWORD_HASH_PROPERTY)
@AutoConfigureTestRestTemplate
class ServingLoggingFlowTest {

	private TestRestTemplate plainTemplate;
	private TestRestTemplate restTemplate;

	@Autowired
	void setRestTemplate(TestRestTemplate restTemplate) {
		this.plainTemplate = restTemplate;
		this.restTemplate = restTemplate.withBasicAuth(TestAuth.USERNAME, TestAuth.PASSWORD);
	}

	@Test
	void loggingRequiresAuthentication() {
		assertThat(plainTemplate.postForEntity("/api/servings/1/entries", new NewEntryGroupRequest(null, null),
				String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(plainTemplate.exchange("/api/entry-groups/" + UUID.randomUUID(), HttpMethod.DELETE, null,
				String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void loggingAServingSnapshotsComputedAmountsAsAGroup() {
		// The protein-bar story, end to end: 2281 kJ + 30 g protein per 100 g,
		// "half bar" = 25 g → 570.25 kJ rounds down, 7.5 g rounds up.
		Catalog catalog = catalog("servlog-bar", 2281, 30, 25);

		EntryGroupDto group = logServing(catalog.servingId(),
				new NewEntryGroupRequest(null, Instant.parse("2026-05-05T08:00:00Z")));

		assertThat(group.label()).isEqualTo("servlog-bar – half bar");
		assertThat(group.loggedAt()).isEqualTo(Instant.parse("2026-05-05T08:00:00Z"));
		assertThat(group.entries()).extracting(EntryDto::metricId, EntryDto::amount)
				.containsExactly(tuple(catalog.energyId(), 570L), tuple(catalog.proteinId(), 8L));
		assertThat(group.entries()).allSatisfy(entry -> {
			assertThat(entry.groupId()).isEqualTo(group.groupId());
			assertThat(entry.label()).isEqualTo(group.label());
			assertThat(entry.loggedAt()).isEqualTo(group.loggedAt());
		});

		// The day view carries the group fields on the same plain entries.
		List<EntryDto> dayEntries = groupEntriesInDay(group.groupId());
		assertThat(dayEntries).hasSize(2);
		assertThat(dayEntries).allSatisfy(entry -> assertThat(entry.label()).isEqualTo("servlog-bar – half bar"));
	}

	@Test
	void multiplierScalesAmountsAndLabels() {
		Catalog catalog = catalog("servlog-mult", 2281, 30, 25);

		EntryGroupDto doubled = logServing(catalog.servingId(),
				new NewEntryGroupRequest(new BigDecimal("2"), Instant.parse("2026-05-05T09:00:00Z")));
		assertThat(doubled.label()).isEqualTo("servlog-mult – half bar ×2");
		// 1140.5 rounds half-up; 15 is exact.
		assertThat(doubled.entries()).extracting(EntryDto::amount).containsExactly(1141L, 15L);

		// "0.50" still renders as ×0.5 — trailing zeros never reach the label.
		EntryGroupDto halved = logServing(catalog.servingId(),
				new NewEntryGroupRequest(new BigDecimal("0.50"), Instant.parse("2026-05-05T09:30:00Z")));
		assertThat(halved.label()).isEqualTo("servlog-mult – half bar ×0.5");
		// 285.125 rounds down; 3.75 rounds up.
		assertThat(halved.entries()).extracting(EntryDto::amount).containsExactly(285L, 4L);
	}

	@Test
	void loggedAtDefaultsToNowAndAnEmptyBodyIsValid() {
		Catalog catalog = catalog("servlog-now", 2281, 30, 25);
		Instant before = Instant.now();

		// The SPA sends a literal {} when no knob is turned.
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<EntryGroupDto> response = restTemplate.postForEntity(
				"/api/servings/" + catalog.servingId() + "/entries", new HttpEntity<>("{}", headers),
				EntryGroupDto.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		EntryGroupDto group = response.getBody();
		assertThat(group).isNotNull();
		assertThat(group.loggedAt()).isBetween(before, Instant.now());
		assertThat(group.label()).isEqualTo("servlog-now – half bar");

		// Leave "today" as we found it (other classes assert around now).
		assertThat(restTemplate.exchange("/api/entry-groups/" + group.groupId(), HttpMethod.DELETE, null,
				Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
	}

	@Test
	void groupDeleteIsAtomicAndHonestlyAbsentAfterwards() {
		Catalog catalog = catalog("servlog-del", 2281, 30, 25);
		EntryGroupDto group = logServing(catalog.servingId(),
				new NewEntryGroupRequest(null, Instant.parse("2026-05-05T11:00:00Z")));
		assertThat(groupEntriesInDay(group.groupId())).hasSize(2);

		assertThat(restTemplate.exchange("/api/entry-groups/" + group.groupId(), HttpMethod.DELETE, null,
				Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(groupEntriesInDay(group.groupId())).isEmpty();
		assertThat(restTemplate.exchange("/api/entry-groups/" + group.groupId(), HttpMethod.DELETE, null,
				String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(restTemplate.exchange("/api/entry-groups/not-a-uuid", HttpMethod.DELETE, null,
				String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void loggedEntriesSurviveCatalogRenamesAndDeletion() {
		Catalog catalog = catalog("servlog-snap", 2281, 30, 25);
		EntryGroupDto group = logServing(catalog.servingId(),
				new NewEntryGroupRequest(null, Instant.parse("2026-05-05T12:00:00Z")));

		// Rewrite history's sources: rename the item, change its composition,
		// rename the serving, then delete the item outright.
		assertThat(restTemplate.exchange("/api/items/" + catalog.itemId(), HttpMethod.PUT,
				new HttpEntity<>(new ItemRequest("servlog-snap-renamed", 50L, "mL",
						List.of(new ItemAmountDto(catalog.energyId(), 1L)))),
				ItemDto.class).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(restTemplate.exchange("/api/servings/" + catalog.servingId(), HttpMethod.PUT,
				new HttpEntity<>(new ServingRequest("renamed serving", 40L)), ServingDto.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(restTemplate.exchange("/api/items/" + catalog.itemId(), HttpMethod.DELETE, null, Void.class)
				.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		// The snapshot is untouched: same label, same amounts (ADR-0007).
		List<EntryDto> entries = groupEntriesInDay(group.groupId());
		assertThat(entries).extracting(EntryDto::amount).containsExactlyInAnyOrder(570L, 8L);
		assertThat(entries).allSatisfy(entry -> assertThat(entry.label()).isEqualTo("servlog-snap – half bar"));
	}

	@Test
	void amountRoundingToZeroRejectsTheWholeLog() {
		// 1 mg trace per 100 g: a 25 g serving computes 0.25 → 0. The energy
		// amount would be fine — the whole log must still be refused.
		MetricDto energy = createMetric("servlog-zero-energy", "kJ");
		MetricDto trace = createMetric("servlog-zero-trace", "mg");
		ItemDto item = createItem("servlog-zero", 100L, "g", List.of(new ItemAmountDto(energy.id(), 2281L),
				new ItemAmountDto(trace.id(), 1L)));
		ServingDto serving = addServing(item.id(), "half bar", 25L);

		ResponseEntity<String> response = restTemplate.postForEntity("/api/servings/" + serving.id() + "/entries",
				new NewEntryGroupRequest(null, Instant.parse("2026-05-05T14:00:00Z")), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("servlog-zero-trace").contains("refusing a silent no-op");
		// No partial group: nothing of this item reached the day.
		DayViewDto day = getDay();
		assertThat(day.entries()).noneSatisfy(
				entry -> assertThat(entry.label()).isNotNull().startsWith("servlog-zero –"));
	}

	@Test
	void unknownServingIsA404() {
		assertThat(restTemplate.postForEntity("/api/servings/999999/entries",
				new NewEntryGroupRequest(null, null), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void invalidMultipliersAreRejected() {
		Catalog catalog = catalog("servlog-badmult", 2281, 30, 25);

		for (String multiplier : new String[] { "0", "-1", "0.001", "1000" }) {
			ResponseEntity<String> response = restTemplate.postForEntity(
					"/api/servings/" + catalog.servingId() + "/entries",
					new NewEntryGroupRequest(new BigDecimal(multiplier), Instant.parse("2026-05-05T15:00:00Z")),
					String.class);
			assertThat(response.getStatusCode()).as("multiplier = %s", multiplier)
					.isEqualTo(HttpStatus.BAD_REQUEST);
		}
	}

	/** itemId + servingId + metric ids for one self-created protein-bar-shaped catalog. */
	private record Catalog(long itemId, long servingId, long energyId, long proteinId) {
	}

	/** Creates metrics (kJ + g), an item "per 100 g" and a "half bar" serving under {@code prefix}. */
	private Catalog catalog(String prefix, long energyPerBasis, long proteinPerBasis, long servingQuantity) {
		MetricDto energy = createMetric(prefix + "-energy", "kJ");
		MetricDto protein = createMetric(prefix + "-protein", "g");
		ItemDto item = createItem(prefix, 100L, "g", List.of(new ItemAmountDto(energy.id(), energyPerBasis),
				new ItemAmountDto(protein.id(), proteinPerBasis)));
		ServingDto serving = addServing(item.id(), "half bar", servingQuantity);
		return new Catalog(item.id(), serving.id(), energy.id(), protein.id());
	}

	private MetricDto createMetric(String name, String canonicalUnit) {
		ResponseEntity<MetricDto> response = restTemplate.postForEntity("/api/metrics",
				new NewMetricRequest(name, canonicalUnit), MetricDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private ItemDto createItem(String name, Long basisAmount, String basisUnit, List<ItemAmountDto> amounts) {
		ResponseEntity<ItemDto> response = restTemplate.postForEntity("/api/items",
				new ItemRequest(name, basisAmount, basisUnit, amounts), ItemDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private ServingDto addServing(long itemId, String name, Long quantity) {
		ResponseEntity<ServingDto> response = restTemplate.postForEntity("/api/items/" + itemId + "/servings",
				new ServingRequest(name, quantity), ServingDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private EntryGroupDto logServing(long servingId, NewEntryGroupRequest request) {
		ResponseEntity<EntryGroupDto> response = restTemplate.postForEntity(
				"/api/servings/" + servingId + "/entries", request, EntryGroupDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private DayViewDto getDay() {
		ResponseEntity<DayViewDto> response = restTemplate.getForEntity("/api/days/2026-05-05", DayViewDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private List<EntryDto> groupEntriesInDay(UUID groupId) {
		return getDay().entries().stream().filter(entry -> groupId.equals(entry.groupId())).toList();
	}

}
