package at.idling.idli;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface EntryRepository extends ListCrudRepository<Entry, Long> {

	List<Entry> findByLoggedAtGreaterThanEqualAndLoggedAtLessThanOrderByLoggedAtDesc(Instant fromInclusive,
			Instant toExclusive);

	// Bulk delete returning the affected count: atomic 404-detection without
	// the existsById-then-delete race (deleteById is a silent no-op there).
	@Modifying
	@Transactional
	@Query("delete from Entry e where e.id = :id")
	int deleteEntryById(long id);

}
