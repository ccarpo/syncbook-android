package com.ccarpo.syncbook

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChecklistTransformationTest {
    @Test
    fun mixedTextTransformsChecklistPrefixesAndCheckedStyleRange() {
        val result = transformChecklistText("Paragraph\n- [ ] open\n- [x] done")

        assertEquals("Paragraph\n☐ open\n☑ done", result.transformed)
        assertEquals(2, result.glyphs.size)
        assertEquals(19, result.checkedRanges.single().start)
        assertEquals(23, result.checkedRanges.single().end)
    }

    @Test
    fun offsetsRoundTripOutsidePrefixes() {
        val original = "Paragraph\n- [ ] open\n- [x] done"
        val result = transformChecklistText(original)
        val prefixes = setOf(
            10, 11, 12, 13, 14, 15,
            21, 22, 23, 24, 25, 26,
        )

        for (offset in 0..original.length) {
            if (offset !in prefixes) {
                val transformed = result.offsetMapping.originalToTransformed(offset)
                assertEquals(
                    offset,
                    result.offsetMapping.transformedToOriginal(transformed),
                    "offset $offset",
                )
            }
        }
    }

    @Test
    fun offsetsInsidePrefixesClampToReplacementEdges() {
        val result = transformChecklistText("- [ ] open")
        val mapping = result.offsetMapping

        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(2, mapping.originalToTransformed(1))
        assertEquals(2, mapping.originalToTransformed(5))
        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(6, mapping.transformedToOriginal(2))
    }

    @Test
    fun bareChecklistLineUsesSingleGlyph() {
        val result = transformChecklistText("- [ ]")
        val mapping = result.offsetMapping

        assertEquals("☐", result.transformed)
        assertEquals(1, mapping.originalToTransformed(1))
        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(5, mapping.transformedToOriginal(1))
        assertNotNull(checkboxAtTransformedOffset(result, 0))
        assertNotNull(checkboxAtTransformedOffset(result, 1))
    }

    @Test
    fun visualTransformationReturnsTransformedText() {
        val transformed = ChecklistVisualTransformation().filter(
            AnnotatedString("- [x] done"),
        )

        assertEquals("☑ done", transformed.text.text)
    }
}
