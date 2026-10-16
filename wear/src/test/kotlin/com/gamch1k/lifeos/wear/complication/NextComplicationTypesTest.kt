package com.gamch1k.lifeos.wear.complication

import com.gamch1k.lifeos.wear.today.NextKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** What "Life OS next" offers watch faces, read from the manifest the APK is built with. */
class NextComplicationTypesTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun supportedTypes(service: String): String {
        // Unit tests run in the module's directory.
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val services = document.getElementsByTagName("service")
        val element = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == service }
        val metaData = element.getElementsByTagName("meta-data")
        return (0 until metaData.length).map { metaData.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == "android.support.wearable.complications.SUPPORTED_TYPES" }
            .getAttributeNS(android, "value")
    }

    @Test
    fun `life os next offers text and images, short text first, and nothing with an arc`() {
        // P19-07: with RANGED_VALUE or GOAL_PROGRESS on offer, Samsung's edge slots took them and drew an arc or a line.
        val offered = supportedTypes(".complication.NextEventComplicationService")
        assertEquals("SHORT_TEXT,LONG_TEXT,MONOCHROMATIC_IMAGE,SMALL_IMAGE", offered)
        // Every type offered has its fields in NextUp, and the other way round.
        assertEquals(NextKind.entries.map { it.name }.sorted(), offered.split(",").sorted())
    }
}
