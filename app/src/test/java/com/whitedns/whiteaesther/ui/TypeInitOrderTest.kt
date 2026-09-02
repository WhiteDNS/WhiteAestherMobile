package com.whitedns.whiteaesther.ui

import java.net.URLClassLoader
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * That the type scale can be the first thing anything touches.
 *
 * AetherType needs the font families, which live at file scope; the file scope
 * needs AetherType for Material's typography. Two initialisers that need each
 * other work only for whichever runs first -- the second one reads the other
 * half-built, as null, which is a NullPointerException inside a static
 * initialiser and an ExceptionInInitializerError on the way out. On a device
 * that is the app closing on its first frame.
 *
 * The order used to be safe by accident: MaterialTheme was always touched
 * before anything read AetherType directly. Adding a type scale that reads it
 * reversed that, and the cycle closed.
 *
 * Class initialisation is per-JVM and happens once, so a plain test cannot
 * choose the order -- whichever test ran first already decided it. This one
 * loads the classes again in a loader of its own, which is the only way to ask
 * the question honestly.
 */
class TypeInitOrderTest {
    /**
     * A loader that redefines this app's classes and borrows everything else.
     *
     * Compose and the Kotlin runtime come from the parent, so only the classes
     * under test are initialised afresh.
     */
    private fun freshLoader(): ClassLoader {
        val urls = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .map { java.io.File(it).toURI().toURL() }
            .toTypedArray()
        val parent = javaClass.classLoader
        return object : URLClassLoader(urls, parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith("com.whitedns.whiteaesther")) {
                    return super.loadClass(name, resolve)
                }
                findLoadedClass(name)?.let { return it }
                val loaded = findClass(name)
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }
    }

    @Test
    fun theScaleInitialisesWhenNothingHasTouchedTheStylesYet() {
        val loader = freshLoader()

        // Exactly what the theme does, and nothing before it.
        val scale = loader.loadClass("com.whitedns.whiteaesther.ui.theme.TypeScale")
        val companion = scale.getField("Companion").get(null)
        val adjusted = companion.javaClass.getMethod(
            "adjusted",
            Float::class.java,
            Float::class.java,
        )

        assertNotNull(adjusted.invoke(companion, 1.22f, 0f))
    }

    @Test
    fun materialsScaleInitialisesWhenNothingHasTouchedItYet() {
        val loader = freshLoader()

        // The other order, which was the one that used to work. Both have to,
        // or the app depends on which screen is drawn first.
        val file = loader.loadClass("com.whitedns.whiteaesther.ui.theme.TypeKt")

        assertNotNull(file.getMethod("getAetherTypography").invoke(null))
    }
}
