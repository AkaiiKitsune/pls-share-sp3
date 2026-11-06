# pls share SP-3

Imprimer sur une **Fujifilm Instax SHARE SP-3** (et SP-2) **sans l'appli officielle**.

Deux morceaux :

- **`app/`** : une appli Android qui se connecte toute seule au Wi-Fi de l'imprimante, le garde pour elle, et imprime une photo de la galerie (ou reçue via « Partager »).
- **`core/`** : le protocole de l'imprimante en Kotlin pur, un client, un **simulateur d'imprimante** et une ligne de commande `instax-print` pour imprimer depuis un ordinateur (Linux, macOS, Windows).

> **État du projet** : le protocole est celui documenté et vérifié par [mbutrovich/instax_api](https://github.com/mbutrovich/instax_api) (fork de [jpwsutton/instax_api](https://github.com/jpwsutton/instax_api)). **Première impression réelle confirmée** avec l'appli Android sur une SP-3 (firmware 01.02) depuis un Pixel 7 Pro. Observé sur cette SP-3 : après chaque association Wi-Fi (et après un tirage), l'imprimante met **60 à 90 secondes** avant d'accepter une connexion TCP (connexions ignorées puis refusées, puis ça passe). L'appli et la CLI réessaient automatiquement pendant 2 minutes en affichant le nombre de tentatives.

## Pourquoi l'appli officielle « ne se connecte pas »

La SP-3 crée son propre réseau Wi-Fi `INSTAX-XXXXXXXX` (ouvert, sans mot de passe) et écoute en TCP sur `192.168.0.251:8080`. Ce réseau n'a **pas d'accès Internet**. Depuis Android 10, le téléphone considère un tel réseau comme « cassé » : il bascule sur la 4G/5G, ou revient sur la box, et l'appli officielle ne trouve plus l'imprimante.

L'appli de ce dépôt contourne ça proprement : elle **demande le réseau à Android** via `WifiNetworkSpecifier` (Android affiche une petite fenêtre « choisir le réseau INSTAX-… »), Android maintient ce Wi-Fi tant que l'appli le demande, et **chaque socket est lié explicitement à ce réseau** (`Network.socketFactory`). Les données mobiles peuvent rester allumées, rien à bricoler dans les réglages Wi-Fi.

## Alternatives existantes (si vous ne voulez pas compiler une appli)

| Projet | Plateformes | SP-3 | Remarques |
|---|---|---|---|
| [analogbits/Instax-Offline-Printer](https://github.com/analogbits/Instax-Offline-Printer) | macOS (appli native), Windows et Linux (scripts Python + interface), navigateur | ✅ depuis la v1.5.3 (« Legacy Instax Printer ») | Le plus complet : interface graphique, plugin Lightroom/Darktable, simulateur intégré. Pas d'appli Android/iOS. |
| [mbutrovich/instax_api](https://github.com/mbutrovich/instax_api) | Ligne de commande Python | ✅ testé sur SP-3 | `instax-print photo.jpg`. Le doc `docs/protocol.md` est la référence du protocole. |
| [jpwsutton/instax_api](https://github.com/jpwsutton/instax_api) | Python | partiel | Projet d'origine, archivé. |
| Appli officielle *instax SHARE* | Android / iOS | ✅ | La seule sur les stores. |
| Appareils Fujifilm X compatibles | — | ✅ | Impression directe depuis le boîtier, sans téléphone. |

Il n'existait **aucune appli Android alternative** : c'est le trou que ce dépôt comble.

## Appli Android

### Installer

1. Aller dans l'onglet **Actions** du dépôt, ouvrir le dernier run vert, télécharger l'artefact `pls-share-sp3-apk` (ou l'APK attaché à une *release* `v*`).
2. Installer l'APK sur le téléphone (autoriser les sources inconnues). Android 10 ou plus récent.

Ou compiler soi-même : ouvrir le dossier dans Android Studio, ou `./gradlew :app:assembleDebug` avec `ANDROID_HOME` défini.

### Utiliser

1. Allumer l'imprimante (voyant Wi-Fi allumé).
2. La première fois, suivre le **guide de connexion** (bouton sur l'écran d'accueil ou menu ⋮) : il explique comment enregistrer le Wi-Fi `INSTAX-XXXXXXXX` avec une adresse IP fixe dans les réglages Android. Ça ne se fait qu'une fois.
3. Rejoindre le Wi-Fi de l'imprimante. L'appli détecte toute seule qu'elle est joignable et affiche **Connectée** avec le modèle, le nombre de films et la batterie. Après l'allumage, l'imprimante met 1 à 2 minutes à répondre : l'appli attend.
4. **Choisir une photo** (ou, depuis la galerie, *Partager → pls share SP-3*), puis **Modifier** (voir *L'éditeur* ci-dessous). L'accueil montre le tirage dans son cadre instax, et l'aperçu correspond exactement à ce qui sortira.
5. **Imprimer**. L'impression tourne dans un service en arrière-plan avec une notification de progression : on peut quitter l'appli pendant l'envoi (1,9 Mo, environ 25 s) et le tirage (~15 s).

**File d'impression** : « Ajouter à la file » met la photo en cours en attente, « Ajouter plusieurs… » choisit jusqu'à dix photos et leur applique le réglage courant, puis « Imprimer la file » enchaîne les tirages (arrêt automatique quand il n'y a plus de film). **Historique** (menu ⋮) : chaque tirage est conservé avec sa date, pour le réimprimer, le partager ou le supprimer. **Exporter / partager** (menu ⋮) enregistre l'image rendue (800 × 800) dans Photos › pls share SP-3 et ouvre le partage.

Le PIN de l'imprimante (1111 par défaut) se change dans le menu ⋮. Le bord épais du tirage est le **bas** de l'image.

### L'éditeur

Photo fixe en haut, réglages qui défilent dessous, deux onglets.

**Réglages** : Original (maintenir) et Aperçu film ; Histogramme RVB et Zones écrêtées (hachures clignotantes, rouge = brûlé, bleu = bouché) ; Tourner, Remplir, Entière, N&B, Réinitialiser ; zoom au pincement, rotation continue à deux doigts, déplacement au doigt avec bords, centre et angles droits aimantés ; dix filtres (Original, Vif, Chaud, Froid, Délavé, Rétro, Sépia, Mono, Noir, Pop) ; **Mes presets** (enregistrer / appliquer / supprimer un réglage complet) ; **Auto** (niveaux automatiques) ; Lumière (exposition, contraste, hautes lumières, ombres, blancs, noirs), Couleur (température, teinte, saturation), Détails (netteté, vignettage), Cadre (bordure, bandeau en bas façon Polaroid, coins arrondis, couleur). Un tap sur le nom d'un curseur le remet à zéro ; un appui long sur un outil explique ce qu'il fait.

**Textes** : légende, date, heure ou date et heure (date EXIF de la photo si disponible), déplaçables au doigt et redimensionnables au pincement, sept polices système, grille de positions avec marge réglable, taille en pixels du tirage, texte clair ou sombre, style ombre / contour / bulle.

### Aperçu film, calibration et compensation

Le bouton **Aperçu film** (dans l'éditeur ; l'aperçu de l'accueil suit le même réglage) montre le tirage tel que le film devrait le rendre, à l'écran seulement. C'est un **modèle paramétrique** (`Film.kt`) : exposition, gamma des demi-tons, seuils d'entrée de noir et de blanc (latitude), niveaux de noir et de blanc du tirage, chaleur des noirs, saturation, dominantes, assombrissement optionnel des bleus. Les valeurs par défaut ont été **mesurées sur un tirage réel de la mire** (SP-3, film instax SQUARE couleur, photographié à côté du téléphone, patches normalisés par le blanc du papier) : les entrées sous 20 % impriment toutes le même noir et celles au-dessus de 88 % le même blanc ; le noir du tirage est loin du noir pur et son blanc reste sous le blanc papier ; les demi-tons sortent plus clairs que l'entrée (gris 50 % → 67 % du blanc papier, gamma ≈ 0,6) ; les noirs sont chauds (brun rouge), les gris moyens presque neutres ; les couleurs sont nettement désaturées, le cyan vire au bleu et le bleu sort plus clair, pas plus sombre. Ces mesures contredisent plusieurs observations publiées (tirages « plus sombres et plus contrastés », « bleus assombris ») : une photo au flash d'un tirage lève un peu les noirs et désature, donc le niveau de noir et la saturation sont tempérés, et le reste se règle à l'œil.

Un sélecteur **Film : Couleur / Monochrome** sur l'accueil choisit le film chargé. En monochrome, tout est rendu en luminance (aperçu, éditeur, données envoyées), et ce qui ne s'applique pas est masqué : section Couleur, bouton N&B, filtres purement colorés, cadres colorés, curseurs de saturation et d'assombrissement des bleus de la calibration. Chaque film a **sa propre calibration** (mémorisée séparément) et **sa propre mire** : couleur (gris, 16 patches, dégradé) ou monochrome (gris grossiers et fins, blocs de détail dans les ombres et les hautes lumières, dégradé). Les valeurs de départ du monochrome ne sont pas encore mesurées : elles partent de la courbe du film couleur avec des noirs neutres et plus profonds, comme le décrivent les tests publiés.

Chaque imprimante et chaque lot de film diffèrent : menu ⋮ → **Calibrer l'aperçu film** affiche une **mire** (gris par paliers, 16 patches dont des teintes chair, dégradé continu) à imprimer une fois, puis des curseurs pour ajuster le modèle jusqu'à ce que la mire simulée à l'écran ressemble au tirage, à la lumière du jour. Les valeurs sont mémorisées.

**Mesure automatique** : **Mesurer depuis une photo du tirage** (dans la calibration) prend ou choisit une photo du tirage de la mire, posé à plat avec son cadre blanc entièrement visible. On touche les quatre coins de la zone image (haut gauche, haut droit, bas droit, bas gauche ; les repères se déplacent au doigt), puis **Mesurer** : la photo est redressée par homographie, chaque patch est moyenné en son centre, et tout est rapporté au **blanc du cadre** mesuré juste autour de l'image, avec un gradient entre côtés opposés pour corriger une lumière inégale. Ce blanc de référence annule la luminosité et la couleur de l'éclairage (jour ou flash) et l'exposition du téléphone. Le modèle est ensuite ajusté (`ChartReader.kt`) : niveaux de noir et de blanc sur les paliers extrêmes, seuils d'écrêtage là où les paliers cessent d'être plats, gamma par la médiane des demi-tons, chaleur des noirs sur le palier le plus sombre, dominantes sur les gris moyens, saturation par le rapport des chromas mesurées aux chromas prédites, assombrissement des bleus sur le patch bleu. L'écran affiche l'erreur RMS du modèle sur les gris et deux rangées de paliers (mesurés / simulés) avant d'**appliquer**.

**Correction par teinte** : les réglages globaux (saturation, dominantes) ne suffisent pas, le film ne traite pas toutes les couleurs pareil. Sur le tirage mesuré, le vert pur sort à 30 % de sa luminosité attendue, le cyan et le bleu virent l'un vers l'autre, le jaune tourne vers l'orange et le magenta perd 40 % de saturation. Le modèle porte donc trois tables à six secteurs (rouge, jaune, vert, cyan, bleu, magenta, interpolées par angle de teinte) : rotation de teinte en degrés, gain de saturation, gain de luminosité proportionnel à la saturation (les gris ne bougent pas). Elles sont ajustées sur les 16 patches par passes successives (résidu de teinte, de saturation et de luminosité entre mesure et prédiction, réparti sur les deux secteurs voisins de la teinte source, pondéré par la chroma), la part commune des gains étant repliée dans la saturation globale. L'inverse (compensation) résout la teinte exactement secteur par secteur et divise saturation et luminosité ; ce que le film ne peut pas rendre est borné. Sur la photo lumière du jour, l'erreur sur les patches passe de 12 % à 7 %. Les tables ne sont jamais réglées à la main : la calibration les affiche et propose de les neutraliser. Deux photos du même tirage (lumière du jour et flash, dans `app/src/test/resources`) donnent des modèles proches, mais le flash lève un peu les noirs et les demi-tons par son reflet : préférez la lumière du jour indirecte.

Menu ⋮ → **Compenser le tirage** applique l'**inverse du modèle** aux données envoyées à l'imprimante (plus clair, moins contrasté, dominante corrigée, bleus relevés) pour que le tirage ressemble à l'écran. Ce que le film ne peut pas rendre (extrêmes écrêtés, bleus très saturés) reste hors de portée ; l'aperçu film montre alors ce qui restera perdu. La mire s'imprime toujours sans compensation.

### Pourquoi une adresse IP fixe

Sur certains téléphones récents (constaté avec un Pixel 7 Pro), la SP-3 se met en erreur et s'éteint pendant l'échange DHCP, que ce soit via une appli ou via les réglages Wi-Fi d'Android. Une adresse fixe supprime cet échange. L'imprimante est toujours à `192.168.0.251`, même si sa plage DHCP change à chaque redémarrage. Les réglages : **Paramètres IP = Statique**, IP `192.168.0.5`, préfixe `24`, passerelle `192.168.0.251`, DNS `192.168.0.251`, Confidentialité = **MAC de l'appareil**, puis « rester connecté » malgré l'absence d'Internet. Android n'autorise une IP fixe que sur un réseau enregistré par l'utilisateur, pas sur un réseau demandé par une appli, d'où ce passage par les réglages.

### Mode debug

Dans le menu ⋮, **Mode debug** affiche : l'adresse de l'imprimante, un **mode test** qui envoie l'image sans lancer le tirage (aucun film consommé), la demande de réseau INSTAX via l'appli (DHCP, instable sur certains téléphones), et le **journal** de chaque étape et chaque trame échangée, avec « Copier » et « Partager » pour signaler un problème.

### Ce que l'appli ne fait pas (encore)

- Pas de cadres ni de collage. Une photo, un tirage.
- SP-1 non supportée (format JPEG propriétaire, différent).
- iOS : impossible avec cette approche (pas de sockets TCP bruts liés à un réseau depuis une appli tierce sans NetworkExtension). Sur iPhone, utilisez un ordinateur avec `instax-print`.

### Signature (à faire une fois, par le propriétaire du dépôt)

La CI signe l'APK avec une clé fournie par les *secrets* GitHub Actions. Sans secret, elle signe avec une clé jetable et la mise à jour intégrée ne pourra pas s'installer par-dessus (il faudra désinstaller / réinstaller à chaque fois).

1. Générer une clé (une seule fois, à garder précieusement) :
   ```bash
   keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias pls-share-sp3 -keyalg RSA -keysize 2048 -validity 36500
   ```
2. Dans le dépôt GitHub → Settings → Secrets and variables → Actions, créer :
   - `APP_KEYSTORE_BASE64` : le contenu de `base64 -w0 release.jks`
   - `APP_KEYSTORE_PASSWORD` : le mot de passe choisi
   - `APP_KEY_ALIAS` : `pls-share-sp3`
   - `APP_KEY_PASSWORD` : le mot de passe de la clé (le même, avec PKCS12)
3. Relancer un build : l'étape « Restore the signing key » confirme « Signing with the repository's release key ». Désinstaller l'ancienne version du téléphone, installer celle-ci : les suivantes se feront depuis l'appli.

## Ligne de commande (ordinateur)

Prérequis : Java 17+. Rejoindre d'abord le Wi-Fi `INSTAX-XXXXXXXX` de l'imprimante.

```bash
./gradlew :core:installDist
core/build/install/instax-print/bin/instax-print --info          # modèle, batterie, films restants
core/build/install/instax-print/bin/instax-print photo.jpg       # imprime, recadré au carré
core/build/install/instax-print/bin/instax-print --fit contain photo.jpg   # photo entière, bandes blanches
core/build/install/instax-print/bin/instax-print --preview out.png photo.jpg  # aperçu sans imprimer
core/build/install/instax-print/bin/instax-print --pin 1234 photo.jpg
core/build/install/instax-print/bin/instax-print --off           # éteint l'imprimante
```

Un zip autonome (`core/build/distributions/instax-print.zip`) est aussi produit par la CI.

### Simulateur

Pour tester sans gaspiller de film :

```bash
core/build/install/instax-print/bin/instax-print --simulate --save-dir /tmp/tirages
# dans un autre terminal
core/build/install/instax-print/bin/instax-print --host 127.0.0.1 photo.jpg
```

Chaque « tirage » est enregistré en PNG dans `/tmp/tirages`. L'appli Android peut aussi viser le simulateur en changeant l'adresse de l'imprimante (le téléphone et l'ordinateur doivent être sur le même réseau).

## Le protocole en deux mots

Tout est détaillé dans [docs/protocol.md de mbutrovich/instax_api](https://github.com/mbutrovich/instax_api/blob/main/docs/protocol.md). Résumé :

- TCP `192.168.0.251:8080`, une connexion par étape. Trames `0x24 opcode longueur session PIN … somme 0D0A` (commande) / `0x2A …` (réponse), big-endian, somme de contrôle = NOT de la somme 16 bits.
- Séquence : `LOCK` → `MODEL_NAME`, `PRINT_COUNT`, `SPECIFICATIONS`, `RESET` → `TRANSFER_START` + 32 × `SEND_CHUNK` (60 000 octets) + `TRANSFER_END` → `PRINT_START` → `PRINT_STATUS` toutes les secondes jusqu'à `0x00` → `UNLOCK`.
- Image : 800 × 800 RGB brut (1 920 000 octets), lignes du **bas vers le haut**, chaque ligne = tous les R, puis tous les G, puis tous les B.
- Le PIN (1111 par défaut) est dans chaque trame ; `0xF7` = mauvais PIN, `0xA0` = un autre appareil tient le verrou (expire après 60 s d'inactivité).

Le module `core` implémente tout ça (`instax/Protocol.kt`, `InstaxPrinter.kt`, `RawImage.kt`) avec des tests qui rejouent les trames capturées sur une vraie imprimante par le projet d'origine.

## Développement

```bash
./gradlew :core:test          # tests du protocole, dont un cycle d'impression complet contre le simulateur
./gradlew :app:assembleDebug  # nécessite le SDK Android (sinon le module est ignoré automatiquement)
./gradlew :app:testDebugUnitTest --tests ScreenshotTest   # captures d'écran de chaque écran dans app/build/screenshots
```

Les captures sont rendues par Robolectric en mode graphique natif (vrais pixels du framework, sans émulateur) sur un écran 412 × 915 dp en mode sombre. Robolectric télécharge lui-même son image Android ; si ce téléchargement est limité, placez les jars dans un dossier et donnez-le via `ROBOLECTRIC_DEPS=/chemin`.

Structure :

```
core/src/main/kotlin/instax/
  Protocol.kt       trames, opcodes, codes retour
  InstaxPrinter.kt  client : info(), print(), ping(), powerOff()
  RawImage.kt       encodage ARGB → format brut de l'imprimante
  Simulator.kt      fausse imprimante (tests, essais)
  DesktopImages.kt  redimensionnement java.awt pour la CLI
  cli/Main.kt       instax-print
app/src/main/kotlin/io/github/akaiikitsune/plsshare/
  MainActivity.kt    accueil, éditeur, guide, menu, impression
  PrinterWatcher.kt  détection automatique de l'imprimante sur le Wi-Fi courant
  PrinterNetwork.kt  sockets liés au Wi-Fi courant (ou demandé en debug)
  PhotoEditorView.kt zoom / déplacement au doigt
  Photos.kt          chargement EXIF-correct, rendu du tirage (cadrage, couleurs)
  Film.kt            modèle de film (simulation / compensation), mires
  ChartReader.kt     lecture d'une photo de la mire et ajustement du modèle
  CornerPickView.kt  pointage des coins de la mire
  CollageView.kt     collages
  Ui.kt              widgets et styles
  Strings.kt         textes FR/EN
```

## Crédits

- [James Sutton (jpwsutton)](https://github.com/jpwsutton/instax_api) pour la rétro-ingénierie initiale du protocole SP-2.
- [mbutrovich](https://github.com/mbutrovich/instax_api) pour la vérification sur SP-3 et la documentation du protocole.
- [analogbits](https://github.com/analogbits/Instax-Offline-Printer) pour la suite bureau.

Licence MIT.
