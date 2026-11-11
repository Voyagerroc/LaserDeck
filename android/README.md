# 📱 LaserDeck PTT — Telefon-Telefon Bas-Konuş (Bluetooth MVP)

İki Android telefon arasında, **internetsiz** ortamda, açık alanda ~30 m hedefiyle
yarı çift yönlü (half-duplex) **ses iletimi** yapan bas-konuş (walkie-talkie) uygulaması.

Taşıyıcı **Bluetooth Classic (RFCOMM)**. Çerçeve katmanı (preamble/sync/CRC32)
**taşıyıcıdan bağımsızdır** — `FrameTransport` arayüzünü uygulayan herhangi bir
taşıyıcı (ör. BLE L2CAP) aynı uygulamaya eklenebilir.

## Mimari

```
Mikrofon ─> AudioEngine ─> VoiceCodec (AMR-NB 12.2k) ─> PttController
                                                            │  LinkFrame
                                                            ▼  (0xAA 0x7E | hdr | payload | CRC32)
                                                     FrameTransport  ◄── arayüz
                                                      └─ BluetoothRfcommTransport   ✅
```

| Katman | Dosya | Not |
|---|---|---|
| Çerçeve protokolü | `protocol/LinkFrame.kt` | Sync + CRC32, gürültüye dayanıklı parser (JVM testli) |
| Taşıyıcı arayüzü | `transport/FrameTransport.kt` | Ses motoru taşıyıcıyı bilmez |
| Bluetooth RFCOMM | `transport/BluetoothRfcommTransport.kt` | Eşleşmiş cihaza bağlanır; keşif/konum izni gerekmez |
| Codec soyutlaması | `audio/VoiceCodec.kt` | id 0=PCM, 1=AMR-NB, 2=Codec2 (TODO), 3=Opus (TODO) |
| AMR-NB codec | `audio/AmrNbCodec.kt` | MediaCodec ile yerleşik, native bağımlılık yok |
| Ses G/Ç | `audio/AudioEngine.kt` | AudioRecord/AudioTrack, 20 ms çerçeve |
| PTT durum makinesi | `PttController.kt` | AUDIO_START/FRAME/END, yarı çift yönlü kilit |
| Ön plan servisi | `PttService.kt` | Bağlantı + PTT arka planda yaşar; kalıcı bildirim, "Kapat" eylemi |
| Ekran | `MainActivity.kt` | Servise bind olur; cihaz seç → Bekle/Bağlan/Kes → basılı tut konuş |
| GPS şerit metre | `modules/GpsTape.kt`, `geo/Geodesy.kt` | Tek telefon; A/B noktalarında 8 s fix ortalaması, Vincenty (WGS84), ± bandı; konum izni yalnızca bu modülde (JVM testli) |

## Kurulum ve Deneme

1. Klasörü **Android Studio** ile aç (`android/`); Gradle senkronu bağımlılıkları indirir
   (AGP 8.5.2, Kotlin 1.9.24, tek bağımlılık: coroutines).
2. İki telefonu önce **Ayarlar → Bluetooth**'tan birbirleriyle eşleştir.
3. Uygulamayı iki telefona da kur; mikrofon + Bluetooth izinlerini ver.
4. A telefonunda **Sunucu (Bekle)** → B telefonunda listeden A'yı seçip **Bağlan**.
5. Büyük düğmeye **basılı tutarak** konuş; karşı taraf konuşurken düğme kilitlenir.

Protokol birim testleri (cihaz gerekmez): `gradlew :app:testDebugUnitTest`

## Bilinen sınırlar (MVP)

- Jitter tamponu AudioTrack'in kendi tamponudur (~160 ms); kayıp çerçeve gizleme (PLC) yok.
- iOS yok (RFCOMM iOS'ta üçüncü taraf uygulamalara kapalı; iOS için yol BLE L2CAP'tir).
- 30 m menzil **açık alan / görüş hattı** içindir; duvar ve vücut gölgelemesi menzili düşürür.

## Yol haritası

1. ~~**ForegroundService + otomatik yeniden bağlanma**~~ ✅ `PttService` + supervisor
   döngüsü: sunucu kopunca hemen tekrar dinler, istemci üstel geri çekilmeyle
   (2s→15s) aynı cihaza yeniden bağlanır; kalıcı bildirimden "Kapat" ile durdurulur.
2. **Opus (libopus JNI)** — 6–24 kbps uyarlanabilir kalite; sinyal zayıfladıkça bitrate düşür.
3. **Codec2 (JNI)** — 0.7–3.2 kbps; optik dongle hattının hedef codec'i.
4. **BLE Coded PHY taşıyıcısı** — destekleyen cihazlarda 50–100+ m menzil.
5. **Uygulama katmanı şifreleme ve kimlik doğrulama** — X25519 anahtar anlaşması +
   AES-GCM, ilk bağlantıda eş anahtarı sabitleme (TOFU).
