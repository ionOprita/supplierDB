package ro.koppel.supplierDB;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;

import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import static jakarta.persistence.CascadeType.MERGE;

@Entity
public class Supplier {
    @Column(unique = true)
    public String name;
    @Column(unique = true)
    public String link;
    @Column
    public int minEmployees;
    @Column
    public int maxEmployees;
    @Column
    public String email;
    @Column
    public Boolean verified;
    @Column
    public int productionLines;
    @Column
    public int minQcStaff;
    @Column
    public int maxQcStaff;
    @Column
    public int minRnDStaff;
    @Column
    public int maxRnDStaff;
    @Column
    public int yearEstablished;
    @Column
    public int minAnnualSales;
    @Column
    public int maxAnnualSales;
    @Column
    public int factorySize;
    @ManyToMany(cascade = MERGE)
    @JoinTable(name = "search_result", joinColumns = @JoinColumn(name = "supplier_id"), inverseJoinColumns = @JoinColumn(name = "search_id"))
    public Set<Search> searchList = new HashSet<>();
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

}
//(cascade = {
//            CascadeType.PERSIST,
//            CascadeType.MERGE
//    })
//