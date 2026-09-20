package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

/**
 * The fuse's counting, against a hand-moved clock. What a blown fuse does to
 * requests is LoginFuseFlowTest's business.
 */
class LoginFuseTest {

	private static final Duration WINDOW = Duration.ofHours(1);

	private Instant now = Instant.parse("2026-09-20T12:00:00Z");

	private final LoginFuse fuse = new LoginFuse(3, WINDOW, () -> now);

	@Test
	void blowsWithTheLastAllowedFailedCheck() {
		fuse.recordFailedCheck();
		fuse.recordFailedCheck();
		assertThat(fuse.isBlown()).isFalse();

		fuse.recordFailedCheck();
		assertThat(fuse.isBlown()).isTrue();
	}

	@Test
	void failedChecksOutsideTheWindowDoNotAddUp() {
		// One failed check every 31 minutes, for a day: never three in an hour.
		for (int i = 0; i < 48; i++) {
			fuse.recordFailedCheck();
			now = now.plus(Duration.ofMinutes(31));
		}

		assertThat(fuse.isBlown()).isFalse();
	}

	@Test
	void aFailedCheckExactlyOneWindowOldNoLongerCounts() {
		fuse.recordFailedCheck();
		now = now.plus(Duration.ofMinutes(30));
		fuse.recordFailedCheck();
		now = now.plus(Duration.ofMinutes(30));

		// The first one is exactly an hour old now and drops out ...
		fuse.recordFailedCheck();
		assertThat(fuse.isBlown()).isFalse();

		// ... but the two after it are still inside the window.
		now = now.plus(Duration.ofMinutes(29));
		fuse.recordFailedCheck();
		assertThat(fuse.isBlown()).isTrue();
	}

	@Test
	void staysBlownHoweverMuchTimePasses() {
		fuse.recordFailedCheck();
		fuse.recordFailedCheck();
		fuse.recordFailedCheck();

		// A fuse, not a circuit breaker: the window only ever decides whether
		// it blows, never whether it recovers.
		now = now.plus(Duration.ofDays(365));
		assertThat(fuse.isBlown()).isTrue();
	}

	@Test
	void rejectsALimitOrWindowThatCannotWork() {
		assertThatIllegalArgumentException().isThrownBy(() -> new LoginFuse(0, WINDOW, () -> now))
				.withMessageContaining("idli.auth.fuse.max-failed-checks");
		assertThatIllegalArgumentException().isThrownBy(() -> new LoginFuse(3, Duration.ZERO, () -> now))
				.withMessageContaining("idli.auth.fuse.window");
		assertThatIllegalArgumentException().isThrownBy(() -> new LoginFuse(3, Duration.ofHours(-1), () -> now))
				.withMessageContaining("idli.auth.fuse.window");
	}

}
