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

**Mesure automatique** : **Mesurer une photo du tirage** (dans la calibration) prend ou choisit une photo du tirage de la mire, posé à plat avec son cadre blanc entièrement visible. L'appli **trouve la mire seule**, sur sa grille de couleurs (`ChartReader.detectFromGrid`) : la photo réduite est découpée en taches uniformes (pixels dont le voisinage est plat, agrégés par couleur ; les fonds texturés se fragmentent, les patches ressortent entiers, le blanc du tirage forme une tache géante écartée par sa taille). Le patch rouge et au moins deux de ses voisins vert, magenta et jaune (quatre teintes qu'aucun autre élément de la mire n'a) ancrent une première transformation : chaque voisin donne le pas des colonnes, dont le pas des lignes se déduit (un quart de tour, trois quarts de la longueur). Les seize patches retrouvés près de leur place prédite affinent ensuite l'homographie par moindres carrés, les coins de l'image s'en déduisent par la géométrie connue de la mire, et la rampe de gris doit aller du sombre au clair, sinon rien n'est proposé. Testé sur sept photos du même tirage (bambou, tissu, stickers, bois incliné, lumière du jour, flash) : toutes alignées, gris à moins de 8 % de la lecture à la main (15 % au flash, qui lève les noirs). En film monochrome (pas de patches), on touche les quatre coins du carré de contenu. Les repères affichés ne sont pas les coins de l'image mais les **centres des quatre patches de coin** (rouge, cyan, gris, bleu ciel), dont le cadre pointillé de l'image se déduit : on les contrôle à la loupe en faisant glisser un repère, et un repère relâché sur un patch **s'aimante à son centre** (la tache uniforme sous le doigt). Pointer les patches plutôt que les bords est délibéré : en mesurant les tirages, la barre de gris commence entre 0 et 3 % de l'image au lieu des 4 % du fichier, et le dégradé finit à 98 % au lieu de 96 : **l'imprimante rogne et étire l'image de quelques pour cent**, de façon non uniforme, si bien que le bord visible de l'image n'est pas le bord du fichier et que le cadre déduit peut légèrement dépasser l'image visible. Le panneau de pointage se replie une fois les repères posés ; le bandeau des photos le rouvre. Sous le bandeau, la mire redressée montre la photo en cours pendant le pointage, et la **moyenne pixel à pixel de toutes les photos** une fois le panneau replié. En film monochrome (pas de patches), on pointe les quatre coins du carré de contenu. Puis **Mesurer** : la photo est redressée par homographie, chaque patch est moyenné en son centre, et tout est rapporté au **blanc du cadre** mesuré juste autour de l'image, avec un gradient entre côtés opposés pour corriger une lumière inégale. Ce blanc de référence annule la luminosité et la couleur de l'éclairage (jour ou flash) et l'exposition du téléphone. Le modèle est ensuite ajusté (`ChartReader.kt`) : niveaux de noir et de blanc sur les paliers extrêmes, seuils d'écrêtage là où les paliers cessent d'être plats, gamma par la médiane des demi-tons, chaleur des noirs sur le palier le plus sombre, dominantes sur les gris moyens, saturation par le rapport des chromas mesurées aux chromas prédites, assombrissement des bleus sur le patch bleu. L'écran affiche la mire redressée (pour juger les coins), l'erreur RMS du modèle sur les gris et deux rangées de paliers (mesurés / simulés) avant d'**appliquer**. On peut charger **plusieurs photos d'un coup** : elles s'alignent en arrière-plan et s'affichent dans un bandeau (celles sans coins sont grisées) ; on touche chacune pour vérifier ou corriger ses coins, appui long pour la retirer, et **Mesurer** combine les lectures de toutes celles qui ont leurs coins avant l'ajustement (la **médiane** de chaque valeur à partir de trois photos, pour qu'un reflet, une poussière ou une ombre sur une seule photo ne compte pas ; la moyenne à deux) ; « Recommencer » efface tout. Le **dégradé continu** de la mire est lu aussi (48 bandes, sur la moitié centrale de sa hauteur) et donne la **forme** de la courbe de ton (fin des plateaux de noir et de blanc à une fraction de palier, gamma sur les demi-tons) ; les niveaux de noir et de blanc restent ceux des paliers, et la forme du dégradé n'est retenue que s'il concorde avec eux à 0,1 près et qu'elle reproduit les paliers au moins aussi bien que la forme tirée des paliers (l'imprimante traite les tons continus un peu autrement que les aplats : sur les tirages de contrôle le dégradé marque un palier vers 50 %) (sur les anciennes mires le cadre en cachait le bas, et le traitement de l'appareil photo peut traiter différemment les deux). Attention à ce traitement : sur cinq photos du même tirage, le noir se lit 0,01, 0,11, 0,13, 0,13 et 0,29 du blanc selon le fond (HDR et tonalité locale du téléphone), ce que la médiane absorbe mais qui limite la précision absolue des niveaux : fond neutre et moyen, lumière du jour, plusieurs photos.

**Matrice du film** : avant les tables de teinte, une matrice 3×3 de diaphonie (`FilmModel.crosstalk`, lignes de somme un : les gris ne bougent pas) appliquée après la saturation et la balance des gris et avant les courbes de ton, inversée à la compensation. Elle est ajustée sur les 16 patches : pour chaque patch, ce qui entre dans la matrice (la source à travers les termes globaux) et ce qui doit en sortir (la couleur mesurée ramenée en niveaux par la courbe de ton de chaque canal telle que les paliers de gris la montrent sur la même photo ; un canal au blanc du film est écarté), moindres carrés par ligne avec une crête vers l'identité sur les termes hors diagonale, ligne ramenée à somme un pour ignorer un gain commun. Les quatre combinaisons (rien, matrice, teintes, matrice puis teintes) sont ajustées et celle qui rend le mieux les patches est gardée ; sur les photos de test, l'erreur sur les patches passe de 10–13 % à 7,6–11,7 % avant les tables de teinte. La saturation globale, ajustée d'abord, prend une partie de la désaturation que le mélange provoque, donc les termes sortent plus doux que la vérité sur une mire synthétique, mais le rendu des patches est retrouvé à moins de 3 %.

**Correction par teinte** : les réglages globaux (saturation, dominantes) ne suffisent pas, le film ne traite pas toutes les couleurs pareil. Sur le tirage mesuré, le vert pur sort à 30 % de sa luminosité attendue, le cyan et le bleu virent l'un vers l'autre, le jaune tourne vers l'orange et le magenta perd 40 % de saturation. Le modèle porte donc trois tables à six secteurs (rouge, jaune, vert, cyan, bleu, magenta, interpolées par angle de teinte) : rotation de teinte en degrés, gain de saturation, gain de luminosité proportionnel à la saturation (les gris ne bougent pas). Elles sont ajustées sur les 16 patches par passes successives (résidu de teinte, de saturation et de luminosité entre mesure et prédiction, réparti sur les deux secteurs voisins de la teinte source, pondéré par la chroma), la part commune des gains étant repliée dans la saturation globale. L'inverse (compensation) résout la teinte exactement secteur par secteur et divise saturation et luminosité ; ce que le film ne peut pas rendre est borné. Sur la photo lumière du jour, l'erreur sur les patches passe de 12 % à 7 %. Les tables ne sont jamais réglées à la main : la calibration les affiche et propose de les neutraliser. Deux photos du même tirage (lumière du jour et flash, dans `app/src/test/resources`) donnent des modèles proches, mais le flash lève un peu les noirs et les demi-tons par son reflet : préférez la lumière du jour indirecte.

**Géométrie du tirage** : les mêmes photos de mire donnent, dans le repère de la grille des patches, la position réelle des lignes de référence du fichier (haut et gauche des gris à 4 %, bas du dégradé à 96 %, droite des patches à 94,8 %) : sur toutes les photos, elles tombent à ½ % près là où le fichier les met. Une première version mesurait un « étirement vertical de 3,6 % » : c'était un artefact du lecteur, qui plaçait le centre de la dernière rangée de patches 1,9 % trop bas (le dégradé recouvre le bas de cette rangée sur la mire ; le lecteur modélise maintenant ce qui est visible, `ChartReader.GRADIENT_TOP`), et l'ajustement de l'homographie absorbait l'erreur en fausse échelle et faux trapèze. Vérifié sur une mire synthétique : les coins détectés sont exacts au pixel.

**Alignement par la couronne** (`ChartReader.detectFromRing`), pour la mire monochrome qui n'a pas de grille de couleurs, et en secours pour la mire couleur : sur une ligne ou une colonne sur trois de la photo, la couronne se traverse comme une série d'au moins cinq creux sombres à espacement régulier (3 à 24 px) ; sur chaque ligne, la paire de séries (une par côté) de même pas, séparées de 84 pas entre leurs extrémités intérieures et bordées à l'intérieur par une marge de papier blanc, donne deux points du rectangle intérieur de la couronne (la ligne à 8 % du fichier) ; les quatre bords sont ajustés par droites avec rejet des points aberrants (au moins huit points cohérents à 2,5 px, et la moitié des points), leur écartement vérifié, et le carré de contenu en découle. Les numéros interrompent quelques lignes, qui sont ignorées ; le cadre peut cacher jusqu'à trois lignes par côté. Sur les cinq photos du tirage de contrôle, la couronne retrouve les coins de la grille à 4 px près ; une mire mono synthétique tournée de 0, 2 et −3° est retrouvée à 3 px, avec sa carte et sa couronne.

La vraie déformation se lit sur les **bords de la carte** (`ChartReader.measureCard`) : la carte instax SQUARE est un vrai rectangle de 72 × 86 mm, donc là où ses bords tombent dans le repère de la mire dit comment l'imprimante a tourné, mis à l'échelle et déformé le fichier sur le papier. Chaque bord est cherché sur neuf lignes perpendiculaires, comme le premier endroit au-delà du bord du fichier où le papier s'arrête (1,5 % qui diffèrent du papier juste à l'intérieur), une droite est ajustée en écartant les points aberrants, les coins sont les intersections ; les fonds proches du papier (acier brossé) font échouer la mesure. Entre les photos qui réussissent, on garde la carte entière d'une seule photo, celle dont la rotation est la médiane, après avoir écarté les cartes invraisemblables (trapèze au-delà de 3 %, rotation à plus de 0,4° de la médiane) : un mélange de coordonnées de plusieurs photos n'est pas une carte. Le rapport liste la rotation lue photo par photo. Sur le tirage de contrôle : fichier imprimé sur environ 63,8 × 64,7 mm, **tourné de 0,5° dans le sens horaire**, trapèze inférieur à 1 %. `PrinterGeometry` en fait une homographie fichier → millimètres de la carte (`toCard`), composée avec la carte linéaire des lignes de référence.

