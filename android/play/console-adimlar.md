# Google Play Console — Yayın Adımları (LaserDeck PTT)

AAB hazır: `android\app\build\outputs\bundle\release\app-release.aab`
İmza anahtarı: `My Project\LaserDeckPTT-android-anahtar\` (ANAHTAR-BILGILERI.txt — YEDEKLE!)

## 0. Önemli tarih notu
31 Ağustos 2026'dan itibaren yeni uygulamalar **targetSdk 36** ister — bu uygulama
zaten targetSdk 36 ile derlendi, bu şart tamam. ✅

## 1. Uygulama oluştur
Play Console → Tüm uygulamalar → **Uygulama oluştur**
- Ad: `LaserDeck PTT — Telsiz` | Dil: Türkçe | Uygulama (oyun değil) | **Ücretsiz**
- Beyanlar: iki kutuyu onayla.

## 2. Kapalı test (kişisel hesap zorunluluğu)
Kişisel geliştirici hesaplarında üretime çıkmadan önce **kapalı testte en az 12
testerla 14 gün** koşulu vardır (PayRadar'dan bildiğin süreç).
- Test et → **Kapalı test** → Yeni sürüm oluştur.
- **Play App Signing**: "Google tarafından oluşturulan anahtar" seçeneğini onayla
  (bizim keystore "upload key" olur).
- `app-release.aab` dosyasını yükle.
- Sürüm notu: `İlk sürüm: Bluetooth üzerinden internetsiz bas-konuş.`
- Testçi listesi: 12+ e-posta (Gmail) ekle → testçilere katılım linkini gönder.

## 3. Uygulama içeriği formları (Politika → Uygulama içeriği)
- **Gizlilik politikası**: ✅ CANLI — konsola şu URL'yi gir:
  `https://laserdeck-ptt.voyagerroc.workers.dev/privacy-policy`
  (Kaynak: `play/site/` + `play/wrangler.jsonc`; güncelleme = `play\` klasöründe `npx wrangler deploy`)
- **Reklam**: Reklam içermiyor.
- **Uygulama erişimi**: "Tüm işlevler özel erişim olmadan kullanılabilir" +
  şu notu ekle: "Tam işlev için Bluetooth ile eşleştirilmiş İKİ cihaz gerekir;
  tek cihazda arayüz incelenebilir ama ses iletimi karşı cihaz ister."
- **İçerik derecelendirmesi** (anket): Kategori "Yardımcı programlar/İletişim";
  şiddet/cinsellik/kumar: hayır; **kullanıcılar arası iletişim: EVET (sesli,
  yalnızca eşleştirilmiş bilinen cihazla)**; konum paylaşımı: hayır.
- **Hedef kitle**: 18+ seç (çocuk politika yükümlülüklerinden kaçınmak için en sade yol).
- **Veri güvenliği (Data safety)**:
  - Veri TOPLANMIYOR ve PAYLAŞILMIYOR (ses cihazdan cihaza doğrudan Bluetooth ile
    gider, geliştirici hiçbir veriye erişmez, sunucu yok — Google tanımına göre
    cihaz-cihaz aktarım "toplama" sayılmaz).
  - **Konum izni (GPS şerit metre modülü)**: konum yalnızca cihaz içinde işlenir,
    aktarılmaz ve saklanmaz — Google tanımına göre "toplama" değil. Yine de izin
    manifestte göründüğü için Data safety anketinde "Konum: toplanmıyor" seçilir ve
    gizlilik politikasındaki "Konum (GPS şerit metre)" bölümü referans verilir.
  - Şifreleme sorusu: "Veri aktarılmıyor" akışı; hesap silme: hesap yok.
- **Devlet uygulaması / haber uygulaması / sağlık**: hayır.

## 4. Mağaza kaydı (Büyüme → Mağaza girişi)
- Metinler: `store-listing.md`'den kopyala.
- Simge: `icon-512.png` | Öne çıkan görsel: `feature-1024x500.png`
- Ekran görüntüleri: `screenshot-1.png`, `screenshot-2.png` (telefon sekmesi;
  gerçek cihazdan alınmış görüntülerle değiştirmek daha iyi olur: cihaz bağlıyken
  `adb exec-out screencap -p > ss1.png`).
- Kategori: İletişim | İletişim e-postası: tascierol24@gmail.com

## 5. Gönderim
Tüm formlar yeşil olunca: Kapalı test sürümünü **incelemeye gönder**.
Yeni uygulama incelemesi genelde 1–7 gün sürer.

## 6. 14 gün sonra üretim
Kapalı testte 12 testçi 14 gün "katılmış" (opt-in) kaldıktan sonra:
**Üretime yükselt** başvurusu aç → aynı AAB üretime kopyalanır → üretim incelemesi.

## Konsol tuzağı hatırlatması (PayRadar deneyiminden)
"Next/İleri" düğmesi kilitliyse sağ üst ⋮ menüsünden **Save/Kaydet** dene.

## Sonraki sürümler
`versionCode`'u artır (2, 3, ...) → `gradlew :app:bundleRelease` → aynı kanala yükle.
