package com.tas.racetiming.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class QueueLineTest {

    private static final long T = 1794207713006L; // an arbitrary instant with non-zero ms

    @Test
    public void formatThenParseKeepsEveryField() {
        QueueLine in = new QueueLine("000000000000000000000102", "-54.70", T, 79);
        QueueLine out = QueueLine.parse(in.format());
        assertNotNull(out);
        assertEquals("000000000000000000000102", out.epc);
        assertEquals("-54.70", out.rssi);
        assertEquals(T, out.readTimeMs);
        assertEquals(79, out.seq);
    }

    @Test
    public void formatHasFiveFieldsWithEpochAndSeqLast() {
        String[] p = new QueueLine("00000101", "-60", T, 5).format().split(";");
        assertEquals(5, p.length);
        assertEquals("00000101", p[0]);
        assertEquals(String.valueOf(T), p[3]);
        assertEquals("5", p[4]);
    }

    @Test
    public void epochFieldWinsOverFormattedTimestamp() {
        // formatted time says one thing, epoch says another: epoch is the truth
        String line = "00000101;01-01-2020 00:00:00.000;-60;" + T + ";7";
        assertEquals(T, QueueLine.parse(line).readTimeMs);
    }

    @Test
    public void oldLineWithoutEpochUsesFormattedTimestampToTheMillisecond() {
        String ts = new SimpleDateFormat(QueueLine.TS_FORMAT, Locale.US).format(new Date(T));
        QueueLine ql = QueueLine.parse("00000101;" + ts + ";-60");
        assertEquals(T, ql.readTimeMs);
        assertEquals(0, ql.seq);
    }

    @Test
    public void dailyCsvLineParses() {
        // daily log format: EPC;timestamp;RSSI;;;0
        String ts = new SimpleDateFormat(QueueLine.TS_FORMAT, Locale.US).format(new Date(T));
        QueueLine ql = QueueLine.parse("00010076;" + ts + ";-57.80;;;0");
        assertEquals("00010076", ql.epc);
        assertEquals("-57.80", ql.rssi);
        assertEquals(T, ql.readTimeMs);
        assertEquals(0, ql.seq);
    }

    @Test
    public void lineWithNoUsableTimeHasZeroReadTime() {
        // must never be given the current time: callers skip readTimeMs == 0
        assertEquals(0, QueueLine.parse("00000101;not a date;-60").readTimeMs);
        assertEquals(0, QueueLine.parse("00000101").readTimeMs);
        assertEquals(0, QueueLine.parse("00000101;;-60;abc;1").readTimeMs);
    }

    @Test
    public void blankOrEpcLessLinesAreNull() {
        assertNull(QueueLine.parse(null));
        assertNull(QueueLine.parse(""));
        assertNull(QueueLine.parse("   \r\n"));
        assertNull(QueueLine.parse(";01-01-2020 00:00:00.000;-60"));
    }

    @Test
    public void separatorInsideAFieldCannotShiftColumns() {
        QueueLine out = QueueLine.parse(new QueueLine("AB;CD", "-5;0", T, 3).format());
        assertEquals("ABCD", out.epc);
        assertEquals("-50", out.rssi);
        assertEquals(T, out.readTimeMs);
        assertEquals(3, out.seq);
    }

    @Test
    public void badSeqBecomesZeroNotNegative() {
        assertEquals(0, QueueLine.parse("00000101;;-60;" + T + ";-4").seq);
        assertEquals(0, QueueLine.parse("00000101;;-60;" + T + ";x").seq);
    }

    @Test
    public void trailingCarriageReturnIsIgnored() {
        QueueLine ql = QueueLine.parse(new QueueLine("00000101", "-60", T, 12).format() + "\r");
        assertEquals(12, ql.seq);
        assertEquals(T, ql.readTimeMs);
    }
}
