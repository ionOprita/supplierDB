package ro.sellfluence.db;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record EmagFetchLog(
        String emagLogin,
        LocalDate date,
        LocalDateTime fetchTime,
        String error) {
}