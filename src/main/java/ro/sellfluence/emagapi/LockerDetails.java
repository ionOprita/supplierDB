package ro.sellfluence.emagapi;

import java.sql.Connection;
import java.sql.PreparedStatement;

public class LockerDetails {
    public String locker_id;
    public String locker_name;

    @Override
    public String toString() {
        return "Details{" +
                "locker_id='" + locker_id + '\'' +
                ", locker_name='" + locker_name + '\'' +
                '}';
    }

    public void x(LockerDetails lockerDetails) {
//        PreparedStatement s = db.prepareStatement("INSERT INTO LockerDetails (locker_id,locker_name VALUES (?,?)");
//        s.setString(1,ld.locker_id);
//        s.setString(2,ld.locker_name);
    }
}
