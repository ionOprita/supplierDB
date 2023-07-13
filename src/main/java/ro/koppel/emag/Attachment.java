package ro.koppel.emag;

public class Attachment {
    public int order_id;
    public String name;
    public String url;
    public Integer type;
    public Integer force_download;

    @Override
    public String toString() {
        return "Attachment{" +
                "order_id=" + order_id +
                ", name='" + name + '\'' +
                ", url='" + url + '\'' +
                ", type=" + type +
                ", force_download=" + force_download +
                '}';
    }
}
