package com.example

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generates standard Office Open XML (.xlsx) spreadsheets with zero external dependencies.
 */
object XlsxExporter {

    fun buildWorkbookBytes(
        sheetName: String,
        headers: List<String>,
        rows: List<List<String>>,
        colWidths: List<Double>? = null
    ): ByteArray {
        val safeSheet = sanitizeSheetName(sheetName)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            writeZipEntry(zos, "[Content_Types].xml", contentTypesXml())
            writeZipEntry(zos, "_rels/.rels", rootRelsXml())
            writeZipEntry(zos, "xl/workbook.xml", workbookXml(safeSheet))
            writeZipEntry(zos, "xl/_rels/workbook.xml.rels", workbookRelsXml())
            writeZipEntry(zos, "xl/styles.xml", stylesXml())
            writeZipEntry(zos, "xl/worksheets/sheet1.xml", sheetXml(headers, rows, colWidths))
        }
        return out.toByteArray()
    }

    private fun writeZipEntry(zos: ZipOutputStream, path: String, content: String) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(content.toByteArray(StandardCharsets.UTF_8))
        zos.closeEntry()
    }

    private fun sanitizeSheetName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/*?:\\[\\]]"), "_").trim()
        return cleaned.ifBlank { "Accounts" }.take(31)
    }

    private fun escXml(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\t', '\n', '\r' -> sb.append(ch)
                else -> {
                    if (ch.code >= 0x20) sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    private fun colRef(colIndexZeroBased: Int): String {
        var idx = colIndexZeroBased + 1
        val sb = StringBuilder()
        while (idx > 0) {
            val rem = (idx - 1) % 26
            sb.append(('A'.code + rem).toChar())
            idx = (idx - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun contentTypesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
        </Types>
    """.trimIndent()

    private fun rootRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbookXml(sheetName: String): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="${escXml(sheetName)}" sheetId="1" r:id="rId1"/>
          </sheets>
        </workbook>
    """.trimIndent()

    private fun workbookRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    private fun stylesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <fonts count="2">
            <font><sz val="11"/><name val="Calibri"/></font>
            <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
          </fonts>
          <fills count="3">
            <fill><patternFill patternType="none"/></fill>
            <fill><patternFill patternType="gray125"/></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FF0F172A"/><bgColor indexed="64"/></patternFill></fill>
          </fills>
          <borders count="1">
            <border><left/><right/><top/><bottom/><diagonal/></border>
          </borders>
          <cellStyleXfs count="1">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
          </cellStyleXfs>
          <cellXfs count="2">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
            <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
          </cellXfs>
          <cellStyles count="1">
            <cellStyle name="Normal" xfId="0" builtinId="0"/>
          </cellStyles>
        </styleSheet>
    """.trimIndent()

    private fun sheetXml(
        headers: List<String>,
        rows: List<List<String>>,
        colWidths: List<Double>? = null
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n")

        val colCount = maxOf(
            headers.size,
            colWidths?.size ?: 0,
            rows.maxOfOrNull { it.size } ?: 0
        ).coerceAtLeast(1)
        sb.append("  <cols>\n")
        for (c in 0 until colCount) {
            val customW = colWidths?.getOrNull(c)
            if (customW != null && customW > 0.0) {
                val formatted = String.format(java.util.Locale.US, "%.2f", customW)
                sb.append("    <col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$formatted\" customWidth=\"1\"/>\n")
            } else {
                val hLen = headers.getOrNull(c)?.length ?: 10
                val maxRowLen = rows.maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0
                val width = (maxOf(hLen, minOf(maxRowLen, 50)) + 4).coerceIn(10, 56)
                sb.append("    <col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$width\" customWidth=\"1\"/>\n")
            }
        }
        sb.append("  </cols>\n")
        sb.append("  <sheetData>\n")

        val hasHeaders = headers.isNotEmpty()
        if (hasHeaders) {
            // Header row (row 1, bold styled s="1")
            sb.append("    <row r=\"1\">\n")
            headers.forEachIndexed { c, h ->
                val ref = "${colRef(c)}1"
                sb.append("      <c r=\"$ref\" t=\"inlineStr\" s=\"1\"><is><t>${escXml(h)}</t></is></c>\n")
            }
            sb.append("    </row>\n")
        }

        val startRowOffset = if (hasHeaders) 2 else 1
        // Data rows
        rows.forEachIndexed { rIdx, row ->
            val rowNum = rIdx + startRowOffset
            sb.append("    <row r=\"$rowNum\">\n")
            for (c in 0 until colCount) {
                val cellVal = row.getOrNull(c) ?: ""
                val ref = "${colRef(c)}$rowNum"
                sb.append("      <c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escXml(cellVal)}</t></is></c>\n")
            }
            sb.append("    </row>\n")
        }

        sb.append("  </sheetData>\n")
        sb.append("</worksheet>")
        return sb.toString()
    }
}
