package dev.amiibo.mcp.validation

import dev.amiibo.mcp.domain.AmiiboSearch
import dev.amiibo.mcp.domain.LoadFiguresBySeriesRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InputValidationTest {
    @Test
    fun `splits id into head and tail`() {
        val result = validateSearch(AmiiboSearch(id = "0000000000000002"))

        assertEquals("00000000", result.head)
        assertEquals("00000002", result.tail)
    }

    @Test
    fun `rejects invalid id length`() {
        assertFailsWith<IllegalArgumentException> {
            validateAmiiboId("1234")
        }
    }

    @Test
    fun `rejects non-positive limit`() {
        assertFailsWith<IllegalArgumentException> {
            validateSearch(AmiiboSearch(limit = 0))
        }
    }

    @Test
    fun `rejects load figures by series without key or name`() {
        assertFailsWith<IllegalArgumentException> {
            validateLoadFiguresBySeries(LoadFiguresBySeriesRequest())
        }
    }
}
