package at.idling.idli;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Login and logout themselves are handled by Spring Security filters (see
// SecurityConfig); only the session probe is a real controller so that it
// shows up in the OpenAPI contract.
@RestController
@RequestMapping("/api")
public class AuthController {

	// "Am I logged in?" for the SPA. Deliberately permitAll and side-effect
	// free; fetching it also (re)issues the XSRF-TOKEN cookie the login POST
	// and later writes need.
	@GetMapping("/auth/session")
	public SessionDto session() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		boolean authenticated = authentication != null
				&& authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken);
		return new SessionDto(authenticated);
	}

}
