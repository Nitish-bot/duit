package com.gaylord.duit.platform

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingBackendBoundaryTest {
    @Test
    fun `no source set shared with prod references the Pairing backend`() {
        val offenders =
            listOf("src/main", "src/prod")
                .map(::File)
                .filter(File::isDirectory)
                .flatMap { root -> root.walkTopDown().filter(File::isFile).toList() }
                .filter { file ->
                    val text = file.readText()
                    PairingBackendMarkers.any(text::contains)
                }
                .map(File::getPath)

        assertEquals(emptyList<String>(), offenders)
    }

    private companion object {
        val PairingBackendMarkers =
            listOf("PAIRING_BACKEND_URL", "FirebaseFunctionsPairingGateway", "getHttpsCallableFromUrl")
    }
}
