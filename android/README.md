# LaserDeck PTT — Android geliştirme ve deneme

LaserDeck iki Android telefon arasında Bluetooth Classic RFCOMM üzerinden
bas-konuş, tam çift yönlü arama, mesajlaşma ve fotoğraf aktarımı sunan
uygulamanın kaynak kodudur. GPS şerit metre ve yakınlık radarı ayrı
modüllerdir. Ürün kapsamı ve fiziksel sınırlar [kök README'de](../README.md)
anlatılır; burada geliştirici kurulumu ve doğrulama adımları bulunur.

## Derleme ortamı

- JDK 17; `java -version` çıktısını kontrol edin.
- Android SDK Platform 36. Android Studio SDK Manager ile kurun.
- Gradle wrapper, AGP ve Kotlin sürümleri depoda tanımlıdır:
  `gradle/wrapper/gradle-wrapper.properties` ve `build.gradle.kts`.
- SDK yolunu yalnızca yerel `local.properties` (`sdk.dir=...`) veya
  `ANDROID_HOME` ile tanımlayın. İlk senkron bağımlılıkları indirir.

Bu dizinden Windows PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Linux/macOS:

```sh
chmod +x gradlew
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

JVM testleri cihaz gerektirmez. Debug build release imzalama anahtarı
gerektirmez. Test raporu `app/build/reports/tests/testDebugUnitTest/`, debug
APK `app/build/outputs/apk/debug/` altında bulunur. `assembleRelease`
çıktısının imzalı olduğu varsayılmamalıdır: release imzalama yalnızca yerel
`keystore.properties` yüklendiğinde yapılandırılır. Anahtarlar repoya girmez.

## Kaynak haritası

Yollar `app/src/main/java/com/voyagerroc/laserdeck/ptt/` altındadır.

| Alan | Başlangıç dosyası | Kapsam |
| --- | --- | --- |
| Çerçeveleme | `protocol/LinkFrame.kt` | Taşıyıcıdan bağımsız başlık, yük ve CRC32 |
| Taşıyıcı | `transport/FrameTransport.kt`, `BluetoothRfcommTransport.kt` | RFCOMM bağlantısı |
| Codec | `audio/VoiceCodec.kt`, `audio/AmrCodec.kt` | PCM/AMR-NB kodlama sınırı |
| Bas-konuş | `PttController.kt`, `audio/AudioEngine.kt` | Yarı çift yönlü PTT akışı |
| Arama | `call/CallController.kt`, `audio/CallAudioSession.kt` | Tam çift yönlü arama ve cihaz ses işleme |
| Kayıp ses | `audio/PacketLossConcealer.kt` | Arama oynatımında kısa kayıplar için perde tekrarı/sönme, uzun kayıplar için konfor gürültüsü |
| Mesaj/fotoğraf | `chat/Messenger.kt`, `files/FileTransferManager.kt` | Teslimat ve aktarım akışları |
| Ön plan | `PttService.kt` | Bağlantı ve bildirim yaşam döngüsü |
| GPS | `modules/GpsTape.kt`, `geo/Geodesy.kt` | Tek telefonla A–B ölçümü ve belirsizlik bandı |

`PacketLossConcealer` arama sesinde kullanılır; bu, her modülde PLC veya
belirli cihazlarda ölçülmüş ses kalitesi garantisi değildir. JVM testleri
protokol, metin, mesajlaşma ve jeodezi alanlarını kapsar; radyo, mikrofon,
yankı iptali ve gerçek mesafe ölçümleri için cihaz testi gerekir.

## İki telefonla kabul denemesi

1. Test edilen commit, APK SHA-256, Android sürümü ve iki cihaz modelini
   kaydedin; kişisel mesajları veya konumu test kaydına koymayın.
2. Telefonları sistem Bluetooth ayarlarında eşleştirin ve debug APK'yı
   ikisine kurun. İstenen izinleri ilgili özellikte değerlendirin.
3. Bir telefonda bekleme/sunucu, diğerinde bağlantı akışını izleyin; PTT'yi
   iki yönde deneyin. Arama, mesaj teslimatı ve örnek fotoğrafı ayrıca deneyin.
4. İzin reddi/geri alma, bağlantı kopması, uygulamanın arka plana alınması
   ve yeniden bağlanma sonuçlarını ayrı kaydedin.
5. Mesafe ve ses değerlendirmesine ortamı ve belirsizliği ekleyin. CI/JVM
   başarısı fiziksel telefon denemesinin yerine geçmez.

Bu bir kabul planıdır; adımların listelenmesi cihaz testinin yapıldığını
göstermez. iOS desteği yoktur. RFCOMM, AMR ve donanım ses işleme sınırları
korunur; Opus/Codec2, BLE taşıyıcısı ve uygulama katmanı şifreleme ayrı
gelecek çalışmalar olarak değerlendirilmelidir.

Dağıtım öncesi [sürüm kanıtı kontrol listesi](../docs/RELEASE.md) ve
[güvenlik bildirim yolu](../SECURITY.md) kullanılmalıdır.
