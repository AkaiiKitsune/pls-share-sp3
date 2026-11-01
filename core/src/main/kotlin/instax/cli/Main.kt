package instax.cli

import instax.DesktopImages
import instax.Fit
import instax.InstaxException
import instax.InstaxPrinter
import instax.PrintEvent
import instax.Simulator
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val USAGE = """usage: instax-print [options] photo.jpg
       instax-print --info
       instax-print --simulate [--save-dir DIR]

Print a photo on a Fujifilm Instax SHARE SP-2 / SP-3 over Wi-Fi.
Join the printer's network (INSTAX-XXXXXXXX) first.

options:
  --host HOST        printer address (default 192.168.0.251)
  --port PORT        printer port (default 8080)
  --pin PIN          printer PIN (default 1111)
  --fit crop|contain crop fills the print, contain adds white bars (default crop)
  --preview out.png  save the image as it would print on an SP-3, without connecting
  --size WxH         print size for --preview (default 800x800; the SP-2 is 600x800)
  --info             show the printer's model, firmware, battery and film
  --off              turn the printer off
  --transfer-only    send the image to the printer but do not print it (uses no film)
  --simulate         run a fake printer on --host/--port instead (default 0.0.0.0:8080)
  --save-dir DIR     with --simulate, save each received image as a PNG there
  -v                 log every frame
"""

fun main(args: Array<String>) {
    var host = InstaxPrinter.DEFAULT_HOST
    var port = InstaxPrinter.DEFAULT_PORT
    var pin = InstaxPrinter.DEFAULT_PIN
    var fit = Fit.CROP
    var preview: File? = null
    var size = 800 to 800
    var info = false
    var off = false
    var transferOnly = false
    var simulate = false
    var saveDir: File? = null
    var verbose = false
    var photo: File? = null

    val it = args.iterator()
    fun next(flag: String): String = if (it.hasNext()) it.next() else fail("$flag needs a value")
    while (it.hasNext()) {
        when (val a = it.next()) {
            "--host" -> host = next(a)
            "--port" -> port = next(a).toIntOrNull() ?: fail("bad port")
            "--pin" -> pin = next(a).toIntOrNull()?.takeIf { it in 0..9999 } ?: fail("the PIN must be 0 to 9999")
            "--fit" -> fit = when (next(a)) { "crop" -> Fit.CROP; "contain" -> Fit.CONTAIN; else -> fail("--fit takes crop or contain") }
            "--preview" -> preview = File(next(a))
            "--size" -> size = next(a).split("x").let { p -> if (p.size != 2) fail("bad --size") else (p[0].toInt() to p[1].toInt()) }
            "--info" -> info = true
            "--off" -> off = true
            "--transfer-only" -> transferOnly = true
            "--simulate" -> simulate = true
            "--save-dir" -> saveDir = File(next(a))
            "-v", "--verbose" -> verbose = true
            "-h", "--help" -> { print(USAGE); return }
            else -> if (a.startsWith("-")) fail("unknown option $a") else photo = File(a)
        }
    }

    val log: ((String) -> Unit)? = if (verbose) { m -> System.err.println(m) } else null

    if (simulate) {
        runSimulator(if (host == InstaxPrinter.DEFAULT_HOST) "0.0.0.0" else host, port, pin, saveDir)
        return
    }

    try {
        val printer = InstaxPrinter(host, port, pin, logger = log)
        when {
            info -> {
                val i = printer.info()
                println("Model:      ${i.model}")
                println("Firmware:   ${i.firmware} (hardware ${i.hardware})")
                println("Battery:    ${i.batteryLevel}/3${if (i.charging) ", charging" else ""}")
                println("Film left:  ${i.filmRemaining}${if (i.coverOpen) " (cover open)" else ""}")
                println("Prints:     ${i.printCount}")
                println("Print size: ${i.width}x${i.height}")
            }
            off -> { printer.powerOff(); println("Printer switched off") }
            photo == null -> fail("give a photo to print, or --info / --simulate")
            else -> {
                val image = DesktopImages.load(photo)
                if (preview != null) {
                    ImageIO.write(DesktopImages.prepare(image, size.first, size.second, fit), "png", preview)
                    println("Preview saved to ${preview.path}")
                    return
                }
                printer.print(DesktopImages.source(image, fit), transferOnly = transferOnly) { event ->
                    when (event) {
                        PrintEvent.Locking -> println("Connecting to $host:$port...")
                        is PrintEvent.Ready -> println("${event.info.model}, ${event.info.filmRemaining} sheets left, battery ${event.info.batteryLevel}/3")
                        is PrintEvent.Transferring -> if (event.sentBytes == event.totalBytes) println("Image sent") else print("\rSending ${100 * event.sentBytes / event.totalBytes}%")
                        PrintEvent.WaitingForPrinter -> {}
                        is PrintEvent.Printing -> println("Printing, about ${event.estimatedSeconds.toInt()} s...")
                        is PrintEvent.Finished -> println("Done, ${event.filmRemaining} sheets left")
                        PrintEvent.TransferOnlyFinished -> println("Transfer test done, nothing printed")
                        PrintEvent.Unlocked -> {}
                    }
                }
            }
        }
    } catch (e: InstaxException) {
        fail("error: ${e.message}")
    } catch (e: IOException) {
        fail("network error: ${e.message} (are you on the INSTAX-XXXXXXXX Wi-Fi?)")
    }
}

private fun runSimulator(host: String, port: Int, pin: Int, saveDir: File?) {
    saveDir?.mkdirs()
    val simulator = Simulator(host, port, pin = pin, onImage = { data ->
        if (saveDir != null) {
            val file = File(saveDir, java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date()) + ".png")
            ImageIO.write(DesktopImages.fromRaw(data, 800, 800), "png", file)
            println("Saved the received image to ${file.path}")
        } else {
            println("Received a ${data.size}-byte image")
        }
    })
    println("Simulating an SP-3 on ${simulator.host}:${simulator.port} (PIN $pin). Ctrl-C to stop.")
    simulator.serve()
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}
