<div align="center">

# 📟 LaserDeck PTT — Offline Telefon-Telefon İletişim

**İnternetsiz • Sunucusuz • Hesapsız • Nokta-Noktaya Bluetooth İletişim Uygulaması**

[![CI](https://github.com/Voyagerroc/LaserDeck/actions/workflows/ci.yml/badge.svg)](https://github.com/Voyagerroc/LaserDeck/actions/workflows/ci.yml)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg?logo=android)](https://developer.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg?logo=kotlin)](https://kotlinlang.org/)
[![Carrier](https://img.shields.io/badge/Carrier-Bluetooth%20Classic%20RFCOMM-blue.svg?logo=bluetooth)](https://en.wikipedia.org/wiki/Bluetooth)
[![GitLab Mirror](https://img.shields.io/badge/GitLab-Mirror-orange?logo=gitlab)](https://gitlab.com/Voyagerroc/LaserDeck)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

</div>

---

<!-- voyagerroc-brand:start -->
## Voyagerroc Technologies

[LaserDeck PTT product overview / ürün sayfası](https://voyagerroctechnologies.com/urunler/laserdeck/) · [Voyagerroc Technologies — applications, SaaS & Si workflows](https://voyagerroctechnologies.com/)

Product scope, available access and limitations are documented on the product page. / Ürün kapsamı, erişim durumu ve sınırlar ürün sayfasında açıklanır.
<!-- voyagerroc-brand:end -->

## 📖 Genel Bakış

LaserDeck PTT, iki Android telefon arasında **internet, sunucu ve hesap olmadan**
doğrudan Bluetooth üzerinden çalışan yerel bir iletişim uygulamasıdır: bas-konuş,
tam çift yönlü sesli arama, mesajlaşma, fotoğraf aktarımı ve yakınlık radarı.

**[⬇ APK indir](https://laserdeck-ptt.voyagerroc.workers.dev)** · Android 8.0+ · ~1 MB · hesap yok, sunucu yok

| Özellik | Durum |
|---|---|
| Bas-konuş (half-duplex) sesli iletişim | ✅ |
| Tam çift yönlü sesli arama (çaldırma, cevaplama, ahize/hoparlör, donanım AEC + gürültü bastırma) | ✅ |
| Teslimat onaylı mesajlaşma, çevrimdışı kuyruk, cihaz içi geçmiş | ✅ |
| Fotoğraf aktarımı — orijinal baytlar, SHA-256 doğrulamalı, yeniden sıkıştırma yok | ✅ |
| Yakınlık radarı — iki telefon arası mesafe tahmini | ✅ (Android 12+) |
| GPS şerit metre — tek telefonla A→B mesafesi, ± bandıyla | ✅ |
| Uygulama içi güncelleme denetimi | ✅ |

**Taşıyıcı:** Bluetooth Classic RFCOMM. İnternet, Wi-Fi, hücresel veri, sunucu ve
hesap kullanılmaz. Uygulamanın yaptığı tek ağ çağrısı sürüm denetimidir ve hiçbir
kullanıcı verisi taşımaz. Konum yalnızca GPS şerit metre modülü açıkken okunur ve
cihazdan çıkmaz.

**Dürüst sınırlar.** Menzil görüş hattında 10–30 m. 4 MB'lık bir fotoğraf yaklaşık
iki dakika sürer — bu yazılımın değil radyonun tavanıdır. Donanım yankı iptali cihaza
bağlıdır ve yoksa arama ekranı bunu açıkça söyler. Radar mesafe ölçer, yön ölçmez:
tek antenle yön üretilemez, hedef nokta değil halka olarak çizilir. GPS şerit metre
tüketici alıcısının fiziğine bağlıdır: tek fix ±3–5 m; iki noktanın farkı daha iyidir
ama "hatasız" değildir — her sonuç ± bandıyla yazılır.

---

## 🏗️ Mimari

Tüm modüller taşıyıcıdan bağımsız `LinkFrame` çerçeve katmanı üzerinde çalışır;
taşıyıcı `FrameTransport` arayüzünün arkasındadır.

```mermaid
flowchart TD
    subgraph Input["Girdi ve Sensör Katmanı"]
        Mic["Mikrofon (AEC + Gürültü Bastırma)"]
        Cam["Kamera (Fotoğraf)"]
        GPS["GNSS / GPS Alıcısı"]
        UI["Klavye & Dokunmatik Ekran"]
    end

    subgraph Modules["Uygulama Modülleri"]
        PTT["Bas-Konuş (PttController)"]
        Call["Tam Çift Yönlü Arama (CallController)"]
        Msg["Teslimat Onaylı Mesajlaşma (Messenger)"]
        Files["Orijinal Fotoğraf Aktarımı (FileTransferManager)"]
        Radar["Yakınlık Radarı (RadarScope)"]
        Tape["GPS Şerit Metre (GpsTape)"]
    end

    subgraph Protocol["Protokol ve Taşıma Katmanı"]
        LF["LinkFrame (0xAA 0x7E | Başlık | Yük | CRC32)"]
        FT["FrameTransport Arayüzü"]
        BT["BluetoothRfcommTransport (RFCOMM)"]
    end

    Input --> Modules
    Modules --> LF
    LF --> FT
    FT --> BT
```

Ayrıntılı katman tablosu, kurulum ve yol haritası: [`android/README.md`](android/README.md)

## 🔨 Derleme

```bash
cd android
gradlew :app:testDebugUnitTest   # protokol birim testleri (cihaz gerekmez)
gradlew :app:assembleRelease     # imzalı APK (keystore.properties gerekir)
```

## 🚀 Dağıtım

Sürüm sitesi Cloudflare Workers üzerinde: `android/play/` altında mağaza paketi,
gizlilik politikası ve `surum.json` sürüm bildirimi bulunur.

---

## 🔗 Depo Bağlantıları

- **GitHub:** [Voyagerroc/LaserDeck](https://github.com/Voyagerroc/LaserDeck)
- **GitLab:** [Voyagerroc/LaserDeck](https://gitlab.com/Voyagerroc/LaserDeck)

---

## 📜 Lisans

**MIT License** © 2026 Erol Tasci — araştırma, eğitim ve açık mühendislik için serbesttir.
