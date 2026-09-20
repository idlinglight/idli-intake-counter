package at.idling.idli;

import org.springframework.security.core.AuthenticationException;

// The login fuse is blown (LoginFuse); answered with 418. Same base class as
// CredentialCheckBusyException, for the same reason.
public class PasswordLoginClosedException extends AuthenticationException {

	public PasswordLoginClosedException() {
		super("password login is closed: the login fuse is blown");
	}

}
