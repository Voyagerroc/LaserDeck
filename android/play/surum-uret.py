# -*- coding: utf-8 -*-
"""
site/surum.json dosyasini build.gradle.kts'ten uretir.

Neden betik: surum numarasi uc yerde birden geciyor (gradle, APK dosya adi,
surum bildirimi). Elle tutulan bir kopya er ya da gec suruklenir ve uygulama
ya hic guncelleme gormez ya da olmayan bir dosyaya yonlendirir.

Kullanim (play/ klasorunde):
    python surum-uret.py "Yenilikler metni"
Ardindan: npx wrangler deploy
"""
import io
import json
import os
import re
import sys

BASE = "https://laserdeck-ptt.voyagerroc.workers.dev"
HERE = os.path.dirname(os.path.abspath(__file__))
GRADLE = os.path.join(HERE, "..", "app", "build.gradle.kts")
SITE = os.path.join(HERE, "site")


def oku_surum():
    src = io.open(GRADLE, encoding="utf-8").read()
    kod = re.search(r"versionCode\s*=\s*(\d+)", src)
    ad = re.search(r'versionName\s*=\s*"([^"]+)"', src)
    if not kod or not ad:
        raise SystemExit("build.gradle.kts icinde surum bulunamadi")
    return int(kod.group(1)), ad.group(1)


def main():
    kod, ad = oku_surum()
    apk_adi = "laserdeck-ptt-%s.apk" % ad
    apk_yolu = os.path.join(SITE, apk_adi)
    if not os.path.exists(apk_yolu):
        raise SystemExit(
            "APK site klasorunde yok: %s\n"
            "Once derleyip kopyala:\n"
            "  gradlew :app:assembleRelease\n"
            "  cp app/build/outputs/apk/release/app-release.apk play/site/%s"
            % (apk_adi, apk_adi)
        )

    notlar = sys.argv[1] if len(sys.argv) > 1 else ""
    manifest = {
        "versionCode": kod,
        "versionName": ad,
        "apk": "%s/%s" % (BASE, apk_adi),
        "notes": notlar,
    }
    hedef = os.path.join(SITE, "surum.json")
    io.open(hedef, "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    )
    print("surum.json yazildi: %s (kod %d)" % (ad, kod))
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == "__main__":
    main()
