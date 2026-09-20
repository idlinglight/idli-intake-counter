package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.InstantSource;

/**
 * The guard's two promises, without a context and without real bcrypt: a
 * refusal never reaches the delegate, and a permit always comes back.
 */
class GuardedPasswordEncoderTest {

	private static final String RIGHT = "right-password";

	private final StubEncoder delegate = new StubEncoder();

	private final CredentialCheckPermits permits = new CredentialCheckPermits(2);

	private final LoginFuse fuse = new LoginFuse(3, Duration.ofHours(1), InstantSource.system());

	private final GuardedPasswordEncoder encoder = new GuardedPasswordEncoder(delegate, permits, fuse);

	@Test
	void passesTheDelegatesVerdictThrough() {
		assertThat(encoder.matches(RIGHT, RIGHT)).isTrue();
		assertThat(encoder.matches("wrong-password", RIGHT)).isFalse();
		assertThat(encoder.encode(RIGHT)).isEqualTo(RIGHT);
	}

	@Test
	void refusesWithoutTouchingTheDelegateWhenNoPermitIsFree() {
		assertThat(permits.tryAcquire()).isTrue();
		assertThat(permits.tryAcquire()).isTrue();

		// The right password too: the refusal comes before any look at it.
		assertThatExceptionOfType(CredentialCheckBusyException.class).isThrownBy(() -> encoder.matches(RIGHT, RIGHT));
		assertThatExceptionOfType(CredentialCheckBusyException.class).isThrownBy(() -> encoder.encode(RIGHT));
		assertThat(delegate.calls).isZero();
	}

	@Test
	void givesItsPermitBackAfterEveryOutcome() {
		CredentialCheckPermits single = new CredentialCheckPermits(1);
		GuardedPasswordEncoder guarded = new GuardedPasswordEncoder(delegate, single,
				new LoginFuse(100, Duration.ofHours(1), InstantSource.system()));

		// With one permit, any leak makes the very next call "busy".
		assertThat(guarded.matches(RIGHT, RIGHT)).isTrue();
		assertThat(guarded.matches("wrong-password", RIGHT)).isFalse();
		delegate.failure = new IllegalStateException("delegate blew up");
		assertThatIllegalStateException().isThrownBy(() -> guarded.matches(RIGHT, RIGHT));
		assertThatIllegalStateException().isThrownBy(() -> guarded.encode(RIGHT));
		delegate.failure = null;

		assertThat(guarded.matches(RIGHT, RIGHT)).isTrue();
	}

	@Test
	void onlyWrongPasswordsCountTowardsTheFuse() {
		for (int i = 0; i < 10; i++) {
			encoder.matches(RIGHT, RIGHT);
			encoder.encode(RIGHT);
		}
		assertThat(fuse.isBlown()).isFalse();

		encoder.matches("wrong-password", RIGHT);
		encoder.matches("wrong-password", RIGHT);
		assertThat(fuse.isBlown()).isFalse();
		encoder.matches("wrong-password", RIGHT);
		assertThat(fuse.isBlown()).isTrue();
	}

	@Test
	void aBusyRefusalIsNotAFailedCheck() {
		assertThat(permits.tryAcquire()).isTrue();
		assertThat(permits.tryAcquire()).isTrue();

		for (int i = 0; i < 10; i++) {
			assertThatExceptionOfType(CredentialCheckBusyException.class)
					.isThrownBy(() -> encoder.matches("wrong-password", RIGHT));
		}

		// Otherwise a flood that never gets a single password looked at would
		// still close the login.
		assertThat(fuse.isBlown()).isFalse();
	}

	@Test
	void refusesWithoutTouchingTheDelegateOnceTheFuseIsBlown() {
		for (int i = 0; i < 3; i++) {
			encoder.matches("wrong-password", RIGHT);
		}
		int callsBefore = delegate.calls;

		// The right password too — that is what "closed" means.
		assertThatExceptionOfType(PasswordLoginClosedException.class).isThrownBy(() -> encoder.matches(RIGHT, RIGHT));
		assertThatExceptionOfType(PasswordLoginClosedException.class).isThrownBy(() -> encoder.encode(RIGHT));
		assertThat(delegate.calls).isEqualTo(callsBefore);
	}

	@Test
	void rejectsLessThanOnePermit() {
		assertThatIllegalArgumentException().isThrownBy(() -> new CredentialCheckPermits(0))
				.withMessageContaining("idli.auth.max-concurrent-checks");
	}

	// "Encodes" to the password itself and counts how often it was asked.
	private static final class StubEncoder implements PasswordEncoder {

		private int calls;

		private RuntimeException failure;

		@Override
		public String encode(CharSequence rawPassword) {
			calls++;
			if (failure != null) {
				throw failure;
			}
			return rawPassword.toString();
		}

		@Override
		public boolean matches(CharSequence rawPassword, String encodedPassword) {
			calls++;
			if (failure != null) {
				throw failure;
			}
			return rawPassword.toString().equals(encodedPassword);
		}

	}

}
