package io.github.akaiikitsune.plsshare

import java.util.Locale

/** UI text in French and English, picked from the phone's language. Kept in code so the app has no resource IDs to depend on. */
class Strings private constructor(private val fr: Boolean) {
    companion object {
        fun forDefaultLocale() = Strings(Locale.getDefault().language == "fr")
    }

    private fun t(french: String, english: String) = if (fr) french else english

    val title get() = "pls share SP-3"

    // Connection status
    val noWifi get() = t("Pas de Wi-Fi", "No Wi-Fi")
    val noWifiDetail get() = t("Rejoignez le réseau de l'imprimante. Le guide explique comment.", "Join the printer's network. The guide shows how.")
    val otherWifi get() = t("Pas sur le Wi-Fi de l'imprimante", "Not on the printer's Wi-Fi")
    val otherWifiDetail get() = t("Le Wi-Fi actuel n'est pas INSTAX-… Rejoignez-le dans les réglages.", "The current Wi-Fi is not INSTAX-… Join it in the settings.")
    val waitingPrinter get() = t("Imprimante en préparation…", "Printer getting ready…")
    val waitingPrinterDetail get() = t("Réseau INSTAX actif. L'imprimante met 1 à 2 minutes à répondre après l'allumage.", "INSTAX network active. The printer needs 1 to 2 minutes to answer after switching on.")
    val connected get() = t("Connectée", "Connected")
    val connectedDetail get() = t("%s · %d films · batterie %d/3%s · toucher pour actualiser", "%s · %d sheets · battery %d/3%s · tap to refresh")
    val charging get() = t(" (en charge)", " (charging)")
    val coverOpen get() = t("Capot du film ouvert", "Film cover open")
    val noFilm get() = t("Plus de film", "Out of film")
    val lowBattery get() = t("Batterie faible, branchez l'imprimante", "Battery low, plug the printer in")
    val guide get() = t("Guide de connexion", "Connection guide")
    val wifiSettings get() = t("Réglages Wi-Fi", "Wi-Fi settings")

    // Home
    val pickPhoto get() = t("Choisir une photo", "Choose a photo")
    val edit get() = t("Modifier", "Edit")
    val print get() = t("Imprimer", "Print")
    val tapToPick get() = t("Touchez pour choisir une photo", "Tap to choose a photo")
    val noPhoto get() = t("Choisissez d'abord une photo.", "Choose a photo first.")
    val notConnectedYet get() = t("L'imprimante n'est pas connectée.", "The printer is not connected.")
    val busy get() = t("Impression en cours, patientez.", "A print is running, please wait.")
    val shared get() = t("Photo reçue.", "Photo received.")
    val photoError get() = t("Impossible de lire cette photo : %s", "Could not read this photo: %s")

    // Print progress
    val reaching get() = t("L'imprimante ne répond pas encore (%s), essai %d…", "The printer is not answering yet (%s), attempt %d…")
    val locking get() = t("Connexion à l'imprimante…", "Contacting the printer…")
    val sending get() = t("Envoi de la photo", "Sending the photo")
    val waiting get() = t("L'imprimante prépare le tirage…", "The printer is getting ready…")
    val printing get() = t("Impression, environ %d s…", "Printing, about %d s…")
    val done get() = t("Tirage terminé ! Il reste %d films.", "Print finished! %d sheets left.")
    val transferOnlyDone get() = t("Test terminé : image reçue par l'imprimante, rien d'imprimé.", "Test finished: the printer received the image, nothing printed.")
    val networkError get() = t("Impossible de joindre l'imprimante (%s).", "Could not reach the printer (%s).")
    val refused get() = t("L'imprimante refuse les connexions (%s). Attendez une minute, ou éteignez-la et rallumez-la.", "The printer refuses connections (%s). Wait a minute, or switch it off and on.")
    val printerError get() = t("L'imprimante a répondu : %s", "The printer answered: %s")

    // Editor
    val editorTitle get() = t("Cadrage et couleurs", "Framing and colours")
    val cancel get() = t("Annuler", "Cancel")
    val ok get() = "OK"
    val rotate get() = t("Tourner", "Rotate")
    val fill get() = t("Remplir", "Fill")
    val whole get() = t("Entière", "Whole")
    val blackWhite get() = t("N&B", "B&W")
    val reset get() = t("Réinit.", "Reset")
    val brightness get() = t("Luminosité", "Brightness")
    val contrast get() = t("Contraste", "Contrast")
    val saturation get() = t("Saturation", "Saturation")
    val editorHint get() = t("Pincez pour zoomer, glissez pour déplacer. Le bord épais du tirage est en bas.", "Pinch to zoom, drag to move. The thick edge of the print is at the bottom.")

