package at.idling.idli;

import org.springframework.data.repository.ListCrudRepository;

import java.time.Instant;
import java.util.List;

public interface EntryRepository extends ListCrudRepository<Entry, Long> {

	List<Entry> findByLoggedAtGreaterThanEqualAndLoggedAtLessThanOrderByLoggedAtDesc(Instant fromInclusive,
			Instant toExclusive);

}
