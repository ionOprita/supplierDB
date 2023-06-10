package ro.koppel.supplierDB;

import jakarta.persistence.*;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
public class Supplier {
    @Column
    public String name;
    @Column
    public URL link;
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
    public int minRnDStuff;
    @Column
    public int maxRnDStuff;
    @Column
    public int yearEstablished;
    @Column
    public int minAnnualSales;
    @Column
    public int maxAnnualSales;
    @Column
    public int factorySize;
    @ManyToMany(cascade=CascadeType.ALL)
    @JoinTable(name = "search_result", joinColumns = @JoinColumn(name = "supplier_id"), inverseJoinColumns = @JoinColumn(name = "search_id"))
    List<Search> searchList = new ArrayList<>();
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

}
//(cascade = {
//            CascadeType.PERSIST,
//            CascadeType.MERGE
//    })
//