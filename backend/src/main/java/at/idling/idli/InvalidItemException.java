package at.idling.idli;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An item payload that bean validation cannot catch, e.g. a duplicate metric. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidItemException extends RuntimeException {

	public InvalidItemException(String message) {
		super(message);
	}

}
