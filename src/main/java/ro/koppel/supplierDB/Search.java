package ro.koppel.supplierDB;

import jakarta.persistence.*;

import java.io.Serializable;

@Entity
@Table(name = "SEARCH")
public class Search implements Serializable {
    @Id
    @Column(name = "ID")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(unique = true)
    public String searchTerms;
//    @ManyToMany(fetch = FetchType.EAGER, mappedBy = "searchList", cascade = {CascadeType.ALL})
//    Set<Supplier> supplierList = new HashSet<>();
}