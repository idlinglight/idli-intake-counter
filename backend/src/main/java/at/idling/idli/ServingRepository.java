package at.idling.idli;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ServingRepository extends ListCrudRepository<Serving, Long> {

	List<Serving> findByItemId(long itemId);

	Optional<Serving> findByItemIdAndName(long itemId, String name);

	// See EntryRepository.deleteEntryById on why count-returning bulk delete.
	@Modifying
	@Transactional
	@Query("delete from Serving s where s.id = :id")
	int deleteServingById(long id);

	// See EntryRepository.deleteAllInBulk on why bulk and not @Transactional.
	@Modifying
	@Query("delete from Serving s")
	void deleteAllInBulk();

}
