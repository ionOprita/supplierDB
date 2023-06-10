package ro.koppel.supplierDB;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.ArrayList;

@SpringBootApplication
public class SupplierDbApplication implements CommandLineRunner {

    @Autowired
    SupplierRepository supplierRepository;

    @Autowired
    SearchRepository searchRepository;

    public static void main(String[] args) {
        SpringApplication.run(SupplierDbApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        Search search1 = new Search();
        search1.searchTerms = "smartwatch";
        Supplier supplier1 = new Supplier();
        supplier1.name = "Shenzen";
        search1.supplierList.add(supplier1);
        supplier1.searchList.add(search1);
        supplierRepository.save(supplier1);
        searchRepository.save(search1);

    }
}
