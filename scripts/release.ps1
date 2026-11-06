# ปล่อยเวอร์ชันใหม่จากเครื่องตัวเอง (Windows PowerShell): build APK ที่เซ็นแล้ว + สร้าง GitHub Release
# ต้องมี: app\release.keystore, GitHub CLI (gh) ที่ล็อกอินแล้ว (gh auth login)
# ใช้: .\scripts\release.ps1 "มีอะไรใหม่ในเวอร์ชันนี้"
param([string]$Notes = "อัปเดตใหม่")
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$Repo = if ($env:UPDATE_REPO) { $env:UPDATE_REPO } else { "Mamakung/App" }
if (-not (Test-Path "app\release.keystore")) { throw "ไม่พบ app\release.keystore (กุญแจเซ็นแอป) วางไฟล์ไว้ก่อน" }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "ไม่พบ GitHub CLI (gh) ติดตั้งจาก https://cli.github.com แล้วรัน gh auth login" }
gh auth status *> $null
if ($LASTEXITCODE -ne 0) { throw "gh ยังไม่ได้ล็อกอิน รัน: gh auth login" }

$utc = [DateTimeOffset]::UtcNow
$env:VERSION_CODE = [string][math]::Floor(($utc.ToUnixTimeSeconds() - 1767225600) / 60)
$bkk = [System.TimeZoneInfo]::ConvertTime($utc, [System.TimeZoneInfo]::FindSystemTimeZoneById("SE Asia Standard Time"))
$env:VERSION_NAME = $bkk.ToString("yyyy.MM.dd-HHmm")
$env:UPDATE_REPO = $Repo
Write-Host "กำลัง build เวอร์ชัน $($env:VERSION_NAME) (code $($env:VERSION_CODE))"

.\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) { throw "build ไม่ผ่าน" }
$Apk = "SmartScheduler-$($env:VERSION_NAME).apk"
Copy-Item "app\build\outputs\apk\release\app-release.apk" $Apk -Force

gh release create "build-$($env:VERSION_CODE)" $Apk --repo $Repo --title "SmartScheduler $($env:VERSION_NAME)" --notes $Notes --latest
if ($LASTEXITCODE -ne 0) { throw "สร้าง Release ไม่สำเร็จ" }
Remove-Item $Apk
Write-Host "ปล่อยแล้ว: แอปจะเห็นเวอร์ชัน $($env:VERSION_NAME) ภายในไม่กี่นาที (หรือกด ตรวจหาอัปเดต ในแอป)"
