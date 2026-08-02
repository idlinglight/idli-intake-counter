package at.idling.idli;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;

public interface MetricRepository extends ListCrudRepository<Metric, Long> {

	boolean existsByName(String name);

	// See EntryRepository.deleteAllInBulk on why bulk and not @Transactional.
	@Modifying
	@Query("delete from Metric m")
	void deleteAllInBulk();

}
