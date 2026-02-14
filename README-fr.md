# <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/src/monocleschat/ic_launcher-playstore.png" width="30"> monocles chat

🇩🇪… [Deutsche Version der Readme hier verfügbar.](README-de.md) / 🇬🇧🇺🇸… [English Readme version available here](README.md) / [Italiano qui](README-it.md) / [Przeczytaj po polsku](README-pl.md) / [Türkçe sürüm burada](README-tr.md)

monocles chat est un fork de blabber.im et [Conversations] (https://github.com/siacs/Conversations).
Les changements visent à améliorer la convivialité et à faciliter la transition depuis les chats préinstallés et d'autres chats répandus. Voici quelques captures d'écran :

<img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/00.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/01.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/02.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/03.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/04.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/05.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/06.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/07.png" width="200" />

## Télécharger
monocles chat est disponible dans F-Droid
<a href="https://f-droid.org/app/de.monocles.chat"><img src="https://f-droid.org/badge/get-it-on-fr.png" alt="Now on F-Droid" height="100"></a>
<a href="https://play.google.com/store/apps/details?id=eu.monocles.chat"><img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/art/GetItOnGooglePlay_Badge_Web_color_French.png" alt="Now on Google Play" height="100"></a>

Alternativement, les APKs de la release et de la beta-release sont disponibles via codeberg : [Releases](https://codeberg.org/Arne/monocles_chat/releases/latest) 

#### monocles chat nightly et beta

Les APK nightly ou beta-release sont disponibles via codeberg : [Releases](https://codeberg.org/Arne/monocles_chat/releases/nightly) 

## Réseaux sociaux
Suivez-nous sur <a rel="me" href="https://monocles.social/@monocles">monocles social</a>

Il existe également des sales XMPP anglophones et germanophones qui se concentrent sur le support et le développement de monocles chat.

Si vous êtes intéressé par le développement du chat, voici un MUC pour vous (en anglais et en allemand) :

Développement-Chat :  [development@conference.monocles.de](https://monocles.chat/)     


Il existe également un Support-MUC où vous pouvez poser des questions et obtenir de l'aide pour les problèmes que vous pouvez rencontrer, voir ci-dessous pour plus de détails.


## Comment puis-je aider à la traduction ?
Vous pouvez améliorer ou créer des traductions sur <a href="https://translate.codeberg.org/projects/monocles_chat/">Codeberg Translate</a>. Merci beaucoup.


## Aidez-moi ! J'ai rencontré des problèmes !
La façon la plus simple d'obtenir de l'aide est de rejoindre notre support-MUC (en anglais et en allemand).

Lien d'invitation à la discussion de soutien : [support@conference.monocles.eu]

Si nous ne pouvons pas résoudre votre problème, vous pouvez ouvrir une question [ici](https://codeberg.org/Arne/monocles_chat/issues), en détaillant votre problème, comment le reproduire et en fournissant des journaux. Voir les instructions ci-dessous sur la façon de créer des fichiers journaux.



### Comment créer des journaux de débogage ? (adb)

#### GNU/Linux, OSX et d'autres systèmes de type Unix :

1. Installez d'abord **A**ndroid **D**ebugging **B**ridge, si ce n'est pas déjà fait. 
    ###### Debian et ses dérivés comme Linux Mint / Ubuntu
    ```
    sudo apt-get update
    sudo apt-get update adb
    # Debian Jessie or older:
    # sudo apt-get install android-tools-adb
    ```
    ###### openSUSE 42.2 et 42.3
    ```
    sudo zypper ref
    sudo zypper install android-tools
    ```
    ###### openSUSE Tumbleweed
    ici, vous devez ajouter le dépôt suivant (par exemple via Yast) :
    http://download.opensuse.org/repositories/hardware/openSUSE_Tumbleweed/
    
    Vous avez également la possibilité d'utiliser le programme d'installation en un clic. 
    https://software.opensuse.org/package/android-tools
    ###### autres systèmes
    installer adb en utilisant une méthode appropriée à votre système
    
2. Maintenant, ouvrez un terminal dans un répertoire de votre choix, ou naviguez vers le répertoire en utilisant `cd`.

3. Suivez les étapes [6] à [10] des instructions Windows.

4. Commencez à sortir votre journal dans un fichier sur votre ordinateur. Nous allons utiliser `logcat.txt`. Entrez :
    ```
    $ adb -d logcat -v time | grep -i "monocles chat" > logcat.txt
    ```

5. Suivez les autres étapes [12] et [13] des instructions Windows.


#### Windows:

1. Téléchargez les outils de la plateforme SDK de Google pour votre système d'exploitation :
    
    https://developer.android.com/studio/releases/platform-tools.html    
2. Au cas où ils n'auraient pas été inclus : Vous avez également besoin des ADB_drivers pour votre version de Microsoft Windows :
    
    https://developer.android.com/studio/run/win-usb.html
3. Extrayez l'archive zip (par exemple vers "C:\ADB\").
4. Ouvrez la ligne de commande (CMD) à l'aide du menu Démarrer :  Démarrer > Exécuter : cmd
5. Naviguez vers le répertoire dans lequel vous avez extrait le zip comme suit. Nous allons utiliser `C:\ADB\`.
    ```
    c:
    cd ADB
    ``` 
6. Sur votre smartphone, ouvrez les paramètres et recherchez "Options développeur". Si cette option n'est pas déjà présente sur votre téléphone, vous devrez le déverrouiller au préalable. Pour ce faire, allez dans `Paramètres > À propos du téléphone`, localisez le `Numéro de construction` (ou similaire) et appuyez dessus 7 fois de suite. Vous devriez maintenant voir une notification indiquant que vous êtes maintenant un développeur. Félicitations, les `Options développeur` sont maintenant disponibles dans votre menu de paramètres.
7. Dans la recherche `Options développeur`, activez le paramètre `USB-Debugging` (parfois juste appelé `Android Debugging`).
8. Connectez votre téléphone à votre ordinateur via un câble USB. Les pilotes nécessaires doivent maintenant être téléchargés et installés s'ils ne sont pas déjà présents. Sous Windows, tous les pilotes nécessaires devraient être téléchargés automatiquement si vous avez suivi l'étape [2] au préalable. Sur la plupart des systèmes GNU/Linux, aucune action supplémentaire n'est requise. 
9. Si tout a fonctionné, vous pouvez maintenant retourner à la ligne de commande et tester si votre périphérique est reconnu. Entrez `adb devices -l` ; vous devriez voir une sortie similaire à celle-ci :
    ```
    > adb devices -l
    List of devices attached
    * daemon not running. starting it now on port 5037 *
    * daemon started successfully *
    042111560169500303f4   unauthorized
    ```
10. Si votre appareil est étiqueté comme `unautorized`, vous devez d'abord accepter une invite sur votre téléphone demandant si le débogage par USB doit être autorisé. En relançant `adb devices` vous devriez maintenant voir :
    ```
    > adb devices
    List of devices attached 
    042111560169500303f4    device
    ```   
11. Commencez à sortir votre journal dans un fichier sur votre ordinateur. Nous allons utiliser `logcat.txt` dans `C:\ADB\`. Entrez simplement ce qui suit (sans `> ` dans la ligne de commande) :
    ```
    > adb -d logcat -v time | FINDSTR monocles_chat > logcat.txt
    ``` 
12. Reproduisez maintenant le problème rencontré.

13. Arrêtez la journalisation (`Ctrl+C`). Maintenant, regardez attentivement votre fichier de log et supprimez toute information personnelle et privée que vous pourriez trouver avant de me l'envoyer avec une description détaillée de votre problème et des instructions sur la façon de le reproduire. Vous pouvez utiliser le gestionnaire de problèmes de GitHub : [Issues](https://github.com/kriztan/Monocles-Messenger/issues)
