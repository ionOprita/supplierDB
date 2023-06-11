package ro.koppel.supplierDB;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SupplierDbApplication implements CommandLineRunner {

    @Autowired
    FetchSupplierDetails supplierFetcher;

    public static void main(String[] args) {
        SpringApplication.run(SupplierDbApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        supplierFetcher.retrieveAndStoreSupplier("https://smartwatch-n.manufacturer.globalsources.com/homepage_6008850873897.htm", "smartwatch camera");
    }
}
