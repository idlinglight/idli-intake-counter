package at.idling.idli;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A create/rename collides with an existing name. 409, not 400: the request is
 * well-formed and would succeed against other state.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class NameConflictException extends RuntimeException {

	public NameConflictException(String what, String name) {
		super(what + " name already taken: " + name);
	}

}
