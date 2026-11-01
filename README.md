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
4. **Choisir une photo** (ou, depuis la galerie, *Partager → pls share SP-3*), puis **Modifier** pour cadrer : zoom au pincement, déplacement au doigt, rotation, photo entière ou remplissage, luminosité, contraste, saturation, noir et blanc. L'aperçu montre exactement ce qui sortira.
5. **Imprimer**. L'impression tourne dans un service en arrière-plan avec une notification de progression : on peut quitter l'appli pendant l'envoi (1,9 Mo, environ 25 s) et le tirage (~15 s).

**File d'impression** : « Ajouter à la file » met la photo en cours en attente, « Ajouter plusieurs… » choisit jusqu'à dix photos et leur applique le réglage courant, puis « Imprimer la file » enchaîne les tirages (arrêt automatique quand il n'y a plus de film). **Historique** (menu ⋮) : chaque tirage est conservé avec sa date, pour le réimprimer, le partager ou le supprimer. **Exporter / partager** (menu ⋮) enregistre l'image rendue (800 × 800) dans Photos › pls share SP-3 et ouvre le partage.

Le PIN de l'imprimante (1111 par défaut) se change dans le menu ⋮. Le bord épais du tirage est le **bas** de l'image.

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
./gradlew :core:test        # 25 tests, dont un cycle d'impression complet contre le simulateur
./gradlew :app:assembleDebug  # nécessite le SDK Android (sinon le module est ignoré automatiquement)
```

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
  Ui.kt              widgets et styles
  Strings.kt         textes FR/EN
```

## Crédits

- [James Sutton (jpwsutton)](https://github.com/jpwsutton/instax_api) pour la rétro-ingénierie initiale du protocole SP-2.
- [mbutrovich](https://github.com/mbutrovich/instax_api) pour la vérification sur SP-3 et la documentation du protocole.
- [analogbits](https://github.com/analogbits/Instax-Offline-Printer) pour la suite bureau.

Licence MIT.
