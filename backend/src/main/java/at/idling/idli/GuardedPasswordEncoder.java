package at.idling.idli;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.function.Supplier;

// ADR-0009: the one place where bcrypt runs, so the one place that bounds it.
// Form login, HTTP Basic and the dummy check Spring Security runs for an
// unknown username (its timing-attack mitigation) all end up in matches() —
// gating the encoder covers them by construction, where a request filter
// would have to keep a list of "what triggers a password check" in sync by
// hand. Anything that authenticates without a password never comes through
// here and is never refused.
final class GuardedPasswordEncoder implements PasswordEncoder {

	private final PasswordEncoder delegate;
	private final CredentialCheckPermits permits;
	private final LoginFuse fuse;

	GuardedPasswordEncoder(PasswordEncoder delegate, CredentialCheckPermits permits, LoginFuse fuse) {
		this.delegate = delegate;
		this.permits = permits;
		this.fuse = fuse;
	}

	@Override
	public boolean matches(CharSequence rawPassword, String encodedPassword) {
		boolean matches = guarded(() -> delegate.matches(rawPassword, encodedPassword));
		if (!matches) {
			fuse.recordFailedCheck();
		}
		return matches;
	}

	// Reachable before authentication as well: DaoAuthenticationProvider
	// lazily encodes its unknown-username dummy on the first login attempt,
	// without a lock — after a restart every request arriving in those first
	// milliseconds would start a bcrypt of its own.
	@Override
	public String encode(CharSequence rawPassword) {
		return guarded(() -> delegate.encode(rawPassword));
	}

	@Override
	public boolean upgradeEncoding(String encodedPassword) {
		return delegate.upgradeEncoding(encodedPassword);
	}

	// Both refusals happen BEFORE any bcrypt. The fuse is checked first and
	// without a lock, so up to (permits - 1) checks already in flight may still
	// finish after it blows.
	private <T> T guarded(Supplier<T> bcrypt) {
		if (fuse.isBlown()) {
			throw new PasswordLoginClosedException();
		}
		if (!permits.tryAcquire()) {
			throw new CredentialCheckBusyException();
		}
		try {
			return bcrypt.get();
		} finally {
			// On every path, an exception from the delegate included: a leaked
			// permit is a healthy-looking app that nobody can log in to.
			permits.release();
		}
	}

}
