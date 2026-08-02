package at.idling.idli;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

public interface ItemAmountRepository extends ListCrudRepository<ItemAmount, Long> {

	List<ItemAmount> findByItemId(long itemId);

	// Both bulk deletes are deliberately not @Transactional: they only ever
	// run inside a service transaction (item replace, import) and @Modifying
	// fails loudly without one. See EntryRepository.deleteAllInBulk.
	@Modifying
	@Query("delete from ItemAmount a where a.itemId = :itemId")
	void deleteByItemIdInBulk(long itemId);

	@Modifying
	@Query("delete from ItemAmount a")
	void deleteAllInBulk();

}
