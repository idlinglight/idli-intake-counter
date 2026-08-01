package at.idling.idli;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.Assert;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	// ADR-0005: exactly one user, so the username is a fixed constant and
	// authorization is simply "authenticated or not".
	static final String USERNAME = "user";

	@Bean
	UserDetailsService userDetailsService(@Value("${idli.auth.password-hash}") String passwordHash) {
		// No default for the hash (see application.yaml): a missing value fails
		// startup here instead of producing an app nobody can log in to.
		Assert.hasText(passwordHash, "idli.auth.password-hash must not be empty");
		return new InMemoryUserDetailsManager(
				User.withUsername(USERNAME).password("{bcrypt}" + passwordHash).roles("USER").build());
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		// Programmatic clients (curl, tests) authenticate per request via HTTP
		// Basic and carry no ambient cookie authority, so CSRF does not apply.
		RequestMatcher basicAuthRequest = request -> {
			String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
			return authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
		};

		http
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/api/hello", "/api/auth/**").permitAll()
						.requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
						// Health stays open for the kubelet probes.
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				// spa() = CookieCsrfTokenRepository.withHttpOnlyFalse() + a handler that
				// accepts the raw token from the X-XSRF-TOKEN header and eagerly resolves
				// the deferred token, so the XSRF-TOKEN cookie is actually written.
				.csrf(csrf -> csrf
						.spa()
						.requireCsrfProtectionMatcher(new AndRequestMatcher(
								CsrfFilter.DEFAULT_CSRF_MATCHER, new NegatedRequestMatcher(basicAuthRequest))))
				.httpBasic(Customizer.withDefaults())
				// Session login for the SPA: plain status codes, no redirects.
				.formLogin(login -> login
						.loginProcessingUrl("/api/auth/login")
						.successHandler((request, response, authentication) -> response
								.setStatus(HttpStatus.NO_CONTENT.value()))
						.failureHandler((request, response, exception) -> response
								.setStatus(HttpStatus.UNAUTHORIZED.value())))
				.logout(logout -> logout
						.logoutUrl("/api/auth/logout")
						.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
				// An API answers 401 to unauthenticated requests, never a login redirect.
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
		return http.build();
	}

}