Ce que le cadre cache ne se mesure pas avec des marges de 4 % : dès que le cadre coupe davantage (sur un tirage de contrôle, la barre de gris touchait le bord et les repères de coin avaient disparu) on ne voit plus rien. La mire fait donc **les deux à la fois** : une **couronne de cadrage** sur le bord du fichier, rectangles emboîtés tous les 1 % numérotés sur leurs quatre côtés et ligne épaisse sur le bord lui-même, et le contenu couleur ou monochrome **centré dans un carré à 78 %** du fichier. Sur le tirage, le **premier rectangle entier** de chaque côté donne le pourcentage caché. L'appli le lit elle-même quand on mesure une photo (`ChartReader.readRing`) : sur cinq lignes perpendiculaires à chaque côté, à l'écart des numéros, elle compte les lignes de la couronne encore visibles en partant du contenu (un creux du profil d'au moins 10 % sous le papier des deux côtés, à des écarts d'environ 1 % l'un de l'autre ; les lignes fines ne font qu'un ou deux pixels sur la photo et le papier entre deux lignes serrées est grisé par leur halo, d'où un critère local plutôt qu'un seuil), prend la médiane, et en déduit le caché à un demi pour cent près (n lignes vues → premier rectangle entier 9 − n → caché 8,5 − n %). Quand la carte est connue, la lecture s'affine : le tirage étant un peu tourné, la ligne suivante apparaît à un bout du côté et pas à l'autre ; l'endroit où elle s'interrompt (balayage de tout le côté, meilleure coupure en deux séries, au moins trois positions de chaque côté, sens de la coupure cohérent avec la pente du bord de carte) est un point du bord de la fenêtre, dont la direction est celle de la carte, et le caché au tiers du côté en découle au dixième de pour cent. La couronne et les bords de carte sont lus sur la photo rechargée à 3200 px de côté (une à la fois, les repères mis à l'échelle), le double de ce que l'éditeur garde. Sur les cinq photos du tirage de contrôle : haut 3 %, bas 0 à 0,5 %, gauche 1,5 %, droite 0,5 % en lecture grossière, et en lecture fine haut 2,7 à 3,1 %, gauche 1,6 à 1,9 %, bas 0,7 à 0,8 %, droite 0,5 à 0,6 %. Le résultat de la mesure montre la photo sélectionnée recadrée sur la carte, avec le contenu (bleu), les bords de carte trouvés (magenta) et la fenêtre déduite (jaune), et dit sur combien de photos la carte a été trouvée ; sans elle, un message explique quoi photographier (carte entière, fond sombre et uni). Les quatre curseurs « Caché en haut / bas / gauche / droite » (pas de 0,5 %) restent là pour corriger à la main, et un curseur « Rotation du tirage » (±5°, dixièmes, positif = sens horaire) montre l'angle mesuré et permet de le remplacer à la main (`rotationSet`) ; une nouvelle mesure de la carte le remet sur la valeur mesurée, et sans carte il tourne seul le tirage autour de son centre.

