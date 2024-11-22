package ro.sellfluence.emagapi;

public record LockerDetails (
     String locker_id,
     String locker_name,
     int locker_delivery_eligible,
     String courier_external_office_id
){}
