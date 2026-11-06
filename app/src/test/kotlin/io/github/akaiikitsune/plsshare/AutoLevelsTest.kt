package io.github.akaiikitsune.plsshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Auto button must still set brightness / contrast, follow the film preview, and be undoable. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31], qualifiers = "w412dp-h915dp-night-xxhdpi")
class AutoLevelsTest {
    private fun call(activity: Any, name: String, vararg args: Any?) {
        val m = MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(activity, *args)
    }

    private fun field(activity: Any, name: String): Any {
        val f = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(activity)
    }

    private fun sample(): Bitmap = javaClass.getResourceAsStream("/sample.jpg")!!.use { Photos.scaledForEditing(BitmapFactory.decodeStream(it)!!, 1400) }

    @Test
    fun `auto sets levels, follows the film preview, and undoes`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val photo = sample()
        MainActivity::class.java.getDeclaredField("photo").apply { isAccessible = true }.set(activity, photo)
        MainActivity::class.java.getDeclaredField("editorPhoto").apply { isAccessible = true }.set(activity, photo)
        call(activity, "refreshPreview")
        call(activity, "openEditor")
        val editor = field(activity, "editorView") as PhotoEditorView

        assertEquals(0f, editor.state.brightness, 0f)
        assertEquals(0f, editor.state.contrast, 0f)

        editor.filmPreview = false
        call(activity, "autoLevels")
        val plainB = editor.state.brightness
        val plainC = editor.state.contrast
        println("auto (plain): brightness $plainB contrast $plainC")
        assertTrue("auto changed something", plainB != 0f || plainC != 0f)

        // Undo restores the neutral state.
        call(activity, "undo")
        assertEquals(0f, editor.state.brightness, 0f)
        assertEquals(0f, editor.state.contrast, 0f)

        // With the film preview on, the target range is the film's latitude, so the result differs.
        editor.filmPreview = true
        call(activity, "autoLevels")
        println("auto (film): brightness ${editor.state.brightness} contrast ${editor.state.contrast}")
        assertNotEquals(plainB, editor.state.brightness, 0.5f)
        assertTrue(editor.state.brightness != 0f || editor.state.contrast != 0f)
    }
}
