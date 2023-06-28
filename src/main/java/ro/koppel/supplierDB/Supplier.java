package ro.koppel.supplierDB;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "SUPPLIER")
public class Supplier implements Serializable {
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
    @Id
    @Column(name = "ID")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "SUPPLIER_SEARCH", joinColumns = @JoinColumn(name = "ID_SUPPLIER"), inverseJoinColumns = @JoinColumn(name = "ID_SEARCH"))
    Set<Search> searchList = new HashSet<>();

    @Override
    public String toString() {
        return "Supplier{" +
                "name='" + name + '\'' +
                ", link='" + link + '\'' +
                ", minEmployees=" + minEmployees +
                ", maxEmployees=" + maxEmployees +
                ", email='" + email + '\'' +
                ", verified=" + verified +
                ", productionLines=" + productionLines +
                ", minQcStaff=" + minQcStaff +
                ", maxQcStaff=" + maxQcStaff +
                ", minRnDStaff=" + minRnDStaff +
                ", maxRnDStaff=" + maxRnDStaff +
                ", yearEstablished=" + yearEstablished +
                ", minAnnualSales=" + minAnnualSales +
                ", maxAnnualSales=" + maxAnnualSales +
                ", factorySize=" + factorySize +
                ", id=" + id +
                ", searchList=" + searchList +
                '}';
    }
}
