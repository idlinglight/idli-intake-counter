package at.idling.idli;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.InstantSource;
import java.util.regex.Pattern;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	// ADR-0005: exactly one user, so the username is a fixed constant and
	// authorization is simply "authenticated or not".
	static final String USERNAME = "user";

	// The answer while the login fuse is blown (ADR-0009 has the long version).
	// 418 is reserved as "(Unused)" by RFC 9110 precisely because servers kept
	// using it for their own purposes — which is what makes it useful here: no
	// proxy, framework or browser between this app and its owner ever produces
	// or interprets one, so a 418 in an access log or behind curl can only mean
	// "fuse blown". 429 already means "busy", a 503 also comes from the ingress
	// when the pod is away, 403 is what a missing CSRF token gets, and 423 is
	// WebDAV's, with a body format of its own. A plain int: Spring deprecated
	// its enum constant for 418 in 7.0.
	static final int LOGIN_CLOSED_STATUS = 418;

	// A bare bcrypt hash as scripts/mint-auth-hash.sh emits it.
	private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

	@Bean
	UserDetailsService userDetailsService(@Value("${idli.auth.password-hash}") String passwordHash) {
		// No default for the hash (see application.yaml): a missing value fails
		// startup here instead of producing an app nobody can log in to.
		Assert.hasText(passwordHash, "idli.auth.password-hash must not be empty");
		// Non-empty is not enough. A malformed hash starts the app *healthy* —
		// probes go green, the rollout succeeds — and then rejects every login
		// forever, indistinguishable from a wrong password (BCryptPasswordEncoder
		// logs "does not look like BCrypt" and returns false). The mangles that
		// actually happen: pasting the whole htpasswd line including its leading
		// ':', hand-adding a '{bcrypt}' prefix, a line-wrapped Secret value.
		Assert.isTrue(BCRYPT_HASH.matcher(passwordHash).matches(),
				"idli.auth.password-hash must be a bare bcrypt hash ($2a/$2b/$2y$<cost>$<53 chars>): "
						+ "no '{bcrypt}' prefix, no leading ':' from the htpasswd line. "
						+ "Mint one with scripts/mint-auth-hash.sh");
		return new InMemoryUserDetailsManager(
				User.withUsername(USERNAME).password("{bcrypt}" + passwordHash).roles("USER").build());
	}

	// ADR-0009. The delegate is the encoder Spring Security would have picked
	// by itself (hence the "{bcrypt}" prefix above); declaring the bean only
	// puts the guard in front of it. It is the single PasswordEncoder bean, so
	// the auto-configured DaoAuthenticationProvider behind form login AND Basic
	// uses it.
	@Bean
	PasswordEncoder passwordEncoder(CredentialCheckPermits permits, LoginFuse fuse) {
		return new GuardedPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder(), permits, fuse);
	}

	@Bean
	CredentialCheckPermits credentialCheckPermits(
			@Value("${idli.auth.max-concurrent-checks:2}") int maxConcurrentChecks) {
		return new CredentialCheckPermits(maxConcurrentChecks);
	}

	@Bean
	LoginFuse loginFuse(@Value("${idli.auth.fuse.max-failed-checks:20}") int maxFailedChecks,
			@Value("${idli.auth.fuse.window:1h}") Duration window) {
		return new LoginFuse(maxFailedChecks, window, InstantSource.system());
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		// Programmatic clients (curl, tests) authenticate per request via HTTP
		// Basic and carry no ambient authority — but that premise must hold by
		// construction, not by comment, and it takes two things. First: the
		// exemption applies only when NO session exists, so a request carrying
		// both a live session cookie and a Basic header still needs the CSRF
		// token. Second, less obvious: the Basic entry point below never sends
		// a WWW-Authenticate challenge. A challenge would make the browser
		// cache the credentials for this origin and re-attach them by itself —
		// turning a cross-site POST into one that is authenticated, cookie-less
		// and therefore CSRF-exempt. SameSite=Lax does not help there; the
		// Authorization header is not a cookie.
		RequestMatcher statelessBasicRequest = request -> {
			String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
			boolean basic = authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
			return basic && request.getSession(false) == null;
		};

		// The login POST authenticates via the password in its body, not via
		// ambient cookie authority, so CSRF adds nothing here — and with a
		// single account, "login CSRF" gains an attacker nothing. Exempting it
		// also keeps the post-logout re-login unambiguous: a missing token
		// would otherwise surface as 401 (anonymous denials go through the
		// entry point), indistinguishable from a wrong password for the SPA.
		// Logout stays CSRF-protected (forced-logout nuisance otherwise).
		RequestMatcher loginRequest = request -> "POST".equals(request.getMethod())
				&& "/api/auth/login".equals(request.getRequestURI());

		// In a stateless double-submit scheme the cookie IS the protection, so
		// it gets the same hardening as the session cookie in application.yaml:
		// without Secure, anyone who can reach the host over plaintext plants a
		// token they already know and CSRF stops meaning anything. Static, for
		// the same reason as there — and localhost stays a trustworthy origin,
		// so local dev over http keeps working.
		CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
		csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));

		http
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/api/hello", "/api/auth/**").permitAll()
						.requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
						// Health stays open for the kubelet probes.
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				// spa() = CookieCsrfTokenRepository.withHttpOnlyFalse() + a handler that
				// accepts the raw token from the X-XSRF-TOKEN header and eagerly resolves
				// the deferred token, so the XSRF-TOKEN cookie is actually written. The
				// repository override keeps that handler and only swaps in the hardened
				// cookie above.
				.csrf(csrf -> csrf
						.spa()
						.csrfTokenRepository(csrfTokens)
						.requireCsrfProtectionMatcher(new AndRequestMatcher(
								CsrfFilter.DEFAULT_CSRF_MATCHER, new NegatedRequestMatcher(statelessBasicRequest),
								new NegatedRequestMatcher(loginRequest))))
				// A bare 401, never "WWW-Authenticate: Basic" — see statelessBasicRequest
				// above. Clients that use Basic here (curl -u, TestRestTemplate) send the
				// header preemptively and never needed the challenge.
				.httpBasic(basic -> basic
						.authenticationEntryPoint((request, response, failure) -> answerFailedAuthentication(
								response, failure)))
				// This API answers unauthenticated requests with a bare 401 and never
				// replays a saved request; the default RequestCache would create a
				// session for every anonymous hit on a protected endpoint.
				.requestCache(cache -> cache.requestCache(new NullRequestCache()))
				// Session login for the SPA: plain status codes, no redirects.
				.formLogin(login -> login
						.loginProcessingUrl("/api/auth/login")
						.successHandler((request, response, authentication) -> response
								.setStatus(HttpStatus.NO_CONTENT.value()))
						.failureHandler((request, response, failure) -> answerFailedAuthentication(response,
								failure)))
				.logout(logout -> logout
						.logoutUrl("/api/auth/logout")
						.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
				// An API answers 401 to unauthenticated requests, never a login redirect.
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
		return http.build();
	}

	// Shared by the form login and HTTP Basic, the two ways a password gets
	// here. The SPA tells the three answers apart (stores/auth.ts): folded into
	// one 401, "busy" and "closed" would read as "wrong password" — and the
	// owner would go hunting for a typo that is not there.
	private static void answerFailedAuthentication(HttpServletResponse response, AuthenticationException failure) {
		if (failure instanceof CredentialCheckBusyException) {
			response.setHeader(HttpHeaders.RETRY_AFTER, "1");
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		} else if (failure instanceof PasswordLoginClosedException) {
			response.setStatus(LOGIN_CLOSED_STATUS);
		} else {
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
		}
	}

}
