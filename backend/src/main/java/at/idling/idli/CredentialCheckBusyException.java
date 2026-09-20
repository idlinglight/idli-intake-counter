package at.idling.idli;

import org.springframework.security.core.AuthenticationException;

// Every permit is taken (CredentialCheckPermits); answered with 429. Extends
// AuthenticationException directly: ProviderManager and both authentication
// filters hand such an exception to the failure handler / entry point as it
// is, whereas an InternalAuthenticationServiceException would be logged as a
// server error — once per refused request, i.e. a log flood under load.
public class CredentialCheckBusyException extends AuthenticationException {

	public CredentialCheckBusyException() {
		super("all credential-check permits are in use");
	}

}
