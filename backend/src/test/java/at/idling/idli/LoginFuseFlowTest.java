package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * What a blown login fuse does to real requests (ADR-0009). A context of its
 * own, with a fuse that blows on the third failed check — and thrown away
 * afterwards: once blown it stays blown, which is the point of it and poison
 * for any test class that would inherit the context.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		TestAuth.PASSWORD_HASH_PROPERTY, "idli.auth.fuse.max-failed-checks=3" })
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginFuseFlowTest {

	@Autowired
	private TestRestTemplate restTemplate;

	// One story, in order: the fuse has no way back, so there is no second act.
	@Test
	void aBlownFuseClosesPasswordLoginAndNothingElse() {
		ResponseEntity<Void> before = formLogin(TestAuth.USERNAME, TestAuth.PASSWORD);
		assertThat(before.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		String session = sessionCookie(before);

		// Three failed checks, one per way of failing. Each still gets its 401:
		// counting any of them twice would answer the third with 418 already,
		// not counting one would let the fourth request below through.
		assertThat(formLogin(TestAuth.USERNAME, "wrong-password").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(basic(TestAuth.USERNAME, "wrong-password").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// An unknown username fails in Spring Security's dummy check.
		assertThat(formLogin("nobody", TestAuth.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		// Blown. The RIGHT password is refused now, through both doors ...
		ResponseEntity<Void> login = formLogin(TestAuth.USERNAME, TestAuth.PASSWORD);
		assertThat(login.getStatusCode().value()).isEqualTo(418);
		assertThat(login.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE))
				.noneMatch(cookie -> cookie.startsWith("JSESSIONID="));
		ResponseEntity<Void> basic = basic(TestAuth.USERNAME, TestAuth.PASSWORD);
		assertThat(basic.getStatusCode().value()).isEqualTo(418);
		assertThat(basic.getHeaders().getOrEmpty(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
		// ... and there is no "try again in a moment" to promise.
		assertThat(basic.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();

		// Whoever was logged in stays logged in: a session costs no bcrypt.
		HttpHeaders withSession = new HttpHeaders();
		withSession.add(HttpHeaders.COOKIE, session);
		assertThat(restTemplate.exchange("/api/days/today", HttpMethod.GET, new HttpEntity<>(withSession),
				DayViewDto.class).getStatusCode()).isEqualTo(HttpStatus.OK);

		// Requests without a password are nobody's business here either.
		assertThat(restTemplate.getForEntity("/api/hello", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(restTemplate.getForEntity("/api/days/today", Void.class).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(restTemplate.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
	}

	// The login POST is CSRF-exempt (see SecurityConfig), so no token prefetch.
	private ResponseEntity<Void> formLogin(String username, String password) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", username);
		form.add("password", password);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		return restTemplate.exchange("/api/auth/login", HttpMethod.POST, new HttpEntity<>(form, headers), Void.class);
	}

	private ResponseEntity<Void> basic(String username, String password) {
		return restTemplate.withBasicAuth(username, password).getForEntity("/api/days/today", Void.class);
	}

	private static String sessionCookie(ResponseEntity<?> response) {
		return response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
				.filter(cookie -> cookie.startsWith("JSESSIONID="))
				.map(cookie -> cookie.split(";", 2)[0])
				.findFirst()
				.orElseThrow();
	}

}
