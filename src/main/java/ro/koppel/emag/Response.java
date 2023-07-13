package ro.koppel.emag;

import java.util.List;

public class Response {
    public boolean isError;
    public List<String> messages;
    public List<OrderResult> results;

    @Override
    public String toString() {
        return "Response{" +
                "isError=" + isError +
                ", messages=" + messages +
                ", results=" + results +
                '}';
    }
}
