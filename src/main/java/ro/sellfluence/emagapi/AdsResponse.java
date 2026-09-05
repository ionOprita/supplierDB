package ro.sellfluence.emagapi;

import java.util.List;

public class AdsResponse {
    public AdsPaginationMeta meta;
    public String error;
    public String message;
    public Integer status;
    public String code;
    public List<AdsError> errors;

    @Override
    public String toString() {
        return "AdsResponse{" +
                "meta=" + meta +
                ", error='" + error + '\'' +
                ", message='" + message + '\'' +
                ", status=" + status +
                ", code='" + code + '\'' +
                ", errors=" + errors +
                '}';
    }
}
