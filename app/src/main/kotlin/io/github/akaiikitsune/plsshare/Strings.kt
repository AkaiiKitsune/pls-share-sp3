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
    val cancelled get() = t("Annulé avant l'exposition du film.", "Cancelled before the sheet was exposed.")
    val cancelPrint get() = t("Annuler", "Cancel")
    val cancelRequested get() = t("Arrêt demandé. Un tirage déjà lancé va jusqu'au bout.", "Stop requested. A print already started runs to its end.")
    val lowFilm get() = t("Plus que %d film(s).", "Only %d sheet(s) left.")
    val queueTooLong get() = t("La file contient %d photos mais il ne reste que %d film(s). Imprimer les %d premières ?", "The queue holds %d photos but only %d sheet(s) remain. Print the first %d?")
    val printFirst get() = t("Imprimer %d", "Print %d")
    val transferOnlyDone get() = t("Test terminé : image reçue par l'imprimante, rien d'imprimé.", "Test finished: the printer received the image, nothing printed.")
    val networkError get() = t("Impossible de joindre l'imprimante (%s).", "Could not reach the printer (%s).")
    val refused get() = t("L'imprimante refuse les connexions (%s). Attendez une minute, ou éteignez-la et rallumez-la.", "The printer refuses connections (%s). Wait a minute, or switch it off and on.")
    val printerError get() = t("L'imprimante a répondu : %s", "The printer answered: %s")

    // Editor
    val editorTitle get() = t("Cadrage et couleurs", "Framing and colours")
    val cancel get() = t("Annuler", "Cancel")
    val undo get() = t("Annuler la dernière modification", "Undo")
    val menuDescription get() = t("Menu", "Menu")
    val redo get() = t("Rétablir", "Redo")
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
    val compareTip get() = t("Maintenir pour voir la photo sans retouche, avec le même cadrage.", "Hold to see the photo without edits, same framing.")
    val histogramTip get() = t("Répartition des tons : rouge, vert, bleu et luminance (contour blanc).", "Tone distribution: red, green, blue and luminance (white outline).")
    val tabAdjust get() = t("Réglages", "Adjust")
    val tabTexts get() = t("Textes", "Texts")
    val addText get() = t("+ Texte", "+ Text")
    val addDate get() = t("+ Date", "+ Date")
    val addTime get() = t("+ Heure", "+ Time")
    val addDateTime get() = t("+ Date et heure", "+ Date and time")
    val addEmoji get() = t("+ Emoji", "+ Emoji")
    val pickEmoji get() = t("Choisir un emoji", "Pick an emoji")
    val overlayRotation get() = t("Rotation (deux doigts sur la photo)", "Rotation (two fingers on the photo)")
    val textsHint get() = t("Touchez un élément sur la photo pour le sélectionner, glissez pour le déplacer, pincez pour sa taille, tournez à deux doigts.", "Tap an item on the photo to select it, drag to move it, pinch to resize it, twist with two fingers to rotate.")
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
    val autoDone get() = t("Niveaux ajustés sur toute la plage.", "Levels fitted to the full range.")
    val autoDoneFilm get() = t("Niveaux ajustés à la plage utile du film (aperçu film actif).", "Levels fitted to the film's usable range (film preview on).")
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
    val editorHint get() = t("Pincer : zoom · deux doigts : rotation · glisser : déplacer · bords et centre aimantés · nom d'un curseur : remise à zéro", "Pinch: zoom · two fingers: rotate · drag: move · edges and centre snap · slider name: reset")

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

    // Film type
    val filmColor get() = t("Couleur", "Colour")
    val filmMono get() = t("Monochrome", "Monochrome")
    val filmTypeLabel get() = t("Film", "Film")
    val filmChipColor get() = t("Film couleur", "Colour film")
    val filmChipMono get() = t("Film monochrome", "Monochrome film")
    val filmTypeTip get() = t("Le film chargé dans l'imprimante : aperçu, calibration et données envoyées s'y adaptent.", "The film loaded in the printer: preview, calibration and the data sent follow it.")
    val calibrateTitleFor get() = t("Calibrer : film %s", "Calibrate: %s film")
    val monoNote get() = t("Film monochrome : la couleur n'est pas imprimée, l'aperçu et les réglages de couleur sont masqués.", "Monochrome film: colour is not printed, the preview and the colour controls are hidden.")

    // Film calibration
    val menuCalibrate get() = t("Calibrer l'aperçu film…", "Calibrate the film preview…")
    val menuCompensate get() = t("Compenser le tirage", "Compensate prints")
    val calibrateTitle get() = t("Calibrer l'aperçu film", "Calibrate the film preview")
    val calibrateIntro get() = t(
        "L'aperçu film est un modèle : noirs bouchés et blancs écrêtés tôt (latitude réduite), noir et blanc du tirage moins extrêmes que l'écran, demi-tons plus clairs, noirs chauds, couleurs désaturées. " +
            "Les valeurs de départ ont été mesurées sur un tirage réel de cette mire sur une SP-3, mais chaque imprimante et chaque lot de film diffèrent. " +
            "Pour l'ajuster : imprimez la mire ci-dessous, posez le tirage à côté du téléphone à la lumière du jour, " +
            "puis réglez les curseurs jusqu'à ce que la mire simulée à l'écran ressemble au tirage. " +
            "Commencez par les gris (exposition, puis noirs, blancs et contraste), puis la saturation et la dominante, puis le patch bleu.",
        "The film preview is a model: blacks block up and whites clip early (reduced latitude), the print's black and white are less extreme than the screen's, midtones print lighter, blacks are warm, colours desaturated. " +
            "The starting values were measured on a real SP-3 print of this chart, but every printer and film batch differs. " +
            "To fit it: print the chart below, put the print next to the phone in daylight, " +
            "then adjust the sliders until the simulated chart on screen looks like the print. " +
            "Start with the greys (exposure, then blacks, whites and contrast), then saturation and cast, then the blue patch.",
    )
    val calibrateHelp get() = t("Comment ça marche ?", "How does it work?")
    val chartSimulated get() = t("Mire simulée (ce que le tirage devrait montrer)", "Simulated chart (what the print should look like)")
    val printChart get() = t("Imprimer la mire", "Print the chart")
    val printChartConfirm get() = t("Imprimer la mire maintenant ? Cela consomme une pose (il en reste %d). Le tirage se suit sur l'accueil.", "Print the chart now? It uses one sheet (%d left). The print is followed on the home screen.")
    val printChartStarted get() = t("Impression de la mire lancée.", "Chart print started.")
    val printChartTip get() = t("La mire s'imprime toujours sans compensation.", "The chart always prints without compensation.")
    val calExposure get() = t("Exposition (centièmes d'IL)", "Exposure (hundredths of a stop)")
    val calGamma get() = t("Demi-tons (gamma ×100 : < 100 éclaircit)", "Midtones (gamma ×100: < 100 lifts)")
    val calBlack get() = t("Seuil des noirs (% d'entrée bouché)", "Black threshold (% of input blocked)")
    val calWhite get() = t("Seuil des blancs (% d'entrée écrêté)", "White threshold (% of input clipped)")
    val calBlackLevel get() = t("Niveau du noir (% du blanc papier)", "Black level (% of paper white)")
    val calWhiteLevel get() = t("Niveau du blanc (% du blanc papier)", "White level (% of paper white)")
    val calShadowWarmth get() = t("Chaleur des noirs", "Warmth of the blacks")
    val calSaturation get() = t("Saturation", "Saturation")
    val calWarmth get() = t("Dominante bleu ↔ jaune", "Blue ↔ yellow cast")
    val calTint get() = t("Dominante vert ↔ magenta", "Green ↔ magenta cast")
    val calBlue get() = t("Assombrissement des bleus", "Blue darkening")
    val calReset get() = t("Valeurs par défaut", "Default values")
    val measureTitle get() = t("Mesurer la mire", "Measure the chart")
    val measureFromPhoto get() = t("Mesurer une photo du tirage", "Measure a photo of the print")
    val measureIntro get() = t(
        "Imprimez la mire, photographiez le tirage à plat avec le cadre blanc entièrement visible, puis indiquez les quatre coins de la zone image. L'appli lit les gris et les couleurs, les rapporte au blanc du cadre (ce qui annule la lumière, jour ou flash) et en déduit les réglages du film.",
        "Print the chart, photograph the print flat with the white frame fully visible, then mark the four corners of the image area. The app reads the greys and colours, relates them to the frame's white (which cancels the light, daylight or flash) and derives the film settings.",
    )
    val measurePick get() = t("Choisir des photos", "Choose photos")
    val measureStripHint get() = t("Touchez une photo pour vérifier ou corriger ses repères ; les photos grisées n'en ont pas. Appui long pour la retirer. « Mesurer » moyenne toutes les photos qui ont leurs repères.", "Tap a photo to check or fix its markers; dimmed photos have none. Long-press to drop it. “Measure” averages every photo that has its markers.")
    val measureLoaded get() = t("%d photos chargées, coins trouvés sur %d.", "%d photos loaded, corners found on %d.")
    val measureShoot get() = t("Photographier", "Take a photo")
    val measureHint get() = t(
        "Les repères sont posés automatiquement d'après la grille de couleurs : ils marquent le centre des quatre patches de coin (1 rouge en haut à gauche, 2 cyan en haut à droite, 3 gris en bas à droite, 4 bleu ciel en bas à gauche), et le cadre pointillé en découle. Pour corriger, faites glisser un repère : relâché sur un patch, il s'aimante à son centre. Le cadre peut dépasser légèrement l'image visible, c'est normal : l'imprimante rogne et étire l'image de quelques pour cent. Lumière du jour, sans reflet.",
        "The markers are placed automatically from the colour grid: they mark the centres of the four corner patches (1 red top left, 2 cyan top right, 3 grey bottom right, 4 sky blue bottom left) and the dashed outline follows. To correct one, drag it: released on a patch, it snaps to its centre. The outline may slightly exceed the visible image, which is normal: the printer crops and stretches the image by a few percent. Daylight, no glare.",
    )
    val measureMarks get() = t("Repères de la photo", "Photo markers")
    val measureMarksOf get() = t("Repères de la photo %d / %d", "Markers of photo %d / %d")
    val measureWarpedAll get() = t("Mires redressées des %d photos, moyennées.", "Rectified charts of the %d photos, averaged.")
    val measureReset get() = t("Recommencer", "Start over")
    val measureRun get() = t("Mesurer", "Measure")
    val measureResult get() = t("Résultat : %s. En haut les gris mesurés sur le tirage, en bas ceux que simule le nouveau modèle.", "Result: %s. Top: the greys measured on the print; bottom: the greys the new model simulates.")
    val measureColours get() = t(
        "Couleurs : erreur %s avec les réglages globaux, %s après correction par teinte. En haut les patches mesurés, en bas ceux que simule le nouveau modèle.\n%s",
        "Colours: error %s with the global settings, %s after the per-hue correction. Top: the patches measured on the print; bottom: the patches the new model simulates.\n%s",
    )
    val calHues get() = t("Correction par teinte (mesurée sur une photo de la mire)", "Per-hue correction (measured from a photo of the chart)")
    val calHuesNone get() = t("aucune", "none")
    val calHueSat get() = t("sat.", "sat.")
    val calHueLum get() = t("lum.", "lum.")
    val calHuesReset get() = t("Neutraliser les teintes", "Clear the hue correction")
    val calHuesCleared get() = t("Correction par teinte retirée.", "Hue correction cleared.")
    val geometryTitle get() = t("Géométrie du tirage (mesurée sur une photo de la mire)", "Print geometry (measured from a photo of the chart)")
    val geometryNone get() = t("non mesurée : aucune zone cachée n'est affichée, rien n'est corrigé", "not measured: no hidden area is shown, nothing is corrected")
    val geometryReadout get() = t("Étirement vertical %+.1f %%, horizontal %+.1f %% ; le cadre cache %.1f %% en haut, %.1f %% en bas, %.1f %% à gauche, %.1f %% à droite (bandes sombres sur les aperçus).", "Vertical stretch %+.1f %%, horizontal %+.1f %%; the frame hides %.1f %% at the top, %.1f %% at the bottom, %.1f %% on the left, %.1f %% on the right (dark bands on the previews).")
    val geometryTop get() = t("Haut : décalage du haut des gris (fichier 4 %)", "Top: offset of the grey steps' top (file 4 %)")
    val geometryBottom get() = t("Bas : décalage du bas du dégradé (fichier 96 %)", "Bottom: offset of the gradient's bottom (file 96 %)")
    val geometryLeft get() = t("Gauche : décalage du bord gauche des gris (fichier 4 %)", "Left: offset of the grey steps' left edge (file 4 %)")
    val geometryRight get() = t("Droite : décalage du bord droit des patches (fichier 94,8 %)", "Right: offset of the patches' right edge (file 94.8 %)")
    val geometryCompensate get() = t("Corriger l'étirement à l'impression", "Correct the stretch when printing")
    val geometryTip get() = t("L'image envoyée est pré-déformée pour que chaque ligne tombe à sa place sur le tirage.", "The image sent is pre-warped so that each row lands where it belongs on the print.")
    val geometryCompensateOn get() = t("Étirement corrigé à l'impression.", "Stretch corrected when printing.")
    val geometryCompensateOff get() = t("Étirement non corrigé.", "Stretch left uncorrected.")
    val geometryReset get() = t("Oublier la géométrie mesurée", "Forget the measured geometry")
    val geometryCleared get() = t("Géométrie oubliée.", "Geometry forgotten.")
    val measureGeometry get() = t("Géométrie : %s", "Geometry: %s")
    val measureApply get() = t("Appliquer ces valeurs", "Apply these values")
    val measureApplied get() = t("Calibration mesurée appliquée.", "Measured calibration applied.")
    val measureFailed get() = t("Mesure impossible : %s", "Could not measure: %s")
    val measureWarped get() = t("Mire redressée de cette photo : les paliers et les patches doivent tomber bien dans leurs cases, sinon ajustez les repères.", "This photo's rectified chart: the steps and patches must sit squarely in their cells; otherwise adjust the markers.")
    val measureCornersFound get() = t("Coins détectés, vérifiez-les.", "Corners detected, check them.")
    val measurePhotos get() = t("Moyenne de %d photos.", "Average of %d photos.")
    val shareShortcut get() = t("Imprimer sur SP-3", "Print on SP-3")
    val shareShortcutLong get() = t("Imprimer sur l'instax SHARE SP-3", "Print on the instax SHARE SP-3")
    val menuSettingsExport get() = t("Exporter les réglages…", "Export settings…")
    val menuSettingsImport get() = t("Importer des réglages…", "Import settings…")
    val settingsExportFailed get() = t("Export impossible : %s", "Could not export: %s")
    val settingsImportFailed get() = t("Import impossible : %s", "Could not import: %s")
    val settingsNotOurs get() = t("ce fichier ne vient pas de cette application", "this file is not from this app")
    val settingsImportConfirm get() = t("Remplacer les calibrations des deux films, les presets et l'option de compensation par ceux du fichier ?", "Replace both films' calibrations, the presets and the compensation option with the file's?")
    val settingsImportDo get() = t("Importer", "Import")
    val settingsImported get() = t("Réglages importés (%d calibrations).", "Settings imported (%d calibrations).")
    val measureEmptyCapture get() = t("l'appareil photo n'a rien enregistré", "the camera app saved nothing")
    val calPrintSharpness get() = t("Netteté ajoutée à l'impression (le film adoucit)", "Sharpening added at print time (the film softens)")
    val ratio get() = t("Format de la photo", "Photo ratio")
    val ratioSquare get() = "1:1"
    val sharedMany get() = t("%d photos reçues et mises dans la file.", "%d photos received and queued.")
    val compensateHint get() = t(
        "Compenser le tirage applique l'inverse du modèle aux données envoyées à l'imprimante (plus clair, moins contrasté, dominante corrigée), " +
            "pour que le tirage ressemble à l'écran. Ce que le film ne peut pas rendre (extrêmes écrêtés) reste perdu. La mire s'imprime toujours sans compensation.",
        "Compensating prints applies the inverse of the model to the data sent to the printer (brighter, less contrast, corrected cast), " +
            "so the print looks like the screen. What the film cannot render (clipped extremes) stays lost. The chart always prints uncompensated.",
    )
    val compensationOn get() = t("Compensation d'impression activée.", "Print compensation on.")
    val compensationOff get() = t("Compensation d'impression désactivée.", "Print compensation off.")

    // Menu
    val menuCollage get() = t("Collage…", "Collage…")
    val collageTitle get() = t("Collage", "Collage")
    val collageHint get() = t("Touchez une case pour y mettre une photo, glissez pour la recadrer, appui long pour la vider.", "Tap a cell to put a photo in it, drag to reframe it, long press to clear it.")
    val collageGap get() = t("Espacement", "Gap")
    val collageRounded get() = t("Coins arrondis", "Rounded corners")
    val collageBackground get() = t("Fond", "Background")
    val collageUse get() = t("Utiliser ce collage", "Use this collage")
    val collageEmpty get() = t("Mettez au moins une photo.", "Put at least one photo in.")
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
