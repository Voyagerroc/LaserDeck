# LaserDeck PTT sitesi: dağıtım

Site `android/play/site/` klasörüdür; Cloudflare Worker (assets-only), ad `laserdeck-ptt`,
adres `https://laserdeck-ptt.voyagerroc.workers.dev`. Yapılandırma: `android/play/wrangler.jsonc`.

## Tuzak: APK depoda yok

`*.apk` `.gitignore`'dadır. Bu yüzden temiz bir klonun `site/` klasöründe APK bulunmaz.
`wrangler deploy` klasörün tamamını yeni sürüm olarak yayınlar; APK klasörde yoksa canlıdan
KALDIRILIR. Sonuç: `laserdeck-ptt-2.1.0.apk` 404 olur, uygulama içi güncelleme ve
`/test` sayfasındaki yedek indirme kırılır.

Dağıtımdan önce APK(lar) `site/` içine konur ve SHA-256 denetlenir:

```sh
cp <imzalı-apk> android/play/site/laserdeck-ptt-2.1.0.apk
cp <imzalı-apk> android/play/site/laserdeck-ptt.apk     # sürümsüz ad, _headers'ta no-store
sha256sum android/play/site/*.apk   # beklenen (2.1.0): dfbf29c785c5a059d53d16450f66e0dd83d3f6572c7661f355fd2b06d0b74e39
```

## Dağıtım

```sh
cd android/play
python surum-uret.py "Yenilikler metni"   # yalnızca sürüm değiştiyse; surum.json'u üretir
npx wrangler deploy
```

## Dağıtımdan sonra

- `curl -s https://laserdeck-ptt.voyagerroc.workers.dev/surum.json` → `versionCode` ve `apk` alanları beklenen değer.
- APK adresi 200 döner ve SHA-256 yukarıdakiyle aynıdır.
- `/`, `/test`, `/privacy-policy` 200 döner.

## Uygulamanın okuduğu tek adres

`UpdateChecker.kt` yalnızca `https://laserdeck-ptt.voyagerroc.workers.dev/surum.json` adresine GET atar;
`versionCode` (sayı), `versionName`, `apk` (aynı kökenle başlamalı), `notes` alanlarını okur.
Yanıt 200 değilse, 8 KB'tan büyükse ya da `apk` farklı bir kökenle başlıyorsa güncelleme sessizce yok sayılır.
