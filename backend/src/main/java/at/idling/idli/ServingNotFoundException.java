package at.idling.idli;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ServingNotFoundException extends RuntimeException {

	public ServingNotFoundException(long servingId) {
		super("no serving with id: " + servingId);
	}

}
