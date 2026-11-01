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
    val light get() = t("Lumière", "Light")
    val colour get() = t("Couleur", "Colour")
    val details get() = t("Détails", "Details")
    val brightness get() = t("Exposition", "Exposure")
    val contrast get() = t("Contraste", "Contrast")
    val shadows get() = t("Ombres", "Shadows")
    val highlights get() = t("Hautes lumières", "Highlights")
    val blacks get() = t("Noirs", "Blacks")
    val whites get() = t("Blancs", "Whites")
    val temperature get() = t("Température", "Temperature")
    val tint get() = t("Teinte", "Tint")
    val saturation get() = t("Saturation", "Saturation")
    val sharpness get() = t("Netteté", "Sharpness")
    val vignette get() = t("Vignettage", "Vignette")
    val compare get() = t("Original (maintenir)", "Original (hold)")
    val tabAdjust get() = t("Réglages", "Adjust")
    val tabTexts get() = t("Textes", "Texts")
    val addText get() = t("+ Texte", "+ Text")
    val addDate get() = t("+ Date", "+ Date")
    val addTime get() = t("+ Heure", "+ Time")
    val addDateTime get() = t("+ Date et heure", "+ Date and time")
    val textsHint get() = t("Touchez un texte sur la photo pour le sélectionner, glissez pour le déplacer, pincez pour changer sa taille.", "Tap a text on the photo to select it, drag to move it, pinch to resize it.")
    val noOverlay get() = t("Aucun texte pour l'instant.", "No text yet.")
    val overlayText get() = t("Texte", "Text")
    val overlaySize get() = t("Taille (hauteur sur le tirage de 800 px)", "Size (height on the 800 px print)")
    val overlaySizePrompt get() = t("Hauteur du texte en pixels (12 à 200)", "Text height in pixels (12 to 200)")
    val overlayDark get() = t("Texte sombre", "Dark text")
    val styleShadow get() = t("Ombre", "Shadow")
    val styleOutline get() = t("Contour", "Outline")
    val stylePill get() = t("Bulle", "Pill")
    val remove get() = t("Supprimer", "Remove")
    val overlayFont get() = t("Police", "Font")
    val overlayPosition get() = t("Position", "Position")
    val overlayMargin get() = t("Marge d'alignement", "Alignment margin")
    fun fontName(font: Overlay.Font): String = when (font) {
        Overlay.Font.BOLD -> t("Gras", "Bold")
        Overlay.Font.LIGHT -> t("Fin", "Light")
        Overlay.Font.CONDENSED -> t("Condensé", "Condensed")
        Overlay.Font.SERIF -> "Serif"
        Overlay.Font.MONO -> "Mono"
        Overlay.Font.CURSIVE -> t("Manuscrite", "Script")
        Overlay.Font.CASUAL -> t("Décontractée", "Casual")
    }
    val photoDateKnown get() = t("Date de la photo : %s", "Photo date: %s")
    val photoDateUnknown get() = t("Date de la photo inconnue : l'heure actuelle sera utilisée.", "Photo date unknown: the current time will be used.")
    val filmPreview get() = t("Aperçu film", "Film preview")
    val filmPreviewHint get() = t("Simulation approximative du rendu instax (moins de contraste et de saturation, noirs relevés). N'affecte pas le tirage.", "Rough simulation of the instax rendering (less contrast and saturation, lifted blacks). Does not affect the print.")
    val auto get() = t("Auto", "Auto")
    val autoDone get() = t("Niveaux ajustés automatiquement.", "Levels adjusted automatically.")
    val autoNothing get() = t("La photo utilise déjà toute la plage de tons.", "The photo already uses the full tonal range.")
    val frame get() = t("Cadre", "Frame")
    val frameWidth get() = t("Bordure", "Border")
    val frameBand get() = t("Bandeau en bas", "Bottom band")
    val cornerRadius get() = t("Coins arrondis", "Rounded corners")
    val frameColor get() = t("Couleur du cadre", "Frame colour")
    val myPresets get() = t("Mes presets", "My presets")
    val savePreset get() = t("Enregistrer…", "Save…")
    val presetName get() = t("Nom du preset", "Preset name")
    val presetSaved get() = t("Preset enregistré.", "Preset saved.")
    val presetDelete get() = t("Supprimer le preset « %s » ?", "Delete preset “%s”?")
    val presetHint get() = t("Un preset garde filtre, couleurs, tons, détails et cadre. Appui long pour supprimer.", "A preset keeps filter, colours, tones, details and frame. Long press to delete.")
    val histogram get() = t("Histogramme", "Histogram")
    val clipping get() = t("Zones écrêtées", "Clipping")
    val clippingHint get() = t("Rouge : hautes lumières brûlées · Bleu : ombres bouchées", "Red: blown highlights · Blue: crushed shadows")
    val editorHint get() = t("Pincez pour zoomer, tournez à deux doigts, glissez pour déplacer. Bords, centre et angles droits s'aimantent. Le bord épais du tirage est en bas.", "Pinch to zoom, twist with two fingers to rotate, drag to move. Edges, centre and right angles snap. The thick edge of the print is at the bottom.")

    // Printing service, queue, history
    val printingTitle get() = t("Impression", "Printing")
    val printingStart get() = t("Préparation…", "Getting ready…")
    val printingDone get() = t("Terminé : %d tirage(s), %d échec(s).", "Done: %d print(s), %d failed.")
    val noFilmStop get() = t("Plus de film : file arrêtée.", "Out of film: queue stopped.")
    val addToQueue get() = t("Ajouter à la file", "Add to queue")
    val queueTitle get() = t("File d'impression", "Print queue")
    val queueEmpty get() = t("Vide. « Ajouter à la file » met la photo en cours en attente ; « Ajouter plusieurs » applique le réglage actuel à plusieurs photos.", "Empty. “Add to queue” holds the current photo; “Add several” applies the current look to several photos.")
    val printQueue get() = t("Imprimer la file (%d)", "Print queue (%d)")
    val addSeveral get() = t("Ajouter plusieurs…", "Add several…")
    val clearQueue get() = t("Vider", "Clear")
    val removeFromQueue get() = t("Retirer de la file ?", "Remove from the queue?")
    val queued get() = t("Ajouté à la file (%d en attente).", "Added to the queue (%d waiting).")
    val menuHistory get() = t("Historique des tirages", "Print history")
    val historyTitle get() = t("Historique", "History")
    val historyEmpty get() = t("Aucun tirage pour l'instant.", "No prints yet.")
    val reprint get() = t("Réimprimer", "Reprint")
    val share get() = t("Partager", "Share")
    val menuExport get() = t("Exporter / partager l'image", "Export / share the image")
    val exported get() = t("Image enregistrée dans Photos › pls share SP-3.", "Image saved to Photos › pls share SP-3.")
    val exportFailed get() = t("Impossible d'enregistrer l'image.", "Could not save the image.")
    val deleteEntry get() = t("Supprimer cet élément ?", "Delete this item?")
    val notificationsHint get() = t("Autorisez les notifications pour suivre l'impression quand l'appli est en arrière-plan.", "Allow notifications to follow printing while the app is in the background.")

    // Menu
    val menuGuide get() = t("Guide de connexion", "Connection guide")
    val menuPin get() = t("PIN de l'imprimante…", "Printer PIN…")
    val menuDebug get() = t("Mode debug", "Debug mode")
    val menuQueue get() = t("File d'impression", "Print queue")
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
    val guideFilmTitle get() = t("Quel film ?", "Which film?")
    val guideFilm get() = t(
        "La SP-3 prend le film instax SQUARE (packs de 10, vendus par deux). Toutes les variantes passent, seul le cadre change. " +
            "Pour imprimer en noir et blanc, le film SQUARE Monochrome donne des gris vraiment neutres et des noirs plus profonds : " +
            "sur du film couleur, le noir et blanc prend souvent une légère dominante. Avec du Monochrome, saturation et filtres couleur n'ont plus d'effet, gardez luminosité et contraste.",
        "The SP-3 takes instax SQUARE film (packs of 10, sold in twin packs). Every variant works, only the frame differs. " +
            "For black and white, SQUARE Monochrome film gives truly neutral greys and deeper blacks: on colour film, black and white often picks up a slight tint. " +
            "With Monochrome, saturation and colour filters have no effect; keep brightness and contrast.",
    )
    val back get() = t("Retour", "Back")

    // Filters
    val filters get() = t("Filtres", "Filters")
    fun filterName(id: String): String = when (id) {
        "normal" -> t("Original", "Original")
        "vivid" -> t("Vif", "Vivid")
        "warm" -> t("Chaud", "Warm")
        "cool" -> t("Froid", "Cool")
        "fade" -> t("Délavé", "Fade")
        "retro" -> t("Rétro", "Retro")
        "sepia" -> t("Sépia", "Sepia")
        "mono" -> t("Mono", "Mono")
        "noir" -> t("Noir", "Noir")
        "pop" -> t("Pop", "Pop")
        else -> id
    }

    // Updates
    val menuUpdate get() = t("Vérifier les mises à jour", "Check for updates")
    val updateChecking get() = t("Recherche de mise à jour…", "Checking for updates…")
    val updateNone get() = t("Vous avez la dernière version (build %d).", "You have the latest version (build %d).")
    val updateError get() = t("Impossible de vérifier les mises à jour (%s).", "Could not check for updates (%s).")
    val updateTitle get() = t("Nouvelle version", "New version")
    val updateBody get() = t("%s (build %d) est disponible, vous avez le build %d. Télécharger et installer ?", "%s (build %d) is available, you have build %d. Download and install?")
    val updateInstall get() = t("Installer", "Install")
    val updateLater get() = t("Plus tard", "Later")
    val updateDownloading get() = t("Téléchargement de la mise à jour…", "Downloading the update…")
    val updateFailed get() = t("Le téléchargement a échoué.", "The download failed.")

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
