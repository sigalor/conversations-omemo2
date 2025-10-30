# <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/src/monocleschat/ic_launcher-playstore.png" width="30"> monocles chat

🇩🇪… [Deutsche Version der Readme hier verfügbar.](README.md) /  [Francais ici](README-fr.md) / [Italiano qui](README-it.md) / [Przeczytaj po polsku](README-pl.md) / [Türkçe sürüm burada](README-tr.md)

monocles chat modern ve güvenli bir Android XMPP istemcisidir. blabber.im ve [Conversations](https://github.com/siacs/Conversations) tabanlıdır ancak birçok değişiklik ve ek özellik içerir.
Değişiklikler kullanılabilirliği iyileştirmeyi ve önceden yüklenmiş ve yaygın diğer sohbet uygulamalarından geçişi kolaylaştırmayı amaçlar. İşte bazı ekran görüntüleri:

<img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/00.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/01.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/02.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/03.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/04.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/05.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/06.png" width="200" /> <img src="https://codeberg.org/Arne/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/07.png" width="200" />

Ekranlar Pigeonalley tarafından tasarlanmıştır (https://pigeonalley.com)

### İndirin

<a href="https://f-droid.org/app/de.monocles.chat"><img src="https://f-droid.org/badge/get-it-on-tr.png" alt="F-Droid'de mevcut" height="100"></a>
<a href="https://play.google.com/store/apps/details?id=eu.monocles.chat"><img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/art/GetItOnGooglePlay_Badge_Web_color_Turkish.png" alt="Google Play'de mevcut" height="100"></a>

### Ön Ayarlar

monocles chat, blabber.im'e kıyasla farklı ön ayarlara sahiptir:

* sohbette web bağlantılarının önizlemelerini göstermez
* sohbette konum önizlemelerini göstermez
* tüm ekleri otomatik olarak indirmez

### OTR

monocles chat OTR şifrelemesini destekler! Kullanımı kolay olmasa da OTR'nin bazı avantajları vardır:
<a href="https://en.wikipedia.org/wiki/Off-the-Record_Messaging#Implementation">https://en.wikipedia.org/wiki/Off-the-Record_Messaging#Implementation</a>

## İndirme
monocles chat F-Droid'den yükleme için mevcuttur
Alternatif olarak beta sürüm APK'ları codeberg üzerinden edinilebilir: [Sürümler](https://codeberg.org/Arne/monocles_chat/releases) 

#### monocles chat gecelik sürüm ve beta

Gecelik veya beta sürüm APK'ları codeberg üzerinden edinilebilir: [Sürümler](https://codeberg.org/Arne/monocles_chat/releases) 

## Sosyal Medya
Bizi <a rel="me" href="https://monocles.social/@monocles">monocles social</a>'da takip edin

Ayrıca monocles chat'in desteği ve geliştirilmesine odaklanan İngilizce ve Almanca konuşulan XMPP odaları da mevcuttur.

Sohbet uygulamasının geliştirilmesiyle ilgileniyorsanız, işte sizin için bir MUC (İngilizce ve Almanca konuşulur):

Geliştirme Sohbeti: [development@conference.monocles.de](https://monocles.chat/)     


Ayrıca soru sorabileceğiniz ve karşılaşabileceğiniz sorunlarla ilgili yardım alabileceğiniz bir Destek MUC'u da bulunmaktadır, ayrıntılar için aşağıya bakınız.


## Çevirileri nasıl destekleyebilirim?
Kod birleştirme talebi oluşturabilir, yeni diller ekleyebilir ve mevcut çevirileri düzenleyebilirsiniz.



## Yardım edin! Sorunlarla karşılaştım!
Yardım almanın en kolay yolu destek MUC'umuza katılmaktır (hem İngilizce hem de Almanca).  

Destek Sohbeti davet bağlantısı: [support@conference.monocles.de](https://monocles.chat/)     

Sorununuzu orada çözemiyorsak, [buradan](https://codeberg.org/Arne/monocles_chat/issues) bir sorun açabilir, sorununuzu, nasıl yeniden uygulanabileceğini detaylandırabilir ve kayıt dosyaları sağlayabilirsiniz. Kayıt dosyalarının nasıl oluşturulacağına dair talimatlar için aşağıya bakınız.



### Hata ayıklama kayıtları nasıl oluşturulur? (adb)

#### GNU/Linux, OSX ve diğer Unix benzeri sistemler:

1. Öncelikle **A**ndroid **D**ebugging **B**ridge'i kurun (henüz yoksa).
    ###### Debian ve Ubuntu / Linux Mint gibi türevleri
    ```
    sudo apt-get update
    sudo apt-get install adb
    # Debian Jessie veya daha eski:
    # sudo apt-get install android-tools-adb
    ```
    ###### openSUSE 42.2 ve 42.3
    ```
    sudo zypper ref
    sudo zypper install android-tools
    ```
    ###### openSUSE Tumbleweed
    burada aşağıdaki depoyu eklemeniz gerekir (örn. Yast üzerinden):
    http://download.opensuse.org/repositories/hardware/openSUSE_Tumbleweed/
    
    alternatif olarak `1 Tıklamalı yükleyici` kullanma seçeneğiniz var
    https://software.opensuse.org/package/android-tools
    ###### diğer sistemler
    adb'yi sisteminiz için uygun bir yöntem kullanarak kurun
    
2. Şimdi seçtiğiniz bir dizinde bir terminal açın veya `cd` kullanarak dizine gidin.

3. Windows talimatlarının [6] ile [10] adımlarını izleyin.

4. Kayıt çıktınızı bilgisayarınızdaki bir dosyaya kaydetmeye başlayın. `logcat.txt` kullanacağız. Girin:
    ```
    $ adb -d logcat -v time | grep -i "monocles chat" > logcat.txt
    ```

5. Windows talimatlarının kalan [12] ve [13] adımlarını izleyin.


#### Windows:

1. İşletim sisteminiz için Google'ın SDK platform araçlarını indirin:
    
    https://developer.android.com/studio/releases/platform-tools.html    
2. Dahil edilmemişlerse: Microsoft Windows sürümünüz için ADB sürücülerine de ihtiyacınız vardır:
    
    https://developer.android.com/studio/run/win-usb.html
3. Zip arşivini çıkarın (örn. `C:\ADB\` klasörüne)
4. Başlat menüsünü kullanarak komut satırını (CMD) açın: Başlat > Çalıştır: cmd
5. Zip'i çıkardığınız dizine aşağıdaki şekilde gidin. `C:\ADB\` kullanacağız
    ```
    c:
    cd ADB
    ``` 
6. Akıllı telefonunuzda ayarları açın ve `Geliştirici Seçenekleri` öğesini arayın. Bu seçenek telefonunuzda henüz mevcut değilse, önce kilidi açmanız gerekecektir. Bunu yapmak için `Ayarlar > Telefon hakkında` bölümüne gidin, orada `Yapı numarası`nı (veya benzerini) bulun ve art arda 7 kez dokunun. Artık geliştirici olduğunuza dair bir bildirim görmelisiniz. Tebrikler, `Geliştirici Seçenekleri` artık ayarlar menünüzde mevcut.
7. `Geliştirici Seçenekleri` içinde `USB Hata Ayıklama` ayarını arayın ve etkinleştirin (bazen sadece `Android Hata Ayıklama` olarak adlandırılır).
8. Telefonunuzu USB kablosu ile bilgisayarınıza bağlayın. Gerekli sürücüler henüz mevcut değilse şimdi indirilmeli ve kurulmalıdır. Windows'ta önceden [2] adımını izlediyseniz gerekli tüm sürücüler otomatik olarak indirilmelidir. Çoğu GNU/Linux sisteminde ek işlem gerekmez.
9. Her şey yolunda gittiyse, artık komut satırına dönebilir ve cihazınızın tanınıp tanınmadığını test edebilirsiniz. `adb devices -l` girin; şuna benzer bir çıktı görmelisiniz:
    ```
    > adb devices -l
    List of devices attached
    * daemon not running. starting it now on port 5037 *
    * daemon started successfully *
    042111560169500303f4   unauthorized
    ```
10. Cihazınız `unauthorized` olarak etiketlenmişse, önce telefonunuzda USB üzerinden hata ayıklamaya izin verilip verilmeyeceğini soran bir istemi kabul etmelisiniz. `adb devices` komutunu tekrar çalıştırdığınızda şunu görmelisiniz:
    ```
    > adb devices
    List of devices attached 
    042111560169500303f4    device
    ```   
11. Kayıt çıktınızı bilgisayarınızdaki bir dosyaya kaydetmeye başlayın. `C:\ADB\` içinde `logcat.txt` kullanacağız. Komut satırına aşağıdakini girin (`> ` olmadan):
    ```
    > adb -d logcat -v time | FINDSTR monocles_chat > logcat.txt
    ``` 
12. Şimdi karşılaşılan sorunu yeniden uygulayın.

13. Kaydetmeyi durdurun (`Ctrl+C`). Şimdi kayıt dosyanıza yakından bakın ve göndermeden önce bulabileceğiniz kişisel ve özel bilgileri kaldırın. Bunu, sorununuzun ayrıntılı açıklaması ve nasıl yeniden üretileceğine dair talimatlarla birlikte bana gönderin. GitHub'ın sorun izleyicisini kullanabilirsiniz: [Sorunlar](https://github.com/kriztan/Monocles-Messenger/issues)

