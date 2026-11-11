package ro.sellfluence.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerProfitCalculatorCategoryTest {
    @Test
    void stripsSelectorsWithoutChangingCategorySpelling() {
        assertEquals(new Server.ProfitCalculatorCategoryRequest("S", "Trimmers & personal care"),
                Server.parseProfitCalculatorCategoryRequest("\t S \n", "\u2003Trimmers & personal care\t"));
    }

    @Test
    void rejectsMissingOrBlankSelectors() {
        for (var value : new String[]{null, "", " \t\r\n", "\u2003"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseProfitCalculatorCategoryRequest(value, "Trimmers"));
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseProfitCalculatorCategoryRequest("S", value));
        }
    }
}
