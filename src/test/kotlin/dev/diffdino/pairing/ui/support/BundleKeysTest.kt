package dev.diffdino.pairing.ui.support

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Every message the code asks for is in the bundle, and the bundle holds nothing the code doesn't ask for. */
class BundleKeysTest {
    private val sources = Path.of("src/main/kotlin")
    private val bundle = Path.of("src/main/resources/messages/DiffDinoBundle.properties")

    private fun keysInBundle(): Set<String> = Files.newBufferedReader(bundle, Charsets.UTF_8).use { reader ->
        Properties().apply { load(reader) }.stringPropertyNames()
    }

    private fun sourceText(): String = Files.walk(sources).use { paths ->
        paths.filter { it.toString().endsWith(".kt") }.map { Files.readString(it) }.toList().joinToString("\n")
    }

    @Test
    fun everyKeyTheCodeUsesIsDefined() {
        val used = Regex("""(?:message|HeaderAction)\(\s*"([a-z][a-z0-9.]*)"""").findAll(sourceText()).map { it.groupValues[1] }.toSet()

        assertEquals(emptySet<String>(), used - keysInBundle())
    }

    @Test
    fun noKeyIsLeftUnused() {
        val text = sourceText()

        assertEquals(emptyList<String>(), keysInBundle().filter { !text.contains("\"$it\"") }.sorted())
    }
}
