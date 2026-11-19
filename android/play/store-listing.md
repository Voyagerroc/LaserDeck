# LaserDeck PTT — Play Store Listing Metinleri

> **Taslak (2.1.0, sürüm kodu 14; `android/app/build.gradle.kts`):** Aşağıdaki Türkçe metin 2.1.0 özellik kümesi için yazıldı. Play Console'da hangi metnin yüklü olduğu bu depodan doğrulanamaz; konsola girilene kadar taslaktır. Eski 1.1.0 metni bu dosyanın geçmişindedir (git). Karakter sınırları: kısa ≤80, tam ≤4000, yenilikler ≤500.

## Uygulama adı (≤30 karakter)
```
LaserDeck PTT — Telsiz
```
(22 karakter. Alternatif: `LaserDeck PTT` — 13)

## Kısa açıklama (≤80 karakter)
```
İnternetsiz telsiz: iki telefon arası konuş, yazış, fotoğraf gönder.
```
(68 karakter)

## Tam açıklama (≤4000 karakter)
```
LaserDeck PTT, iki Android telefonu internetsiz ortamda birbirine bağlayan bir
telsiz uygulamasıdır. Şebeke çekmeyen yerlerde — dağda, bodrumda, fuar alanında —
Bluetooth üzerinden doğrudan telefondan telefona konuşabilir, arayabilir, yazışabilir
ve fotoğraf gönderebilirsiniz. Her iki telefonda da uygulama kurulu olmalıdır.

ÖZELLİKLER
• Bas-konuş: düğmeye basılı tut, bırakınca karşı taraf konuşur
• Sesli arama: karşı telefon çalar, yanıtlanınca iki yönlü konuşursunuz; telefonun
  yankı giderici ve gürültü bastırması kullanılır
• Mesajlaşma: aynı hat üzerinden teslim onaylı yazışma; hat kopukken yazılan mesaj
  kuyrukta bekler, bağlantı dönünce gider
• Fotoğraf aktarımı: seçtiğiniz fotoğraf yeniden sıkıştırılmadan, bütünlüğü
  doğrulanarak gider; alınanlar galerinizde Pictures/LaserDeck klasörüne kaydedilir
  (yaklaşık 4 MB iki dakika sürer)
• Yakınlık radarı: Bluetooth LE sinyal gücünden kaba mesafe aralığı ve
  yaklaşıyor/uzaklaşıyor eğilimi (yön vermez; Android 12+)
• GPS şerit metre: tek telefonla iki nokta arası mesafe, hata bandıyla (isteğe bağlı
  konum izni)
• MODÜL sekmesi: duyuru panosu, taşıyıcı ağ, senkron gösteri, yakınlık kasası
• Arka planda çalışır: çağrı ve hat sürerken kalıcı bildirim gösterilir
• Sunucu yok, hesap yok, reklam yok, analitik yok

NASIL KULLANILIR
1) İki telefonu Android Ayarlar > Bluetooth menüsünden eşleştirin.
2) Uygulamayı iki telefona da kurun, mikrofon ve Bluetooth izinlerini verin.
3) Bir telefonda "BEKLE"ye basın; diğerinde cihazı seçip "BAĞLAN"a basın.
4) TELSİZ sekmesinde büyük düğmeye basılı tutarak konuşun, MESAJ sekmesinden yazışın.

MENZİL
Bluetooth menzili açık alanda ve görüş hattında en iyi sonucu verir (tipik 10–30 m;
cihaza göre değişir). Duvarlar ve engeller menzili azaltır.

İZİNLER
• Mikrofon: bas-konuş sırasında ve sesli aramada ses yakalamak için.
• Bluetooth / yakındaki cihazlar: eşleşmiş telefona bağlanmak ve radar/duyuru için.
• Konum (isteğe bağlı): yalnızca GPS şerit metre modülü için; konum gönderilmez,
  diske yazılmaz.
• Bildirim: hat ve çağrı durumunu göstermek için.
Ses kaydedilmez ve hiçbir sunucuya gönderilmez. Sohbet geçmişi ve alınan
fotoğraflar telefonunuzda kalır. İnternet yalnızca "yeni sürüm var mı?" sorusu için
kullanılır.

Bu uygulama bir ön sürümdür. Kaynak kodu GitHub'da herkese açıktır:
github.com/Voyagerroc/LaserDeck
```
(Karakter sayılarını konsola yapıştırmadan önce sayın; sınırlar üstte.)

## English (EN-US) — ikincil dil olarak eklenebilir

**Title:** `LaserDeck PTT — Walkie Talkie`
**Short:** `Offline walkie-talkie: talk and text between two phones over Bluetooth.`
**Full:** TR metnin çevirisi (konsola eklerken istenirse üretilir).

## Sürüm notu (2.1.0 — "Yenilikler" alanı, ≤500 karakter)
```
GPS şerit metre: tek telefonla iki nokta arası mesafe, hata bandıyla (isteğe bağlı
konum izni). Radar ölçümü iki yönlü oldu ve ilan gücü düzeltmesi eklendi. Ayrıca
sesli arama, fotoğraf aktarımı, duyuru panosu ve yakınlık kasası bu sürüm hattında
yer alır.
```

## Grafikler (bu klasörde)
- `icon-512.png` — 512×512 uygulama simgesi (PNG, ≤1MB)
- `feature-1024x500.png` — öne çıkan görsel
- `screenshot-1.png`, `screenshot-2.png` — 1080×1920 telefon ekran görüntüleri
  (gerçek cihaz görüntüsü çekilince değiştirilmesi önerilir)

## Sınıflandırma önerileri
- Kategori: **İletişim** (Communication)
- Etiketler: walkie talkie, PTT, offline
- Reklam içerir: **Hayır** — Uygulama içi satın alma: **Yok**
