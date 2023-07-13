package ro.koppel.emag;

public class OrderResult {
    public String id;
    int status;
    boolean is_complete;
    int type;
    String delivery_mode;
    String date;
    int payment_status;
    //TODO: Add fields, also regenerate toString after adding fields.


    @Override
    public String toString() {
        return "OrderResult{" +
                "id='" + id + '\'' +
                ", status=" + status +
                ", is_complete=" + is_complete +
                ", type=" + type +
                ", delivery_mode='" + delivery_mode + '\'' +
                ", date='" + date + '\'' +
                ", payment_status=" + payment_status +
                '}';
    }
}
