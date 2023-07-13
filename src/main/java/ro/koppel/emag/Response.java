package ro.koppel.emag;

import java.util.Arrays;

public class Response {
    public boolean isError;
    public String[] messages;
    public OrderResult[] results;

    @Override
    public String toString() {
        return "Response{" +
                "isError=" + isError +
                ", messages=" + Arrays.toString(messages) +
                ", results=" + Arrays.toString(results) +
                '}';
    }
}
