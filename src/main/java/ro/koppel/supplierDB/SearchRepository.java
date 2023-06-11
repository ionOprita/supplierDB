package ro.koppel.supplierDB;

import org.springframework.data.repository.CrudRepository;

public interface SearchRepository extends CrudRepository<Search, Long> {

    Search findBySearchTerms(String searchTerm);
}
