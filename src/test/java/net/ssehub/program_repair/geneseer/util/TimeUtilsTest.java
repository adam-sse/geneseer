package net.ssehub.program_repair.geneseer.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TimeUtilsTest {

    @ParameterizedTest
    @CsvSource({
        "0,00:00",
        "59,00:59",
        "60,01:00",
        "600,10:00",
        "3599,59:59",
        "3600,1:00:00",
        "86400,24:00:00",
    })
    public void formatSeconds(int seconds, String expected) {
        assertEquals(expected, TimeUtils.formatSeconds(seconds));
    }
    
    @ParameterizedTest
    @CsvSource(useHeadersInDisplayName = true, value = {
        "millisconeds,expected",
        "0,00:00.000",
        "999,00:00.999",
        "1000,00:01.000",
        "59999,00:59.999",
        "60000,01:00.000",
        "600000,10:00.000",
        "3599999,59:59.999",
        "3600000,1:00:00.000",
        "86400000,24:00:00.000",
    })
    public void formatMilliseconds(int milliseconds, String expected) {
        assertEquals(expected, TimeUtils.formatMilliseconds(milliseconds));
    }
    
}
