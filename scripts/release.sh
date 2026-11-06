#!/usr/bin/env bash
# ปล่อยเวอร์ชันใหม่จากเครื่องตัวเอง: build APK ที่เซ็นแล้ว + สร้าง GitHub Release
# แอปที่ติดตั้งอยู่จะเห็นอัปเดตและกดอัปเดตในแอปได้
#
# ต้องมี: app/release.keystore, GitHub CLI (gh) ที่ล็อกอินแล้ว (gh auth login)
# ใช้: ./scripts/release.sh "มีอะไรใหม่ในเวอร์ชันนี้"
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="${UPDATE_REPO:-Mamakung/App}"
NOTES="${1:-อัปเดตใหม่}"

[ -f app/release.keystore ] || { echo "ไม่พบ app/release.keystore (กุญแจเซ็นแอป) วางไฟล์ไว้ก่อน"; exit 1; }
command -v gh >/dev/null || { echo "ไม่พบ GitHub CLI (gh) ติดตั้งจาก https://cli.github.com แล้วรัน gh auth login"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh ยังไม่ได้ล็อกอิน รัน: gh auth login"; exit 1; }

export VERSION_CODE=$(( ( $(date -u +%s) - 1767225600 ) / 60 ))
export VERSION_NAME=$(TZ=Asia/Bangkok date +%Y.%m.%d-%H%M)
export UPDATE_REPO="$REPO"
echo "กำลัง build เวอร์ชัน $VERSION_NAME (code $VERSION_CODE)"

chmod +x gradlew
./gradlew assembleRelease
APK="SmartScheduler-$VERSION_NAME.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"

gh release create "build-$VERSION_CODE" "$APK" \
  --repo "$REPO" \
  --title "SmartScheduler $VERSION_NAME" \
  --notes "$NOTES" \
  --latest
rm -f "$APK"
echo "ปล่อยแล้ว: แอปจะเห็นเวอร์ชัน $VERSION_NAME ภายในไม่กี่นาที (หรือกด ตรวจหาอัปเดต ในแอป)"
