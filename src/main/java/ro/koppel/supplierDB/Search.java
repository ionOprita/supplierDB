package ro.koppel.supplierDB;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
public class Search {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(unique = true)
    public String searchTerms;
    @ManyToMany(fetch = FetchType.EAGER, mappedBy = "searchList", cascade = { CascadeType.ALL })
    Set<Supplier> supplierList = new HashSet<>();
}