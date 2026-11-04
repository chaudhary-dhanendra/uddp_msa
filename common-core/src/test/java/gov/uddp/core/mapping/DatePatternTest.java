package gov.uddp.core.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DateTimeException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DatePatternTest {

    @Test
    void existingDayMonthYearFormatIsUnchanged() {
        assertEquals(LocalDate.of(1935, 3, 2), ResidentMappingEngine.parseDate("02-03-1935", "DD-MM-YYYY"));
        assertEquals(LocalDate.of(1980, 4, 1), ResidentMappingEngine.parseDate("1980-04-01", "YYYY-MM-DD"));
    }

    @Test
    void monthNamesAreEnglishAndCaseInsensitive() {
        assertEquals(LocalDate.of(2026, 4, 1), ResidentMappingEngine.parseDate("1-Apr-26", "D-MMM-YY"));
        assertEquals(LocalDate.of(2026, 4, 1), ResidentMappingEngine.parseDate("01-APR-2026", "DD-MMM-YYYY"));
        assertEquals(LocalDate.of(2026, 9, 15), ResidentMappingEngine.parseDate("15 September 2026", "DD MMMM YYYY"));
    }

    @Test
    void twoDigitYearUsesPivot() {
        assertEquals(LocalDate.of(2026, 4, 1), ResidentMappingEngine.parseDate("1-Apr-26", "D-MMM-YY;pivot=1930"));
        assertEquals(LocalDate.of(2029, 4, 1), ResidentMappingEngine.parseDate("1-Apr-29", "D-MMM-YY;pivot=1930"));
        assertEquals(LocalDate.of(1930, 4, 1), ResidentMappingEngine.parseDate("1-Apr-30", "D-MMM-YY;pivot=1930"));
        assertEquals(LocalDate.of(1985, 12, 31), ResidentMappingEngine.parseDate("31-Dec-85", "D-MMM-YY;pivot=1930"));
    }

    @Test
    void twoDigitYearWithoutPivotIsTwentyFirstCentury() {
        assertEquals(LocalDate.of(2085, 12, 31), ResidentMappingEngine.parseDate("31-Dec-85", "D-MMM-YY"));
    }

    @Test
    void mismatchIsRejected() {
        assertThrows(DateTimeException.class, () -> ResidentMappingEngine.parseDate("1-Foo-26", "D-MMM-YY"));
        assertThrows(DateTimeException.class, () -> ResidentMappingEngine.parseDate("1-Apr-1935", "DD-MM-YYYY"));
    }
}
