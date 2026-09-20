package at.idling.idli;

import org.springframework.util.Assert;

import java.util.concurrent.Semaphore;

// ADR-0009: how many bcrypt computations may run at once. One costs a few
// hundred milliseconds of CPU and anybody can ask for one — a login POST, or
// a Basic header on any /api path — so without a bound a modest flood
// occupies every core and every worker thread, the liveness probe starves and
// the restart drops all in-memory sessions.
// Non-blocking on purpose: a caller that does not get a permit is refused at
// once. Waiting callers would park exactly the worker threads this protects.
final class CredentialCheckPermits {

	private final Semaphore permits;

	CredentialCheckPermits(int maxConcurrentChecks) {
		Assert.isTrue(maxConcurrentChecks >= 1, "idli.auth.max-concurrent-checks must be at least 1");
		this.permits = new Semaphore(maxConcurrentChecks);
	}

	boolean tryAcquire() {
		return permits.tryAcquire();
	}

	void release() {
		permits.release();
	}

}
