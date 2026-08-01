package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class WaterLoggingFlowTest {

	// Fixed past day so test methods cannot collide with entries logged "now".
	// The configured zone is Europe/Vienna (UTC+1 on this date).
	private static final LocalDate DAY = LocalDate.parse("2026-03-03");

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	void metricsListContainsWaterInMilliliters() {
		ResponseEntity<List<MetricDto>> response = restTemplate.exchange(
				RequestEntity.method(HttpMethod.GET, URI.create("/api/metrics")).build(),
				new ParameterizedTypeReference<List<MetricDto>>() {
				});

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).anySatisfy(metric -> {
			assertThat(metric.name()).isEqualTo("water");
			assertThat(metric.canonicalUnit()).isEqualTo("mL");
		});
	}

	@Test
	void todayEndpointResolvesTheDateInTheConfiguredZone() {
		LocalDate before = LocalDate.now(ZoneId.of("Europe/Vienna"));
		ResponseEntity<DayViewDto> response = restTemplate.getForEntity("/api/days/today", DayViewDto.class);
		LocalDate after = LocalDate.now(ZoneId.of("Europe/Vienna"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		// before/after bracket tolerates a midnight rollover during the call
		assertThat(response.getBody().date()).isIn(before, after);
	}

	@Test
	void logDayViewAndDeleteFlow() {
		long water = waterMetricId();

		EntryDto morning = postEntry(water, 250, Instant.parse("2026-03-03T08:00:00Z"));
		EntryDto midday = postEntry(water, 500, Instant.parse("2026-03-03T10:15:00Z"));
		// 23:30Z is already 00:30 of the NEXT day in Europe/Vienna.
		EntryDto nextDay = postEntry(water, 300, Instant.parse("2026-03-03T23:30:00Z"));

		assertThat(morning.metricId()).isEqualTo(water);
		assertThat(morning.loggedAt()).isEqualTo(Instant.parse("2026-03-03T08:00:00Z"));

		DayViewDto dayView = getDay(DAY);
		assertThat(dayView.date()).isEqualTo(DAY);
		assertThat(dayView.entries()).extracting(EntryDto::id).containsExactly(midday.id(), morning.id());
		assertThat(dayView.totals()).containsExactly(new TotalDto(water, "water", "mL", 750));

		DayViewDto nextDayView = getDay(DAY.plusDays(1));
		assertThat(nextDayView.entries()).extracting(EntryDto::id).containsExactly(nextDay.id());

		assertThat(deleteEntry(midday.id()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(deleteEntry(midday.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		DayViewDto afterDelete = getDay(DAY);
		assertThat(afterDelete.entries()).extracting(EntryDto::id).containsExactly(morning.id());
		assertThat(afterDelete.totals()).containsExactly(new TotalDto(water, "water", "mL", 250));
	}

	@Test
	void loggedAtDefaultsToNow() {
		Instant before = Instant.now();

		ResponseEntity<EntryDto> response = restTemplate.postForEntity("/api/entries",
				new NewEntryRequest(waterMetricId(), 100L, null), EntryDto.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().loggedAt()).isBetween(before, Instant.now());
	}

	@Test
	void unknownMetricIdIsRejected() {
		ResponseEntity<String> response = restTemplate.postForEntity("/api/entries",
				new NewEntryRequest(999_999L, 100L, null), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void nonPositiveAmountIsRejected() {
		long water = waterMetricId();

		for (Long amount : new Long[] { 0L, -100L, null }) {
			ResponseEntity<String> response = restTemplate.postForEntity("/api/entries",
					new NewEntryRequest(water, amount, null), String.class);

			assertThat(response.getStatusCode()).as("amount = %s", amount).isEqualTo(HttpStatus.BAD_REQUEST);
		}
	}

	private long waterMetricId() {
		ResponseEntity<List<MetricDto>> response = restTemplate.exchange(
				RequestEntity.method(HttpMethod.GET, URI.create("/api/metrics")).build(),
				new ParameterizedTypeReference<List<MetricDto>>() {
				});
		assertThat(response.getBody()).isNotNull();
		return response.getBody().stream()
				.filter(metric -> metric.name().equals("water"))
				.findFirst()
				.orElseThrow()
				.id();
	}

	private EntryDto postEntry(long metricId, long amount, Instant loggedAt) {
		ResponseEntity<EntryDto> response = restTemplate.postForEntity("/api/entries",
				new NewEntryRequest(metricId, amount, loggedAt), EntryDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private ResponseEntity<Void> deleteEntry(long entryId) {
		return restTemplate.exchange(
				RequestEntity.method(HttpMethod.DELETE, URI.create("/api/entries/" + entryId)).build(), Void.class);
	}

	private DayViewDto getDay(LocalDate date) {
		ResponseEntity<DayViewDto> response = restTemplate.getForEntity("/api/days/" + date, DayViewDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

}
