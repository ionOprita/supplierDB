package ro.sellfluence.emagapi;

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
}
