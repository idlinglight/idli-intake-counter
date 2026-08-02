package at.idling.idli;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ItemRepository extends ListCrudRepository<Item, Long> {

	boolean existsByName(String name);

	// See EntryRepository.deleteEntryById on why count-returning bulk delete.
	// The DB cascades item_amount and serving rows.
	@Modifying
	@Transactional
	@Query("delete from Item i where i.id = :id")
	int deleteItemById(long id);

	// See EntryRepository.deleteAllInBulk on why bulk and not @Transactional.
	@Modifying
	@Query("delete from Item i")
	void deleteAllInBulk();

}
