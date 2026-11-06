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
        call(activity, "addOverlay", Overlay(Overlay.Kind.DATE))
        call(activity, "addOverlay", Overlay(Overlay.Kind.EMOJI, text = "🎉", size = 0.14f).also { it.x = 0.8f; it.y = 0.25f; it.rotation = 20f })
        shoot(editor, "04-editor-texts", w, h)

        call(activity, "showTab", true)
        shoot(editor, "05-editor-adjust-scrolled", w, h, scrollTo = 1800)
        findScroll(editor)?.scrollTo(0, 0)

        val guide = field(activity, "guideView") as View
        shoot(guide, "06-guide", w, h)

        call(activity, "refreshChart")
        val calibrate = field(activity, "calibrateView") as View
        shoot(calibrate, "07-calibrate", w, h)

        val collageScreen = field(activity, "collageView") as View
        val collage = field(activity, "collage") as CollageView
        collage.layout = CollageLayout.BIG_LEFT
        collage.setCell(0, sample)
        collage.setCell(2, sample)
        shoot(collageScreen, "08-collage", w, h)

        // Measurement screen with the daylight chart photo, corners marked and the fit shown.
        val chart = javaClass.getResourceAsStream("/chart-daylight.jpg")?.use { android.graphics.BitmapFactory.decodeStream(it) }
        if (chart != null) {
            val scaled = Photos.scaledForEditing(chart, 2000)
            val k = scaled.width.toFloat() / chart.width
            val cornerPick = field(activity, "cornerPick") as CornerPickView
            cornerPick.bitmap = scaled
            for ((x, y) in listOf(639 to 1214, 2332 to 1226, 2320 to 2824, 627 to 2812)) cornerPick.corners += floatArrayOf(x * k, y * k)
            call(activity, "syncMeasureButtons")
            val reading = ChartReader.read(scaled, cornerPick.cornersArray()!!, FilmType.COLOR)
            val fit = ChartReader.fit(reading, FilmType.COLOR)
            val model = fit.model
            (field(activity, "measureReport") as android.widget.TextView).text = "Résultat : %.1f %%.".format(fit.greyRms)
            val strip = MainActivity::class.java.getDeclaredMethod("comparisonStrip", ChartReader.Reading::class.java, FilmModel::class.java).apply { isAccessible = true }
            (field(activity, "measureCompare") as android.widget.ImageView).setImageBitmap(strip.invoke(activity, reading, model) as Bitmap)
            val patches = MainActivity::class.java.getDeclaredMethod("patchStrip", ChartReader.Reading::class.java, FilmModel::class.java).apply { isAccessible = true }
            (field(activity, "measureColourCompare") as android.widget.ImageView).setImageBitmap(patches.invoke(activity, reading, model) as Bitmap)
            val hues = MainActivity::class.java.getDeclaredMethod("describeHues", FilmModel::class.java).apply { isAccessible = true }
            (field(activity, "measureColourReport") as android.widget.TextView).text =
                "Couleurs : erreur %.1f %% avec les réglages globaux, %.1f %% après correction par teinte.\n%s".format(fit.colourRmsBefore, fit.colourRmsAfter, hues.invoke(activity, model))
            (field(activity, "measureResultBox") as View).visibility = View.VISIBLE
            val measure = field(activity, "measureView") as View
            shoot(measure, "09-measure", w, h, scrollTo = 300)
            shoot(measure, "09b-measure-result", w, h, scrollTo = 5000)
            // Press on corner 3 to show the loupe.
            val v3 = cornerPick.javaClass.getDeclaredMethod("toView", FloatArray::class.java).apply { isAccessible = true }.invoke(cornerPick, cornerPick.corners[2]) as FloatArray
            val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, v3[0], v3[1], 0)
            cornerPick.dispatchTouchEvent(down)
            shoot(measure, "09c-measure-loupe", w, h, scrollTo = 300)
            cornerPick.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_UP, v3[0], v3[1], 0))
        }
    }

    private fun findScroll(root: View): android.widget.ScrollView? {
        if (root is android.widget.ScrollView) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) findScroll(root.getChildAt(i))?.let { return it }
        return null
    }
}
