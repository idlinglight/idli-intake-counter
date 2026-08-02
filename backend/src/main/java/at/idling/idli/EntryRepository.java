package at.idling.idli;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EntryRepository extends ListCrudRepository<Entry, Long> {

	// Id tie-break: entries of one serving-log share a loggedAt, and only the
	// id keeps their order deterministic across reads.
	List<Entry> findByLoggedAtGreaterThanEqualAndLoggedAtLessThanOrderByLoggedAtDescIdDesc(Instant fromInclusive,
			Instant toExclusive);

	// Bulk delete returning the affected count: atomic 404-detection without
	// the existsById-then-delete race (deleteById is a silent no-op there).
	@Modifying
	@Transactional
	@Query("delete from Entry e where e.id = :id")
	int deleteEntryById(long id);

	// Same pattern, one level up: a serving-log's entries live and die together.
	@Modifying
	@Transactional
	@Query("delete from Entry e where e.groupId = :groupId")
	int deleteEntryGroupById(UUID groupId);

	// Import wipes the table in one statement; deleteAll() would issue a
	// select plus one delete per row. Deliberately not @Transactional: this
	// must only ever run inside the import transaction, and @Modifying fails
	// loudly without one.
	@Modifying
	@Query("delete from Entry e")
	void deleteAllInBulk();

}
