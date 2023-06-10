package ro.koppel.supplierDB;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
public class Search {
    @ManyToMany(mappedBy = "searchList")
    List<Supplier> supplierList = new ArrayList<>();
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String searchTerms;
}