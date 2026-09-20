package at.idling.idli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Deque;

// ADR-0009: bounds the TOTAL work that wrong passwords can buy. The permits
// (CredentialCheckPermits) only bound the rate — that many cores, for as long
// as somebody keeps guessing. After maxFailedChecks failed checks inside one
// window the fuse blows and password verification stays off until the process
// restarts. A fuse, not a circuit breaker: nothing here ever closes it again.
// That is affordable because the one user is also the operator (ADR-0005) —
// deleting the pod is the release — and because sessions never come through
// here, so whoever is logged in stays logged in.
final class LoginFuse {

	private static final Logger log = LoggerFactory.getLogger(LoginFuse.class);

	private final int maxFailedChecks;
	private final Duration window;
	private final InstantSource clock;

	// The failed checks inside the window, oldest first. Windowed so that the
	// odd typo or drive-by probe never adds up over weeks; never grows past
	// maxFailedChecks.
	private final Deque<Instant> failedChecks = new ArrayDeque<>();

	private volatile boolean blown;

	LoginFuse(int maxFailedChecks, Duration window, InstantSource clock) {
		Assert.isTrue(maxFailedChecks >= 1, "idli.auth.fuse.max-failed-checks must be at least 1");
		Assert.isTrue(window.isPositive(), "idli.auth.fuse.window must be positive");
		this.maxFailedChecks = maxFailedChecks;
		this.window = window;
		this.clock = clock;
	}

	boolean isBlown() {
		return blown;
	}

	synchronized void recordFailedCheck() {
		if (blown) {
			return;
		}
		Instant now = clock.instant();
		Instant windowStart = now.minus(window);
		while (!failedChecks.isEmpty() && !failedChecks.peekFirst().isAfter(windowStart)) {
			failedChecks.removeFirst();
		}
		failedChecks.addLast(now);
		if (failedChecks.size() >= maxFailedChecks) {
			blown = true;
			failedChecks.clear();
			// Once, not per refused request: a flood must not become a log flood.
			log.warn("Login fuse blown: {} failed password checks within {}. Password verification is off "
					+ "until this process restarts (delete the backend pod); existing sessions keep working.",
					maxFailedChecks, window);
		}
	}

}
