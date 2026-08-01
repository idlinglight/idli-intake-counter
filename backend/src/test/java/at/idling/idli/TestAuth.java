package at.idling.idli;

// The single test credential (ADR-0005: one fixed user). The hash is
// bcrypt(PASSWORD), minted like production hashes: scripts/mint-auth-hash.sh
// (htpasswd bcrypt, here cost 10 to keep per-request verification cheap).
final class TestAuth {

	static final String USERNAME = "user";

	static final String PASSWORD = "idli-test-password";

	static final String PASSWORD_HASH = "$2y$10$D9SLsk6jSCVjHHSqAnIz3ew6xjgTD1pUB//pXvqn85ASYnnJCdGJi";

	// For @SpringBootTest(properties = ...): the context refuses to start
	// without a hash, mirroring production fail-fast.
	static final String PASSWORD_HASH_PROPERTY = "idli.auth.password-hash=" + PASSWORD_HASH;

	private TestAuth() {
	}

}
