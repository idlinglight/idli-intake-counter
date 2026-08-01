package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The password-hash guard, tested without a context: a hash that survives
 * startup but cannot match anything produces an app that looks healthy to the
 * kubelet and answers 401 to its owner forever.
 */
class SecurityConfigTest {

	private final SecurityConfig config = new SecurityConfig();

	@Test
	void acceptsAMintedHash() {
		assertThatCode(() -> config.userDetailsService(TestAuth.PASSWORD_HASH)).doesNotThrowAnyException();
	}

	@Test
	void keepsTheHashItWasGiven() {
		assertThat(config.userDetailsService(TestAuth.PASSWORD_HASH).loadUserByUsername(TestAuth.USERNAME)
				.getPassword()).isEqualTo("{bcrypt}" + TestAuth.PASSWORD_HASH);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"placeholder",
			// The whole htpasswd line, ':' and all.
			":$2y$10$D9SLsk6jSCVjHHSqAnIz3ew6xjgTD1pUB//pXvqn85ASYnnJCdGJi",
			// A hand-added prefix — the delegating encoder strips only one.
			"{bcrypt}$2y$10$D9SLsk6jSCVjHHSqAnIz3ew6xjgTD1pUB//pXvqn85ASYnnJCdGJi",
			// Truncated / line-wrapped Secret value.
			"$2y$10$D9SLsk6jSCVjHHSqAnIz3ew6xjgTD1pUB",
			// Not bcrypt at all (htpasswd's default MD5 variant).
			"$apr1$vBmPZbZR$Bpu2yZBjWrqAgQGdBLqYo1" })
	void rejectsAMalformedHash(String hash) {
		assertThatIllegalArgumentException().isThrownBy(() -> config.userDetailsService(hash))
				.withMessageContaining("bare bcrypt hash");
	}

	@Test
	void rejectsAnEmptyHash() {
		assertThatIllegalArgumentException().isThrownBy(() -> config.userDetailsService(" "))
				.withMessageContaining("must not be empty");
	}

}
