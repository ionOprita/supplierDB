package ro.sellfluence.emagapi;

import java.util.Arrays;

public class Response<T> {
    public boolean isError;
    public String[] messages;
    public String[] errors;
    public T[] results;

    @Override
    public String toString() {
        return "Response{" +
               "isError=" + isError +
               ", messages=" + Arrays.toString(messages) +
               ", errors=" + Arrays.toString(errors) +
               ", results=" + Arrays.toString(results) +
               '}';
    }
}
