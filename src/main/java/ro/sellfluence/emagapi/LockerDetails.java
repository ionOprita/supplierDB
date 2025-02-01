package ro.sellfluence.emagapi;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public record LockerDetails (
     String locker_id,
     String locker_name,
     int locker_delivery_eligible,
     String courier_external_office_id
){
    public static boolean ldlEquals(List<LockerDetails> l1, List<LockerDetails> l2) {
        if (l1==null&&l2==null) return true;
        if (l1==null||l2==null) return false;
        if (l1.size()!=l2.size()) return false;
        Iterator<?> i1 = l1.iterator();
        Iterator<?> i2 = l2.iterator();
        while (i1.hasNext()) {
            if (!Objects.equals(i1.next(), i2.next())) {
                return false;
            }
        }
        return true;
    }
}
