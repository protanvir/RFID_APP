package com.tas.racetiming.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class PassingFormatTest {

    private static long at(int y, int mo, int d, int h, int mi, int s, int ms) {
        Calendar c = Calendar.getInstance();
        c.set(y, mo - 1, d, h, mi, s);
        c.set(Calendar.MILLISECOND, ms);
        return c.getTimeInMillis();
    }

    @Test
    public void wiclaxPassingIsSeqAtEpcAtTimestamp() {
        long t = at(2026, 11, 9, 13, 11, 53, 6);
        assertEquals("79@00000101@20261109131153006", PassingFormat.wiclax(79, "00000101", t));
    }

    @Test
    public void wiclaxPassingWithoutSeqLeavesFirstFieldEmpty() {
        long t = at(2026, 11, 9, 13, 11, 53, 6);
        assertEquals("@00000101@20261109131153006", PassingFormat.wiclax(0, "00000101", t));
    }

    @Test
    public void wiclaxTimestampKeepsMillisecondsAndZeroPadding() {
        long t = at(2026, 1, 2, 3, 4, 5, 7);
        assertTrue(PassingFormat.wiclax(1, "A", t).endsWith("@20260102030405007"));
    }

    @Test
    public void shortBibStripsZeroPadding() {
        assertEquals("102", PassingFormat.shortBib("000000000000000000000102"));
        assertEquals("101", PassingFormat.shortBib("00000101"));
        assertEquals("10076", PassingFormat.shortBib("00010076"));
        assertEquals("0", PassingFormat.shortBib("00000000"));
    }

    @Test
    public void shortBibShortensNonNumericChips() {
        assertEquals("…1280D4AF", PassingFormat.shortBib("E2000019730502381280D4AF"));
        assertEquals("ABC123", PassingFormat.shortBib("ABC123"));
        assertEquals("", PassingFormat.shortBib(""));
        assertEquals("", PassingFormat.shortBib(null));
    }

    @Test
    public void shortBibDoesNotTreatLongNumbersAsBibs() {
        // 7+ significant digits is a chip code, not a bib number
        assertEquals("…34567890", PassingFormat.shortBib("000001234567890"));
    }

    @Test
    public void parseRssi() {
        assertEquals(-54.7, PassingFormat.parseRssi("-54.70"), 0.0001);
        assertEquals(-54.7, PassingFormat.parseRssi(" -54,70 "), 0.0001);
        assertTrue(Double.isNaN(PassingFormat.parseRssi("")));
        assertTrue(Double.isNaN(PassingFormat.parseRssi(null)));
        assertTrue(Double.isNaN(PassingFormat.parseRssi("MANUAL")));
    }
}
