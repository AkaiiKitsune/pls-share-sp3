package io.github.akaiikitsune.plsshare

import java.util.Locale

/** UI text in French and English, picked from the phone's language. Kept in code so the app has no resource IDs to depend on. */
class Strings private constructor(private val fr: Boolean) {
    companion object {
        fun forDefaultLocale() = Strings(Locale.getDefault().language == "fr")
    }

    private fun t(french: String, english: String) = if (fr) french else english

    val title get() = "pls share SP-3"
    val subtitle get() = t("Impression Instax SHARE SP-2 / SP-3 sans l'appli officielle", "Print to an Instax SHARE SP-2 / SP-3 without the official app")
    val connect get() = t("1. Connecter l'imprimante", "1. Connect the printer")
    val connecting get() = t("Connexion au Wi-Fi INSTAX-… Choisissez l'imprimante dans la fenêtre système.", "Joining the INSTAX-… Wi-Fi. Pick the printer in the system dialog.")
    val connected get() = t("Connecté au Wi-Fi de l'imprimante.", "Connected to the printer's Wi-Fi.")
    val connectFailed get() = t("Pas de réseau INSTAX trouvé. Allumez l'imprimante (voyant Wi-Fi) et réessayez.", "No INSTAX network found. Switch the printer on (Wi-Fi light) and try again.")
    val lost get() = t("Connexion à l'imprimante perdue.", "Lost the connection to the printer.")
    val notConnected get() = t("Non connecté. Si vous avez déjà rejoint le Wi-Fi INSTAX- à la main, l'appli l'utilisera.", "Not connected. If you already joined the INSTAX- Wi-Fi by hand, the app will use it.")
    val pickPhoto get() = t("2. Choisir une photo", "2. Pick a photo")
    val print get() = t("3. Imprimer", "3. Print")
    val info get() = t("Infos imprimante", "Printer info")
    val fitCrop get() = t("Recadrer pour remplir", "Crop to fill")
    val fitContain get() = t("Photo entière (bandes blanches)", "Whole photo (white bars)")
    val pin get() = t("PIN de l'imprimante (1111 par défaut)", "Printer PIN (default 1111)")
    val host get() = t("Adresse de l'imprimante", "Printer address")
    val noPhoto get() = t("Choisissez d'abord une photo.", "Pick a photo first.")
    val badPin get() = t("Le PIN doit avoir 4 chiffres.", "The PIN must be 4 digits.")
    val busy get() = t("Impression en cours, patientez.", "A print is running, please wait.")
    val locking get() = t("Connexion à l'imprimante…", "Contacting the printer…")
    val sending get() = t("Envoi de la photo", "Sending the photo")
    val waiting get() = t("L'imprimante prépare le tirage…", "The printer is getting ready…")
    val printing get() = t("Impression, environ %d s…", "Printing, about %d s…")
    val done get() = t("Tirage terminé ! Il reste %d photos.", "Print finished! %d sheets left.")
    val cancelled get() = t("Annulé.", "Cancelled.")
    val networkError get() = t("Impossible de joindre l'imprimante (%s). Êtes-vous sur le Wi-Fi INSTAX-… ?", "Could not reach the printer (%s). Are you on the INSTAX-… Wi-Fi?")
    val printerError get() = t("L'imprimante a répondu : %s", "The printer answered: %s")
    val photoError get() = t("Impossible de lire cette photo : %s", "Could not read this photo: %s")
    val infoLine get() = t("%s · firmware %s · batterie %d/3%s · %d photos restantes · %d tirages au total", "%s · firmware %s · battery %d/3%s · %d sheets left · %d prints in total")
    val charging get() = t(" (en charge)", " (charging)")
    val coverOpen get() = t(" · capot ouvert !", " · cover open!")
    val shared get() = t("Photo reçue. Connectez l'imprimante puis appuyez sur Imprimer.", "Photo received. Connect the printer and tap Print.")
    val hint get() = t(
        "Allumez l'imprimante, appuyez sur « Connecter », puis choisissez le réseau INSTAX-XXXXXXXX dans la fenêtre d'Android. " +
            "L'appli garde ce réseau pour elle : pas besoin de couper les données mobiles ni de bricoler le Wi-Fi.",
        "Switch the printer on, tap “Connect”, then choose the INSTAX-XXXXXXXX network in the Android dialog. " +
            "The app keeps that network for itself: no need to turn mobile data off or fiddle with Wi-Fi settings.",
    )

    fun describeReturnCode(code: Int, fallback: String): String = if (!fr) fallback else when (code) {
        0x7F -> "imprimante occupée"
        0xA0 -> "un autre appareil utilise l'imprimante"
        0xA1 -> "image refusée"
        0xA2 -> "batterie vide"
        0xA3 -> "impression en cours"
        0xA4 -> "éjection en cours"
        0xB4 -> "en charge"
        0xF4 -> "plus de film"
        0xF5, 0xF6, 0xF8 -> "erreur mécanique"
        0xF7 -> "PIN incorrect"
        else -> fallback
    }
}