    // Menu
    val menuGuide get() = t("Guide de connexion", "Connection guide")
    val menuPin get() = t("PIN de l'imprimante…", "Printer PIN…")
    val menuDebug get() = t("Mode debug", "Debug mode")
    val pinTitle get() = t("PIN de l'imprimante", "Printer PIN")
    val pinHint get() = t("4 chiffres, 1111 par défaut", "4 digits, default 1111")
    val badPin get() = t("Le PIN doit être un nombre de 0 à 9999.", "The PIN must be a number from 0 to 9999.")

    // Guide
    val guideTitle get() = t("Connecter l'imprimante", "Connect the printer")
    val guideIntro get() = t(
        "La SP-3 crée son propre Wi-Fi « INSTAX-XXXXXXXX ». Sur les téléphones récents elle peut planter pendant l'attribution automatique " +
            "d'adresse (DHCP) : on lui donne donc une adresse fixe, une seule fois. Ensuite il suffit de rejoindre ce Wi-Fi.",
        "The SP-3 creates its own “INSTAX-XXXXXXXX” Wi-Fi. On recent phones it can crash during automatic address assignment (DHCP), " +
            "so we give it a fixed address, once. After that, just join that Wi-Fi.",
    )
    val guideSteps get() = listOf(
        t("Allumez l'imprimante et attendez que son voyant Wi-Fi soit allumé.", "Switch the printer on and wait for its Wi-Fi light."),
        t("Ouvrez les réglages Wi-Fi d'Android et touchez le réseau INSTAX-XXXXXXXX (ouvert, sans mot de passe).", "Open the Android Wi-Fi settings and tap the INSTAX-XXXXXXXX network (open, no password)."),
        t("Avant de vous connecter, ouvrez « Options avancées » et réglez : Paramètres IP = Statique · Adresse IP 192.168.0.5 · Passerelle 192.168.0.251 · Longueur du préfixe 24 · DNS 1 192.168.0.251.", "Before connecting, open “Advanced options” and set: IP settings = Static · IP address 192.168.0.5 · Gateway 192.168.0.251 · Prefix length 24 · DNS 1 192.168.0.251."),
        t("Dans « Confidentialité », choisissez « Utiliser l'adresse MAC de l'appareil ».", "Under “Privacy”, choose “Use device MAC”."),
        t("Connectez-vous. Si Android prévient que ce réseau n'a pas d'accès Internet, choisissez « Rester connecté » (et « Ne plus demander »).", "Connect. If Android warns the network has no internet, choose “Stay connected” (and “Don't ask again”)."),
        t("Revenez dans l'appli : le statut passe à « Connectée » tout seul. L'imprimante peut mettre 1 à 2 minutes à répondre après l'allumage.", "Come back to the app: the status turns to “Connected” by itself. The printer can take 1 to 2 minutes to answer after switching on."),
    )
    val guideNotes get() = t(
        "Les données mobiles peuvent rester activées. Le réglage ne se fait qu'une fois : les fois suivantes, allumez l'imprimante, rejoignez son Wi-Fi, imprimez.",
        "Mobile data can stay on. The setup is done once: next time, switch the printer on, join its Wi-Fi, print.",
    )
    val back get() = t("Retour", "Back")

    // Debug
    val debugTitle get() = "Debug"
    val host get() = t("Adresse de l'imprimante", "Printer address")
    val transferOnly get() = t("Mode test : envoyer l'image sans imprimer (n'utilise pas de film)", "Test mode: send the image without printing (uses no film)")
    val requestNetwork get() = t("Demander le réseau INSTAX via l'appli (DHCP, expérimental)", "Request the INSTAX network through the app (DHCP, experimental)")
    val requesting get() = t("Demande du réseau INSTAX… choisissez l'imprimante dans la fenêtre Android.", "Requesting the INSTAX network… pick the printer in the Android dialog.")
    val requestFailed get() = t("Demande de réseau échouée ou expirée.", "Network request failed or timed out.")
    val journal get() = t("Journal", "Log")
    val copyLog get() = t("Copier", "Copy")
    val shareLog get() = t("Partager", "Share")
    val logCopied get() = t("Journal copié.", "Log copied.")

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