Avec la carte et les coupes connues, les coupes situent la **fenêtre du cadre** sur la carte (environ 62 mm) et l'impression **place l'image entière, droite et carrée dans cette fenêtre** (`PrinterGeometry.prewarp` : chaque pixel du fichier reçoit le pixel de l'image qui doit tomber là où l'imprimante le mettra, ré-échantillonnage bicubique Catmull-Rom à 16 points, le bord est répété au-delà de l'image) : plus de rotation, plus de bande cachée, les aperçus ne montrent plus de bandes sombres. La fenêtre est prise carrée (moyenne de sa largeur et de sa hauteur mesurées) pour ne pas déformer l'image, et le placement est désactivé si la mesure est invraisemblable (fenêtre hors de 56–68 mm, rotation de plus de 5°). Sans la carte, l'impression ne corrige que la carte linéaire des lignes de référence et les aperçus montrent les coupes en bandes sombres. Le lecteur travaille dans les coordonnées du carré de contenu, donc la détection par la grille et la lecture ne changent pas ; le blanc du papier est échantillonné au-delà de la couronne, et les anciennes mires sans couronne restent lisibles.

Menu ⋮ → **Compenser le tirage** applique l'**inverse du modèle** aux données envoyées à l'imprimante (plus clair, moins contrasté, dominante corrigée, bleus relevés) pour que le tirage ressemble à l'écran. L'inverse étire les tons, et en 8 bits le tirage sauterait des niveaux (bandes dans un ciel, un fond flou) : la compensation est calculée en **niveaux fractionnaires** et **tramée** (bruit à gradient entrelacé) avant l'envoi, de sorte que deux pixels voisins alternent entre les deux niveaux encadrants et que le film en fasse la moyenne. Les aperçus utilisent une table 3D (33³, trilinéaire) du même modèle, recalculée à chaque changement, pour rester fluides. Ce que le film ne peut pas rendre (extrêmes écrêtés, bleus très saturés) reste hors de portée ; l'aperçu film montre alors ce qui restera perdu. La mire s'imprime toujours sans compensation.

### Pourquoi une adresse IP fixe

Sur certains téléphones récents (constaté avec un Pixel 7 Pro), la SP-3 se met en erreur et s'éteint pendant l'échange DHCP, que ce soit via une appli ou via les réglages Wi-Fi d'Android. Une adresse fixe supprime cet échange. L'imprimante est toujours à `192.168.0.251`, même si sa plage DHCP change à chaque redémarrage. Les réglages : **Paramètres IP = Statique**, IP `192.168.0.5`, préfixe `24`, passerelle `192.168.0.251`, DNS `192.168.0.251`, Confidentialité = **MAC de l'appareil**, puis « rester connecté » malgré l'absence d'Internet. Android n'autorise une IP fixe que sur un réseau enregistré par l'utilisateur, pas sur un réseau demandé par une appli, d'où ce passage par les réglages.

### Mode debug

Menu ⋮ → **Exporter les réglages…** partage un fichier JSON avec les calibrations des deux films (matrice et tables de teinte comprises), les presets et l'option de compensation ; **Importer des réglages…** les recharge depuis un tel fichier (changement de téléphone, comparaison entre deux imprimantes). Depuis la galerie, l'appli est aussi un **raccourci de partage direct** (« Imprimer sur SP-3 ») en tête de la feuille de partage. La boîte de mise à jour montre les **changements** de la nightly (les messages des commits depuis la précédente).

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
  MainActivity.kt    accueil, éditeur, calibration, collage, menu, impression
  MeasureScreen.kt   écran de mesure de la mire (photos, repères, lectures, ajustement)
  GuideScreen.kt     guide de connexion
  HistoryScreen.kt   historique des tirages
  PrinterWatcher.kt  détection automatique de l'imprimante sur le Wi-Fi courant
  PrinterNetwork.kt  sockets liés au Wi-Fi courant (ou demandé en debug)
  PhotoEditorView.kt zoom / déplacement au doigt
  Photos.kt          chargement EXIF-correct, rendu du tirage (cadrage, couleurs)
  Film.kt            modèle de film (simulation / compensation), mires
  ChartReader.kt     lecture d'une photo de la mire et ajustement du modèle
  CornerPickView.kt  pointage des coins de la mire (loupe)
  CaptureProvider.kt fichiers privés servis à l'appareil photo et au partage
  Geometry.kt        géométrie de l'imprimante (étirement, zone cachée, pré-déformation)
  CollageView.kt     collages
  Ui.kt              widgets et styles
  Strings.kt         textes FR/EN
```

## Crédits

- [James Sutton (jpwsutton)](https://github.com/jpwsutton/instax_api) pour la rétro-ingénierie initiale du protocole SP-2.
- [mbutrovich](https://github.com/mbutrovich/instax_api) pour la vérification sur SP-3 et la documentation du protocole.
- [analogbits](https://github.com/analogbits/Instax-Offline-Printer) pour la suite bureau.

Licence MIT.
