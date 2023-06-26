package ro.koppel.supplierDB;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;

@SpringBootApplication
public class SupplierDbApplication implements CommandLineRunner {

    private final Logger logger = LoggerFactory.getLogger(SupplierDbApplication.class);

    @Autowired
    FetchSuppliers supplierSearcher;

    @Autowired
    Excel excel;

    public static void main(String[] args) {
        SpringApplication.run(SupplierDbApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length != 1) {
            logger.atError().log("Please give the name of a text file having on each line a search to perform.");
        } else {
            var inputPath = Paths.get(args[0]);
            Files.readAllLines(inputPath).stream().filter(Objects::nonNull).map(String::trim).forEach(search -> {
                logger.atInfo().log("Searching suppliers for {}", search);
                supplierSearcher.searchSuppliers(search);
            });
            excel.createExcel(Paths.get("C:\\Users\\Oprita\\Desktop\\supplierDB\\supplierDB\\SupplierListTest.xlsx"));
        }
    }
}
