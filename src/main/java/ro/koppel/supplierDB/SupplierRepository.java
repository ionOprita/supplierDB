package ro.koppel.supplierDB;

import org.springframework.data.repository.CrudRepository;
public interface SupplierRepository extends CrudRepository<Supplier, Long> {

    Supplier findByName(String name);

    Supplier findByLink(String link);
}
