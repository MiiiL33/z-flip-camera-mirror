package com.flipmirror.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test trivial para que el job de CI tenga algo que ejecutar desde el día 1.
 */
class SanityTest {
    @Test
    fun sanityCheckBasicArithmetic() {
        assertEquals(4, 2 + 2)
    }
}
