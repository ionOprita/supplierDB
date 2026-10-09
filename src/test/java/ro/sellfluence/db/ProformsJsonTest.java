package ro.sellfluence.db;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProformsJsonTest {
    private static final String fullProforms = """
            [
              {
                "id": 4000000001,
                "vendor_id": 4000000002,
                "vendor_name": "Vânzător ☕",
                "vendor_bank": "Example Bank",
                "vendor_iban": "RO00EXAMPLE123456789",
                "customer_name": "Customer Ș",
                "vendor_order_id": 4000000003,
                "products": [
                  {
                    "id": 4000000004,
                    "vendor_proform_id": 4000000001,
                    "vendor_order_product_id": 4000000005,
                    "vendor_product_id": 4000000006,
                    "vendor_product_ext_name": "Șurubelniță\\nsecond line",
                    "vendor_order_product_quantity": 2,
                    "vendor_order_product_sale_price": "123456789.000000001",
                    "vendor_order_product_vat_rate": "0.190000",
                    "created": "2026-10-09T10:11:12.123456789",
                    "modified": "2026-10-09T11:12:13"
                  },
                  {
                    "id": 4000000007,
                    "vendor_product_ext_name": "Another product",
                    "vendor_order_product_quantity": 3,
                    "vendor_order_product_sale_price": "0.000000009"
                  }
                ],
                "proforma_number": 4000000008,
                "created": "2026-10-09T10:11:12.123456789",
                "date_expire": "2026-11-09T00:00:00",
                "modified": "2026-10-09T12:13:14",
                "net_value": "246913578.000000002",
                "gross_value": 293827157.82000000238,
                "status": 1,
                "is_payed": 0,
                "mkt_order_id": 4000000009
              },
              {
                "id": 4000000010,
                "vendor_name": "Second vendor",
                "products": [{"id": 4000000011, "vendor_product_ext_name": "第三个商品"}],
                "net_value": "0.000000001",
                "gross_value": 0.00000000119,
                "status": 2,
                "is_payed": 1
              }
            ]
            """;

    @Test
    void roundTripsCompleteListsWithoutLosingProductsDatesDecimalsOrUnicode() throws Exception {
        var proforms = ProformsJson.decode(fullProforms, "order-42");

        assertEquals(2, proforms.size());
        var first = proforms.getFirst();
        assertEquals(4_000_000_001L, first.id());
        assertEquals(4_000_000_008L, first.proforma_number());
        assertEquals("Vânzător ☕", first.vendor_name());
        assertEquals(new BigDecimal("246913578.000000002"), first.net_value());
        assertEquals(new BigDecimal("293827157.82000000238"), first.gross_value());
        assertEquals(LocalDateTime.of(2026, 10, 9, 10, 11, 12, 123_456_789), first.created());
        assertEquals(2, first.products().size());
        assertEquals("Șurubelniță\nsecond line", first.products().getFirst().vendor_product_ext_name());
        assertEquals(new BigDecimal("123456789.000000001"),
                first.products().getFirst().vendor_order_product_sale_price());
        assertEquals(new BigDecimal("0.190000"), first.products().getFirst().vendor_order_product_vat_rate());
        assertEquals("第三个商品", proforms.getLast().products().getFirst().vendor_product_ext_name());

        var stored = ProformsJson.encode(proforms);

        assertTrue(stored.startsWith("["));
        assertTrue(stored.endsWith("]"));
        assertFalse(stored.contains("\n"), "Stored JSON should be compact, with embedded newlines escaped");
        assertTrue(stored.contains("\"created\":\"2026-10-09T10:11:12.123456789\""));
        assertEquals(proforms, ProformsJson.decode(stored, "order-42"));
    }

    @Test
    void readsNullBlankLegacyColumnsAndEmptyArraysAsEmptyLists() throws Exception {
        for (var stored : Arrays.asList(null, "", " \t\n", "[]", " [ ] ")) {
            assertEquals(List.of(), ProformsJson.decode(stored, "old-order"));
        }
        assertEquals("[]", ProformsJson.encode(List.of()));
    }

    @Test
    void rejectsLegacyTextCorruptJsonAndIncompatibleShapesWithOrderContextAndCause() {
        for (var stored : List.of(
                "legacy-proform",
                "\"legacy-proform\"",
                "[\"legacy-proform\"]",
                "{}",
                "{",
                "[",
                "42",
                "null",
                "[] []",
                "[] trailing",
                "[{\"unexpected_field\":true}]",
                "[{\"products\":[\"legacy-product\"]}]"
        )) {
            var error = assertThrows(SQLException.class,
                    () -> ProformsJson.decode(stored, "important-order-42"), stored);

            assertTrue(error.getMessage().contains("important-order-42"), stored);
            assertTrue(error.getMessage().contains("legacy text or corrupt JSON was not discarded"), stored);
            assertNotNull(error.getCause(), stored);
        }
    }

    @Test
    void rejectsNullListsInsteadOfWritingAnUnreadableJsonNullRoot() {
        var error = assertThrows(SQLException.class, () -> ProformsJson.encode(null));

        assertTrue(error.getMessage().contains("encode order proforms"));
        assertNotNull(error.getCause());
    }
}
