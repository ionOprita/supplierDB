package ro.sellfluence.test;

import org.junit.jupiter.api.Test;
import ro.sellfluence.sheetSupport.Conversions;

import java.time.DateTimeException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ConversionsTest {

    @Test
    void toLocalDateTime() {
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("2024-12-06T13:19")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("2024-12-06T13:19:00")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("2024-12-06T13:19:00.000")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("Fri, 6 Dec 2024 13:19:00")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("Fri, 06 Dec 2024 13:19")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("Fri, 6 Dec 2024 13:19")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("Fri, 6 Dec 24 13:19")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("Fri, 06 Dec 24 13:19")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("Fri, 6 Dec 24 13:19:00")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("2024-12-06 13:19")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("2024-12-06 13:19:00")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("2024-12-06 13:19:00.000")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("06 Dec 2024, 13:19")
        );
        assertEquals(
                LocalDateTime.of(2024,12,6,13,19),
                Conversions.toLocalDateTime("06 Dec 2024, 13:19")
        );
        assertThrows(
                DateTimeException.class,
                () -> Conversions.toLocalDateTime("06 Dec 2024, 13:19")
        );
        assertThrows(
                DateTimeException.class,
                () -> Conversions.toLocalDateTime("6 Dec 2024, 13:19")
        );
    }
}