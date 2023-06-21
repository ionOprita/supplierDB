package ro.koppel.supplierDB;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
public class Search {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(unique = true)
    public String searchTerms;
    @ManyToMany(mappedBy = "searchList")
    List<Supplier> supplierList = new ArrayList<>();
}