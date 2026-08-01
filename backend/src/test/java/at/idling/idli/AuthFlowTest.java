package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = TestAuth.PASSWORD_HASH_PROPERTY)
@AutoConfigureTestRestTemplate
class AuthFlowTest {

	private static final String XSRF_COOKIE = "XSRF-TOKEN";
	private static final String XSRF_HEADER = "X-XSRF-TOKEN";

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	void apiRequiresAuthentication() {
		ResponseEntity<Void> response = restTemplate.getForEntity("/api/days/today", Void.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void wrongBasicAuthPasswordIsRejected() {
		ResponseEntity<Void> response = restTemplate.withBasicAuth(TestAuth.USERNAME, "wrong-password")
				.getForEntity("/api/days/today", Void.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void basicAuthGrantsAccess() {
		ResponseEntity<DayViewDto> response = restTemplate.withBasicAuth(TestAuth.USERNAME, TestAuth.PASSWORD)
				.getForEntity("/api/days/today", DayViewDto.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void livenessProbeStaysPublic() {
		// The kubelet probes without credentials.
		ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health/liveness", String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void formLoginWithWrongPasswordIsRejected() {
		Map<String, String> cookies = new LinkedHashMap<>();

		ResponseEntity<Void> response = formLogin(cookies, "wrong-password");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(sessionStatus(cookies)).isFalse();
	}

	@Test
	void formLoginLogoutSessionLifecycle() {
		Map<String, String> cookies = new LinkedHashMap<>();
		assertThat(sessionStatus(cookies)).isFalse();

		assertThat(formLogin(cookies, TestAuth.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(cookies).containsKey("JSESSIONID");

		// The session cookie now carries authentication ...
		assertThat(get("/api/days/today", cookies, DayViewDto.class).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(sessionStatus(cookies)).isTrue();

		// ... until logout invalidates the session server-side.
		ResponseEntity<Void> logout = post("/api/auth/logout", null, cookies, true, Void.class);
		assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(get("/api/days/today", cookies, Void.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(sessionStatus(cookies)).isFalse();
	}

	@Test
	void sessionWritesRequireTheCsrfToken() {
		Map<String, String> cookies = new LinkedHashMap<>();
		assertThat(formLogin(cookies, TestAuth.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		// Login rotates the CSRF token; a follow-up GET re-issues the cookie.
		sessionStatus(cookies);
		assertThat(cookies).containsKey(XSRF_COOKIE);

		NewEntryRequest entry = new NewEntryRequest(waterMetricId(), 100L, null);

		// Session cookie alone, no X-XSRF-TOKEN header: rejected.
		ResponseEntity<String> withoutToken = post("/api/entries", entry, cookies, false, String.class);
		assertThat(withoutToken.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		// With the token echoed from the cookie (the SPA contract): accepted.
		ResponseEntity<EntryDto> withToken = post("/api/entries", entry, cookies, true, EntryDto.class);
		assertThat(withToken.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void basicAuthRequestsAreExemptFromCsrf() {
		// Programmatic clients carry no ambient cookie authority.
		ResponseEntity<EntryDto> response = restTemplate.withBasicAuth(TestAuth.USERNAME, TestAuth.PASSWORD)
				.postForEntity("/api/entries", new NewEntryRequest(waterMetricId(), 100L, null), EntryDto.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private boolean sessionStatus(Map<String, String> cookies) {
		ResponseEntity<SessionDto> response = get("/api/auth/session", cookies, SessionDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		return response.getBody().authenticated();
	}

	private ResponseEntity<Void> formLogin(Map<String, String> cookies, String password) {
		// The login POST is itself CSRF-protected: fetch the XSRF-TOKEN cookie
		// first, exactly like the SPA does.
		sessionStatus(cookies);

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", TestAuth.USERNAME);
		form.add("password", password);
		HttpHeaders headers = cookieHeaders(cookies, true);
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		ResponseEntity<Void> response = restTemplate.exchange("/api/auth/login", HttpMethod.POST,
				new HttpEntity<>(form, headers), Void.class);
		storeCookies(cookies, response);
		return response;
	}

	private <T> ResponseEntity<T> get(String url, Map<String, String> cookies, Class<T> type) {
		HttpHeaders headers = cookieHeaders(cookies, false);

		ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), type);
		storeCookies(cookies, response);
		return response;
	}

	private <T> ResponseEntity<T> post(String url, Object body, Map<String, String> cookies, boolean withCsrfHeader,
			Class<T> type) {
		HttpHeaders headers = cookieHeaders(cookies, withCsrfHeader);

		ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), type);
		storeCookies(cookies, response);
		return response;
	}

	private HttpHeaders cookieHeaders(Map<String, String> cookies, boolean withCsrfHeader) {
		HttpHeaders headers = new HttpHeaders();
		if (!cookies.isEmpty()) {
			headers.add(HttpHeaders.COOKIE, cookies.entrySet().stream()
					.map(cookie -> cookie.getKey() + "=" + cookie.getValue())
					.collect(Collectors.joining("; ")));
		}
		if (withCsrfHeader && cookies.containsKey(XSRF_COOKIE)) {
			headers.add(XSRF_HEADER, cookies.get(XSRF_COOKIE));
		}
		return headers;
	}

	// Minimal cookie jar: TestRestTemplate does not track cookies, and the
	// session flow needs JSESSIONID and XSRF-TOKEN carried across requests.
	private void storeCookies(Map<String, String> cookies, ResponseEntity<?> response) {
		for (String setCookie : response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
			String pair = setCookie.split(";", 2)[0];
			int separator = pair.indexOf('=');
			String name = pair.substring(0, separator);
			String value = pair.substring(separator + 1);
			if (value.isEmpty()) {
				// Max-Age=0 deletion, e.g. the rotated XSRF token on login.
				cookies.remove(name);
			} else {
				cookies.put(name, value);
			}
		}
	}

	private long waterMetricId() {
		ResponseEntity<List<MetricDto>> response = restTemplate.withBasicAuth(TestAuth.USERNAME, TestAuth.PASSWORD)
				.exchange(RequestEntity.method(HttpMethod.GET, URI.create("/api/metrics")).build(),
						new ParameterizedTypeReference<List<MetricDto>>() {
						});
		assertThat(response.getBody()).isNotNull();
		return response.getBody().stream()
				.filter(metric -> metric.name().equals("water"))
				.findFirst()
				.orElseThrow()
				.id();
	}

}
