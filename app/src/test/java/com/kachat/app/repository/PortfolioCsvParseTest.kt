package com.kachat.app.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioCsvParseTest {

    @Test
    fun `a quoted note keeps its line breaks, quotes and commas`() {
        val csv = "Date,Token,Type\r\n" +
            "\"2026-01-01\",\"KAS\",\"buy\",\"line one\nline \"\"two\"\", with comma\"\n" +
            "\n" +
            "\"2026-01-02\",\"KAS\",\"sell\"\r"
        val records = PortfolioRepository.parseCsvRecords(csv)
        assertEquals(3, records.size)
        assertEquals(listOf("Date", "Token", "Type"), records[0])
        assertEquals("line one\nline \"two\", with comma", records[1][3])
        assertEquals(listOf("2026-01-02", "KAS", "sell"), records[2])
    }
}
