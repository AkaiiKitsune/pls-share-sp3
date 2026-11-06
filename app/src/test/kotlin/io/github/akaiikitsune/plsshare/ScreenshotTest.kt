package io.github.akaiikitsune.plsshare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the app's screens to PNG files, so the layout can be looked at without a device.
 * Output: app/build/screenshots (PNG). Run with: ./gradlew :app:testDebugUnitTest --tests ScreenshotTest
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31], qualifiers = "w412dp-h915dp-night-xxhdpi")
class ScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun field(activity: Any, name: String): Any {
        val f = MainActivity::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(activity)
    }

    private fun call(activity: Any, name: String, vararg args: Any?) {
        val m = MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(activity, *args)
    }

    private fun shoot(view: View, name: String, width: Int, height: Int, scrollTo: Int = 0) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        if (scrollTo > 0) findScroll(view)?.scrollTo(0, scrollTo)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF121212.toInt())
        view.draw(canvas)
        FileOutputStream(File(outDir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${File(outDir, "$name.png").absolutePath}")
    }

    private fun samplePhoto(): Bitmap {
        javaClass.getResourceAsStream("/sample.jpg")?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input)?.let { return Photos.scaledForEditing(it, 1400) }
        }
        val b = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = android.graphics.Paint()
        p.shader = android.graphics.LinearGradient(0f, 0f, 1200f, 900f, intArrayOf(0xFF2C5F8A.toInt(), 0xFFE8B86D.toInt(), 0xFFB03A48.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1200f, 900f, p)
        p.shader = null
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(800f, 350f, 160f, p)
        p.color = 0xFF1B1B1B.toInt()
        c.drawRect(100f, 600f, 700f, 850f, p)
        return b
    }

    @Test
    fun screens() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val metrics = activity.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        println("display ${w}x${h} density ${metrics.density}")

        val home = field(activity, "homeView") as View
        shoot(home, "01-home-empty", w, h)

        // Load a sample photo through the private path the picker uses.
        val photoField = MainActivity::class.java.getDeclaredField("photo").apply { isAccessible = true }
        val editorPhotoField = MainActivity::class.java.getDeclaredField("editorPhoto").apply { isAccessible = true }
        val sample = samplePhoto()
        photoField.set(activity, sample)
        editorPhotoField.set(activity, sample)
        call(activity, "refreshPreview")
        call(activity, "updateButtons")
        shoot(home, "02-home-photo", w, h)

        call(activity, "openEditor")
        val editor = field(activity, "editorScreen") as View
        shoot(editor, "03-editor-adjust", w, h)

        call(activity, "showTab", false)
        shoot(editor, "04-editor-texts", w, h)

        call(activity, "showTab", true)
        shoot(editor, "05-editor-adjust-scrolled", w, h, scrollTo = 1800)
        findScroll(editor)?.scrollTo(0, 0)

        val guide = field(activity, "guideView") as View
        shoot(guide, "06-guide", w, h)

        call(activity, "refreshChart")
        val calibrate = field(activity, "calibrateView") as View
        shoot(calibrate, "07-calibrate", w, h)
    }

    private fun findScroll(root: View): android.widget.ScrollView? {
        if (root is android.widget.ScrollView) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) findScroll(root.getChildAt(i))?.let { return it }
        return null
    }
}
