package ro.sellfluence.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class UsefulMethods {
    /**
     * Null safe comparison of two BigDecimal numbers.
     *
     * @param bd1 first number
     * @param bd2 second number
     * @return true if they represent the same value.
     */
    public static boolean bdEquals(BigDecimal bd1, BigDecimal bd2) {
        if (bd1 == null) {
            return bd2 == null;
        }
        if (bd2 == null) {
            return false;
        }
        return bd1.compareTo(bd2) == 0; // Both are non-null, use compareTo
    }

    /**
     * Null safe setting scale of BigDecimal.
     *
     * @param bd value to adjust scale.
     * @return scaled value.
     */
    public static BigDecimal round(BigDecimal bd) {
        return bd == null ? null : bd.setScale(2, RoundingMode.HALF_EVEN);
    }

}
