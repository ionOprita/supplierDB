package ro.sellfluence.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

public class TestRounding {
    @Test
    void formatRounding() {
        Assertions.assertEquals("0.01", "%.2f".formatted(0.007));
        Assertions.assertEquals("0.00", "%.2f".formatted(0.0049999));
        Assertions.assertEquals("0.01", "%.2f".formatted(0.005));
        Assertions.assertEquals("0.01", "%.2f".formatted(0.0149999));
        Assertions.assertEquals("0.02", "%.2f".formatted(0.015));
        Assertions.assertEquals("8.01", "%.2f".formatted(8.007));
        Assertions.assertEquals("8.00", "%.2f".formatted(8.0049999));
        Assertions.assertEquals("8.01", "%.2f".formatted(8.005));
        Assertions.assertEquals("8.01", "%.2f".formatted(8.0149999));
        Assertions.assertEquals("8.02", "%.2f".formatted(8.015));
    }
}

