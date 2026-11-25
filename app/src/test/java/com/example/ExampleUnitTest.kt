package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `automation runtime state updates and resets cleanly`() {
        AutomationRuntimeState.update(null) {
            it.copy(
                igAutoEnabled = true,
                igAutoRunning = true,
                igStepText = "Filling IG login",
                currentNumber = "+8801700000000",
                currentOtp = "654321",
                floatingBubbleEnabled = true,
                backgroundServiceRunning = true
            )
        }

        val active = AutomationRuntimeState.state.value
        assertTrue(active.igAutoEnabled)
        assertTrue(active.igAutoRunning)
        assertEquals("Filling IG login", active.igStepText)
        assertEquals("+8801700000000", active.currentNumber)
        assertEquals("654321", active.currentOtp)
        assertTrue(active.backgroundServiceRunning)

        AutomationRuntimeState.update(null) {
            it.copy(igAutoEnabled = false, igAutoRunning = false, igStepText = "Automation OFF")
        }
        val stopped = AutomationRuntimeState.state.value
        assertFalse(stopped.igAutoEnabled)
        assertFalse(stopped.igAutoRunning)
        assertEquals("Automation OFF", stopped.igStepText)
    }

    @Test
    fun `stex api service gracefully reports unconfigured state without context`() {
        assertFalse(StexApiService.hasApiKey())
        assertEquals("", StexApiService.getBaseUrl())
        assertEquals("", StexApiService.getApiKey())
    }

    @Test
    fun `xlsx exporter creates valid openxml zip structure with data`() {
        val bytes = XlsxExporter.buildWorkbookBytes(
            sheetName = "Accounts",
            headers = listOf("#", "Username", "Password", "Cookies"),
            rows = listOf(
                listOf("1", "demo_user&1", "P@ss<123>", "sessionid=abc; ds_user_id=99")
            )
        )
        assertTrue(bytes.size > 200)
        val entries = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries[entry.name] = zis.readBytes().toString(java.nio.charset.StandardCharsets.UTF_8)
                entry = zis.nextEntry
            }
        }
        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("xl/workbook.xml"))
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))
        val sheetXml = entries["xl/worksheets/sheet1.xml"].orEmpty()
        assertTrue(sheetXml.contains("demo_user&amp;1"))
        assertTrue(sheetXml.contains("P@ss&lt;123&gt;"))
        assertTrue(sheetXml.contains("sessionid=abc; ds_user_id=99"))
    }

    @Test
    fun `xlsx exporter headerless mode starts at row 1 with narrower password column`() {
        val bytes = XlsxExporter.buildWorkbookBytes(
            sheetName = "TrustPoint",
            headers = emptyList(),
            rows = listOf(
                listOf("insta_user_01", "shakil21", "sessionid=xyz", "JBSWY3DPEHPK3PXP")
            ),
            colWidths = listOf(22.0, 12.0, 22.0, 22.0)
        )
        val entries = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries[entry.name] = zis.readBytes().toString(java.nio.charset.StandardCharsets.UTF_8)
                entry = zis.nextEntry
            }
        }
        val sheetXml = entries["xl/worksheets/sheet1.xml"].orEmpty()
        assertTrue(sheetXml.contains("<col min=\"1\" max=\"1\" width=\"22.00\" customWidth=\"1\"/>"))
        assertTrue(sheetXml.contains("<col min=\"2\" max=\"2\" width=\"12.00\" customWidth=\"1\"/>"))
        assertTrue(sheetXml.contains("<col min=\"3\" max=\"3\" width=\"22.00\" customWidth=\"1\"/>"))
        assertTrue(sheetXml.contains("<col min=\"4\" max=\"4\" width=\"22.00\" customWidth=\"1\"/>"))
        assertTrue(sheetXml.contains("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>insta_user_01</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>shakil21</t></is></c><c r=\"C1\" t=\"inlineStr\"><is><t>sessionid=xyz</t></is></c><c r=\"D1\" t=\"inlineStr\"><is><t>JBSWY3DPEHPK3PXP</t></is></c></row>"))
        assertFalse(sheetXml.contains("<row r=\"2\">"))
    }
}
