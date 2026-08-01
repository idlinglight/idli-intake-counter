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

	// Import wipes the table in one statement; deleteAll() would issue a
	// select plus one delete per row. Deliberately not @Transactional: this
	// must only ever run inside the import transaction, and @Modifying fails
	// loudly without one.
	@Modifying
	@Query("delete from Entry e")
	void deleteAllInBulk();

}
