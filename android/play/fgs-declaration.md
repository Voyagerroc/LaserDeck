# Ön plan servisi (Foreground Service) beyanı — Play Console

## Durum: video ZORUNLU (28 Ağu 2026'da deneyerek doğrulandı)

Play Console, ön plan servisi beyanı için YouTube tanıtım videosu istiyor ve
video linki olmadan **Save düğmesi açılmıyor**. Denenenler:

| Denenen | Sonuç |
|---|---|
| `microphone` tipi → "Background audio input" | Video zorunlu |
| `connectedDevice` tipi → "Continuous data transfer to an external device" | Video zorunlu |
| `connectedDevice` tipi → "Other" (açıklama alanı var) | Açıklama dolu olsa da video zorunlu |

Yani ön plan servisi kaldığı sürece video kaçınılmaz. Videosuz yayın için tek yol
servisi tamamen kaldırmak (arka planda hat kopar).

## Videoda gösterilecekler (~1 dakika yeter)

1. İki telefonda uygulama açık, biri "Sunucu (Bekle)", diğeri "Bağlan" → BAĞLI
2. Bas-konuş düğmesiyle konuşma, karşı telefondan sesin duyulması
3. Uygulamayı arka plana al / ekranı kapat → kalıcı bildirimin göründüğü
4. Bağlantının kopmadığı, karşı taraftan gelen sesin hâlâ duyulduğu
5. Bildirimden "Kapat" ile hattın kapatılabildiği

YouTube'a **liste dışı (unlisted)** olarak yükle, linki konsola yapıştır.

Not: Uygulama henüz gerçek cihazda hiç çalıştırılmadı. Bu video, ilk saha
testiyle aynı iş — ikisini birlikte yap.

## Konsola yapıştırılacak açıklama metni (hazır)

> LaserDeck PTT is an offline walkie-talkie. Two phones pair over Bluetooth and
> exchange live voice directly, with no server and no internet. The foreground
> service holds the RFCOMM socket to the paired phone open and plays incoming
> audio as it arrives.
>
> Why it must start immediately and cannot be paused or restarted: the connection
> is a live voice link between two people. If the service is paused or killed, the
> socket drops, the other person's speech is lost mid-sentence, and the user must
> manually reconnect. Reconnecting takes several seconds, which defeats the purpose
> of a walkie-talkie in places with no network coverage (mountains, basements,
> disaster response). The persistent notification shows the live connection state
> and offers a Close action, so the user always sees and controls it.
>
> The microphone is used only while the user physically holds the push-to-talk
> button with the app on screen, so the app does not request
> FOREGROUND_SERVICE_MICROPHONE.

## Seçilecek kutu

`connectedDevice` → **Continuous data transfer to an external device**
(uygulama v1.0.1'den itibaren `connectedDevice` tipini kullanıyor; `microphone`
izni kaldırıldı çünkü mikrofon yalnızca uygulama ön plandayken, PTT düğmesi
basılıyken kullanılıyor.)
