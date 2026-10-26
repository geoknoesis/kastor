@file:OptIn(InternalKastorApi::class)

package com.geoknoesis.kastor.rdf.xsd

import com.geoknoesis.kastor.rdf.InternalKastorApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class XsdTemporalsTest {
    private val xsd = "http://www.w3.org/2001/XMLSchema#"

    private fun valid(type: String, lexical: String) = XsdLexical.isValid(xsd + type, lexical)

    @Test
    fun `date and time lexical spaces follow XSD 1_1`() {
        val table = mapOf(
            "dateTime" to (listOf(
                "2020-01-01T00:00:00", "2020-02-29T23:59:59.999Z", "2000-02-29T00:00:00", "-0044-03-15T12:00:00+14:00",
                "0000-01-01T00:00:00", "12345-06-07T08:09:10-13:59", "2020-12-31T24:00:00", "2020-12-31T24:00:00.000",
                "2020-01-01T00:00:00." + "1".repeat(50),
            ) to listOf(
                "2021-02-29T00:00:00", "1900-02-29T00:00:00", "2020-04-31T00:00:00", "2020-13-01T00:00:00",
                "2020-00-01T00:00:00", "2020-01-00T00:00:00", "2020-01-01T24:00:01", "2020-01-01T24:00:00.1",
                "2020-01-01T24:01:00", "2020-01-01T00:60:00", "2020-01-01T00:00:60", "2020-01-01T23:59:60",
                "2020-01-01T00:00:00+14:01", "2020-01-01T00:00:00+15:00", "2020-01-01T00:00:00+05:60",
                "2020-01-01T00:00:00.", "2020-01-01T00:00", "02020-01-01T00:00:00", "020-01-01T00:00:00",
                "+2020-01-01T00:00:00", "2020-01-01", "2020-01-01T00:00:00Z ", " 2020-01-01T00:00:00", "2020-1-01T00:00:00",
                "2020-01-01T00:00:00z", "2020-01-01T00:00:00+0100", "2020-01-01t00:00:00",
            )),
            "dateTimeStamp" to (listOf("2020-01-01T00:00:00Z", "2020-01-01T00:00:00-05:00") to listOf("2020-01-01T00:00:00")),
            "date" to (listOf("2020-02-29", "2020-01-01Z", "-0001-12-31+01:00", "1600-02-29") to listOf("2019-02-29", "1700-02-29", "2020-01-01T00:00:00", "2020-06-31")),
            "time" to (listOf("00:00:00", "23:59:59.5Z", "24:00:00", "12:30:00+05:30") to listOf("24:00:00.5", "25:00:00", "12:00", "12:00:00+14:30", "23:59:60")),
            "gYear" to (listOf("2020", "-0001", "12345Z", "0000") to listOf("20", "02020", "2020-01", "+2020")),
            "gYearMonth" to (listOf("2020-12", "2020-01+01:00") to listOf("2020-13", "2020-00", "2020")),
            "gMonthDay" to (listOf("--02-29", "--12-31Z", "--04-30") to listOf("--02-30", "--04-31", "--13-01", "-02-29")),
            "gDay" to (listOf("---01", "---31-14:00") to listOf("---32", "---00", "--01")),
            "gMonth" to (listOf("--01", "--12Z") to listOf("--13", "--00", "--1", "--01--")),
        )
        for ((type, cases) in table) {
            for (ok in cases.first) assertEquals(true, valid(type, ok), "$ok as $type")
            for (bad in cases.second) assertEquals(false, valid(type, bad), "$bad as $type")
        }
    }

    @Test
    fun `years of any length are valid and leap years use the last four digits`() {
        assertEquals(true, valid("date", "1" + "0".repeat(1000) + "-02-29"), "10^1000 is a multiple of 400")
        assertEquals(false, valid("date", "1" + "0".repeat(998) + "100-02-29"), "a multiple of 100 but not of 400")
        assertEquals(true, valid("date", "-" + "9".repeat(100) + "6-02-29"))
    }

    private fun t(type: String, lexical: String) = assertNotNull(XsdTemporals.parse(xsd + type, lexical), lexical)

    private fun cmp(type: String, a: String, b: String) = XsdTemporals.compare(t(type, a), t(type, b))

    @Test
    fun `dateTimes compare on the UTC time line`() {
        assertEquals(0, cmp("dateTime", "2020-01-01T12:00:00Z", "2020-01-01T13:00:00+01:00"))
        assertEquals(0, cmp("dateTime", "2020-01-01T00:00:00+14:00", "2019-12-31T10:00:00Z"))
        assertEquals(0, cmp("dateTime", "2020-12-31T24:00:00", "2021-01-01T00:00:00"), "24:00 is the next day")
        assertEquals(0, cmp("dateTime", "2020-02-28T24:00:00Z", "2020-02-29T00:00:00Z"))
        assertEquals(0, cmp("dateTime", "2021-02-28T24:00:00Z", "2021-03-01T00:00:00Z"))
        assertEquals(0, cmp("dateTime", "2020-01-01T00:00:00.5", "2020-01-01T00:00:00.500"))
        assertEquals(-1, cmp("dateTime", "2020-01-01T00:00:00.5", "2020-01-01T00:00:00.51"))
        assertEquals(1, cmp("dateTime", "2020-01-01T00:00:00.6", "2020-01-01T00:00:00.51"))
        assertEquals(-1, cmp("dateTime", "-0001-12-31T23:00:00-02:00", "0000-01-01T02:00:00Z"), "year carry across zero")
        assertEquals(0, cmp("dateTime", "-0001-12-31T23:00:00-02:00", "0000-01-01T01:00:00Z"))
        assertEquals(-1, cmp("dateTime", "9999-12-31T23:59:59", "10000-01-01T00:00:00"))
        val big = "9".repeat(30)
        assertEquals(0, cmp("dateTime", "$big-12-31T23:00:00-02:00", "1" + "0".repeat(30) + "-01-01T01:00:00Z"), "carry into a 31-digit year")
        assertEquals(0, cmp("dateTime", "-1" + "0".repeat(30) + "-01-01T01:00:00+02:00", "-1" + "0".repeat(29) + "1-12-31T23:00:00Z"))
    }

    @Test
    fun `zoned and local values compare only outside the fourteen-hour window`() {
        assertNull(cmp("dateTime", "2020-01-01T12:00:00Z", "2020-01-01T12:00:00"))
        assertNull(cmp("dateTime", "2020-01-01T00:00:00Z", "2020-01-01T14:00:00"))
        assertEquals(-1, cmp("dateTime", "2020-01-01T00:00:00Z", "2020-01-01T14:00:01"))
        assertEquals(1, cmp("dateTime", "2020-01-02T04:00:01Z", "2020-01-01T14:00:00"))
        assertEquals(1, cmp("dateTime", "2020-01-01T14:00:01", "2020-01-01T00:00:00Z"))
        assertEquals(0, cmp("dateTime", "2020-01-01T00:00:00", "2020-01-01T00:00:00"))
    }

    @Test
    fun `dates and times compare in their own families`() {
        assertEquals(-1, cmp("date", "2020-01-01", "2020-01-02"))
        assertEquals(0, cmp("date", "2020-01-02+12:00", "2020-01-01-12:00"))
        assertEquals(0, cmp("time", "24:00:00", "00:00:00"), "24:00:00 is 00:00:00 for xsd:time")
        assertEquals(0, cmp("time", "13:00:00+01:00", "12:00:00Z"))
        assertEquals(1, cmp("time", "00:30:00-01:00", "00:30:00Z"))
        assertNull(XsdTemporals.compare(t("date", "2020-01-01"), t("dateTime", "2020-01-01T00:00:00")))
        assertEquals(0, XsdTemporals.compare(t("dateTimeStamp", "2020-01-01T00:00:00Z"), t("dateTime", "2020-01-01T00:00:00Z")))
        assertNull(XsdTemporals.compare(t("gYear", "2020"), t("gYear", "2021")), "Gregorian values are not ordered here")
    }

    @Test
    fun `durations follow the designator grammar`() {
        val table = mapOf(
            XsdLexical.DurationKind.DURATION to (listOf("P1Y", "-P1Y2M3DT4H5M6.7S", "PT0S", "P0D", "PT1M", "P1M", "PT1.5S", "P1DT1H") to
                listOf("P", "PT", "P1YT", "-P", "P1.5Y", "PT1.5M", "P1S", "P1H", "PT1D", "P1M1Y", "PT1S1M", "P1Y1Y", "1Y", "P-1Y", "PT1.S", "PT.5S", "P1DT", "P1Y 2M", "PT1H1H", "p1Y")),
            XsdLexical.DurationKind.DAY_TIME to (listOf("P1D", "PT1H", "-P1DT2.5S") to listOf("P1Y", "P1M", "P1Y1D")),
            XsdLexical.DurationKind.YEAR_MONTH to (listOf("P1Y", "P1M", "-P1Y2M") to listOf("P1D", "PT1H", "P1YT1M")),
        )
        for ((kind, cases) in table) {
            for (ok in cases.first) assertEquals(true, XsdLexical.isDuration(kind, ok), "$ok as $kind")
            for (bad in cases.second) assertEquals(false, XsdLexical.isDuration(kind, bad), "$bad as $kind")
        }
        assertEquals(true, valid("dayTimeDuration", "PT" + "1".repeat(100_000) + "." + "5".repeat(100_000) + "S"))
    }

    @Test
    fun `booleans and unknown datatypes`() {
        for (ok in listOf("true", "false", "1", "0")) assertEquals(true, valid("boolean", ok))
        for (bad in listOf("True", "yes", "", " true", "01")) assertEquals(false, valid("boolean", bad))
        assertNull(XsdLexical.isValid(xsd + "string", "anything"))
        assertNull(XsdLexical.isValid("http://example.org/dt", "1"))
    }
}
