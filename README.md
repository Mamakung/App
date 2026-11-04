# Smart Scheduler + AI Stock Assistant (Android / Kotlin / Jetpack Compose)

A single Android app combining a **Smart Daily Scheduler** and an **AI-Powered Stock
Market Assistant**, built with Clean Architecture, Jetpack Compose, Room, Hilt,
Retrofit, WorkManager, and AlarmManager.

## 1. Architecture Overview

```
com.example.smartscheduler
├── data/
│   ├── local/
│   │   ├── entity/        Room entities (persistence models)
│   │   ├── dao/            Room DAOs
│   │   └── AppDatabase.kt
│   ├── remote/              Retrofit API interfaces + DTOs (stock, news, AI)
│   └── repository/          AuthRepository, ScheduleRepository, StockRepository
├── domain/
│   └── model/                Plain Kotlin data classes used by the UI/ViewModel layer,
│                              decoupled from Room so persistence can change independently
├── notification/            AlarmScheduler (AlarmManager), BroadcastReceiver,
│                              WorkManager workers (boot reschedule + daily refresh)
├── di/                       Hilt modules (Database, Network, Firebase)
├── navigation/               Jetpack Navigation Compose graph
└── ui/
    ├── screens/auth/          Login / Register
    ├── screens/dashboard/     Today's schedule (Module 2)
    ├── screens/schedule/      Template management + Batch multi-day assignment
    ├── screens/stocks/        Stock Market Hub + Detailed Analysis (Module 3)
    └── theme/                 Material 3 theme
```

**Layering rule:** UI → ViewModel → Repository → (Room DAO | Retrofit API).
ViewModels never touch DAOs or Retrofit services directly; Repositories are the
single source of truth and the only place that decides "local cache vs. network."

## 2. Spec → Implementation Map

| Spec | Implementation |
|---|---|
| #1 Auth (Firebase Auth + Repository) | `AuthRepository.kt`, `AuthScreen.kt` |
| #1 Profile/preferences/local sync | `UserEntity`, `UserDao`, `AuthRepository.updatePreferences()` |
| #2 Wake-up + time-slotted tasks, status | `ScheduleDayEntity`, `ScheduleTaskEntity`, `DashboardScreen.kt` |
| #3 Standard Routine Template + propagation | `RoutineTemplateEntity/Task`, `ScheduleRepository.propagateTemplateToFutureDays()` |
| #4 Multi-day / batch assignment | `ScheduleDao.applyBatchTasks()`, `BatchAssignScreen.kt` |
| #5 Single-day overrides w/o touching template | `ScheduleOverrideEntity`, `ScheduleRepository.editSingleDayTask/insertSingleDayTask/deleteSingleDayTask` |
| #6 Local notifications | `AlarmScheduler.kt` (AlarmManager), `ReminderBroadcastReceiver.kt`, `AlarmWorkers.kt` (WorkManager for boot-reschedule + daily pre-generation) |
| #7 Dual-market (TH/US), price + status | `StockQuoteApi`, `Market` enum, tabs in `StockMarketHubScreen.kt` |
| #8 Watchlist + notes | `WatchlistItemEntity`, `StockNoteEntity`, `StockRepository` |
| #9 Daily AI news summarizer | `NewsFeedApi` + `AiAssistantApi.summarizeNews()`, cached in `MarketNewsSummaryEntity` |
| #10 Buy/Hold/Sell engine + reasoning + disclaimer | `AiAssistantApi.analyzeStock()`, `StockRecommendationEntity`, `StockAnalysisScreen.kt` |

## 3. Database Schema Summary (Room)

- **users** — profile + preferences (wake-up default, notifications, theme, sync toggle)
- **routine_templates** / **routine_template_tasks** — the editable "Standard Daily Routine"
- **schedule_days** / **schedule_tasks** — concrete generated days, `isCustomized` flag
  gates template propagation per Spec #5
- **schedule_overrides** — audit log of every single-day edit/insert/delete
- **watchlist_items** — favorited tickers with cached last quote
- **stock_notes** — free-text notes per ticker
- **market_news_summaries** — cached daily AI bullet-point summaries per market
- **stock_recommendations** — cached Buy/Hold/Sell signal + reasoning + disclaimer

All foreign keys cascade on delete (e.g. deleting a `schedule_days` row removes its
`schedule_tasks` and `schedule_overrides`).

## 4. Key Design Decisions

- **Propagation safety (Spec #3 + #5):** `ScheduleDayEntity.isCustomized` is the single
  gate that determines whether a day is regenerated when the master template changes.
  Any single-day edit sets this flag, permanently excluding that day from future
  propagation until the user resets it.
- **Batch assignment (Spec #4):** all days created/updated by one batch action share a
  `batchGroupId` (UUID), enabling later bulk-undo or "show me what I just batch-assigned."
- **Notifications (Spec #6):** `AlarmManager.setExactAndAllowWhileIdle` is used for
  precise wake-up/task alarms; a `BroadcastReceiver` posts the actual notification.
  Because exact alarms are cleared on reboot, a `BOOT_COMPLETED` receiver enqueues a
  `WorkManager` job (`RescheduleAlarmsWorker`) to regenerate them from Room, respecting
  Android's background execution limits.
- **AI calls stay server-side:** `AiAssistantApi` targets your own backend proxy, not
  the LLM vendor directly — this keeps API keys off the device and allows you to swap
  or fine-tune the summarization/analysis prompts without an app update.
- **Recommendation disclaimers:** `StockRecommendationEntity.riskDisclaimer` always has
  a non-blank default and is displayed prominently in `StockAnalysisScreen` — the
  signal is never rendered without it.

## 5. Setup Checklist

1. Add your `google-services.json` (Firebase project with Email/Password auth enabled)
   to `app/`.
2. Point `NetworkModule.kt`'s three base URLs to your actual stock-data provider,
   news-feed provider, and AI backend proxy.
3. Implement the backend proxy's two endpoints (`/v1/ai/summarize-news`,
   `/v1/ai/analyze-stock`) — these should call the Anthropic API server-side and
   return the shapes defined in `ApiServices.kt`.
4. Request `SCHEDULE_EXACT_ALARM` at runtime on Android 12+ if
   `AlarmScheduler.canScheduleExactAlarms()` returns false (deep-link to
   `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`).
5. Replace `fallbackToDestructiveMigration()` in `DatabaseModule` with real `Migration`
   objects before shipping an update that changes the schema.

## 6. Known Simplifications (flagged for production hardening)

- Batch date-picking in `BatchAssignScreen` stubs out the actual `DatePickerDialog`
  call — wire up `MaterialDatePicker`/Compose date picker to `viewModel.addExplicitDate()`.
- `AuthRepository` does not yet implement email verification or re-auth-before-delete
  flows.
- Quote/news/AI API responses are illustrative DTOs; adjust field names to match your
  chosen providers (e.g., Alpha Vantage, Finnhub, SETTRADE, NewsAPI).

---

## v2 changes (read this first)

**ก่อน build ต้องทำ 1 อย่าง:** แอปใช้ Firebase Auth จึงต้องวางไฟล์ `google-services.json`
ไว้ที่ `app/google-services.json` (สร้างโปรเจกต์ใน Firebase Console, package name
`com.example.smartscheduler`, เปิด Email/Password sign-in). ถ้าไม่มีไฟล์นี้ แอปจะ build ได้
แต่จะปิดตัวตอนเปิดเพราะ FirebaseAuth ยังไม่ถูกตั้งค่า

สิ่งที่เพิ่ม/เปลี่ยน
- ช่วงเวลาแบ่งเป็น 3 ประเภท: ทำงาน / พัก / กิจกรรม (`TaskKind`) แต่ละช่วงมีเวลาเริ่ม–สิ้นสุด และเปิด/ปิดแจ้งเตือนได้
- กิจวัตรมาตรฐานเลือก "วันที่ทำซ้ำ" และ "เดือนที่ทำซ้ำ" ได้ (`appliesToDaysOfWeek`, `appliesToMonths`)
- ผู้ใช้ใหม่ได้กิจวัตรตัวอย่าง (ทำงาน/พักเที่ยง/พักสั้น) ไม่เจอหน้าว่าง
- เพิ่มหลายวัน: เลือกช่วงวันที่ + วัน + เดือน หรือเลือกวันที่เองด้วย date picker จริง (เพิ่มต่อท้าย ไม่ลบของเดิม)
- แก้เฉพาะวัน: แตะรายการเพื่อแก้/ลบ, ปุ่ม "รีเซ็ต" กลับไปตามกิจวัตรมาตรฐาน
- UI ใหม่: ธีม, bottom navigation (วันนี้ / กิจวัตร / หุ้น), แถบสัปดาห์, สรุปเวลาทำงาน/พัก
- แก้บั๊กแจ้งเตือน: หลังรีบูตจะตั้งปลุกใหม่ให้ 30 วันล่วงหน้า
- ฐานข้อมูลเป็น version 2 (ถ้าเคยติดตั้งเวอร์ชันเก่า ข้อมูลจะถูกล้างอัตโนมัติ)

---

## v3: หน้าตาใหม่

- ธีมใหม่: พื้นเทาอมเขียว, ตัวอักษรสีหมึกเขียวเข้ม, ทำงาน = น้ำเงิน, พัก = เขียวใบเตย (ลายทแยง), กิจกรรม = ขมิ้น
- หน้า "วันนี้": เส้นเวลาที่ความสูงของแต่ละช่วงเท่ากับเวลาจริง มีเส้นแดงบอกเวลาปัจจุบัน และหัวจอบอกว่าเหลืออีกกี่นาทีก่อนจบช่วงนี้
- หน้า "กิจวัตร": แถบภาพรวมทั้งวัน, ปุ่มเลือกวัน 7 ช่องและเดือน 12 ช่อง, รายการช่วงเวลา
- หน้า "เพิ่มหลายวัน": ปฏิทินแสดงล่วงหน้าว่าวันไหนจะได้รับกิจกรรม
- หน้า "หุ้น": เพิ่มหุ้นได้จริง (กดค้างเพื่อเลิกติดตาม) และไม่ปิดตัวเมื่อโหลดข้อมูลไม่ได้
- ภาพตัวอย่างอยู่ในโฟลเดอร์ `design/`

---

## v4: build APK บน GitHub + ใช้งานได้โดยไม่ต้องล็อกอิน

- ไม่มี `google-services.json` ก็เปิดแอปได้: ข้ามหน้าล็อกอิน ใช้ผู้ใช้ในเครื่อง (`CurrentUser`) ข้อมูลอยู่ในเครื่อง
- ถ้าอยากเปิดล็อกอิน: วาง `app/google-services.json` หรือใส่เนื้อหาไฟล์เป็น GitHub secret ชื่อ `GOOGLE_SERVICES_JSON`
- `.github/workflows/build-apk.yml` สั่ง GitHub build APK ทุกครั้งที่ push ขึ้น main ดาวน์โหลดได้จากแท็บ Actions → Artifacts
- เพิ่ม `gradlew` แล้ว build ในเครื่องด้วย `./gradlew assembleDebug` ได้เลย

---

## v5: ฟอนต์ไม่มีหัว + ระยะโปร่ง

- ฝัง IBM Plex Sans Thai (Regular/Medium/SemiBold/Bold) ไว้ใน `app/src/main/res/font` (สัญญาอนุญาต OFL อยู่ใน `licenses/`)
- ตัวหนาเปลี่ยนเป็น SemiBold, ระยะบรรทัดประมาณ 1.6 เท่า, เพิ่มช่องว่างระหว่างส่วน แถว และกล่อง
- ภาพก่อน/หลัง: `design/compare.png`

---

## v6: อัปเดตในแอป + แจ้งเตือนเมื่อมีเวอร์ชันใหม่

ทุกครั้งที่ push ขึ้น `main` GitHub Actions จะ build APK ที่เซ็นด้วยกุญแจเดียวกัน ตั้งเลขเวอร์ชัน `1.0.<เลข build>` แล้วสร้าง Release
แอปจะเช็ก Release ล่าสุดตอนเปิดแอปและทุก 12 ชั่วโมง ถ้ามีเวอร์ชันใหม่จะแจ้งเตือนและให้กด "อัปเดตเลย"

ตั้งค่าครั้งเดียว:
1. repo ต้องเป็น **public** (แอปอ่าน Release โดยไม่มี token)
2. เพิ่ม secret `SIGNING_KEYSTORE_B64` = เนื้อหาไฟล์ `SIGNING_KEYSTORE_B64.txt`
3. เก็บ `release.keystore` ไว้ที่ปลอดภัย ถ้าทำหาย เวอร์ชันถัดไปจะติดตั้งทับเวอร์ชันเดิมไม่ได้ (ต้องลบแอปแล้วติดตั้งใหม่)

ข้อความ commit จะกลายเป็น "มีอะไรใหม่" ที่แสดงในหน้าต่างอัปเดต

**สำคัญเมื่อแก้ฐานข้อมูล:** ถ้าเพิ่ม `version` ใน `AppDatabase` ต้องเขียน `Migration` ด้วย
ไม่งั้น `fallbackToDestructiveMigration()` จะล้างข้อมูลผู้ใช้ตอนอัปเดต

---

## v7: สัปดาห์เริ่มวันอาทิตย์

- ลำดับวัน อา จ อ พ พฤ ศ ส ในแถบสัปดาห์ ปุ่มเลือกวัน และปฏิทินหน้าเพิ่มหลายวัน (`Recurrence.dayCodes`, `Recurrence.weekStart`)

---

## v8: นำเข้าตารางจากไฟล์ (ตารางเรียน ตารางงาน ตารางเวร)

- หน้า "นำเข้าตารางจากไฟล์" (ไอคอนอัปโหลดในหน้าวันนี้ หรือในหน้ากิจวัตร)
- **CSV**: อ่านในเครื่อง คอลัมน์ `day,start,end,title,code,room,teacher,kind` (หัวตารางภาษาไทยก็ได้) ตัวอย่างใน `samples/timetable_example.csv`
- **รูป/PDF**: ส่งให้ Claude (`claude-sonnet-5-5`) อ่าน ต้องใส่ API key ของผู้ใช้ในแอป (ปุ่มกุญแจมุมขวาบน) เก็บในเครื่องเท่านั้น
- ตรวจ/แก้รายการก่อนบันทึก เลือกช่วงภาคเรียน และเลือกได้ว่าจะแทนที่ตารางเดิมในวันนั้นหรือเพิ่มต่อท้าย
- ประเภทใหม่ "เรียน" (`TaskKind.CLASS`) สีม่วงพลัม แจ้งเตือน "📚 เข้าเรียน"
- เส้นเวลา: ช่วงสั้นไม่ยืดทับช่วงถัดไปแล้ว

---

## v9: ภาคเรียน

- หน้านำเข้า: ใส่ "วันเปิดภาค" + "เรียนกี่สัปดาห์" แทนช่วงวันที่ สอบปลายภาค = สัปดาห์ถัดไป
- เลือกวิชาที่สอบในสัปดาห์เรียนสุดท้ายได้ วิชานั้นจะเรียนถึงสัปดาห์ก่อนหน้า
- ลงตารางตั้งแต่วันนี้ (วันที่ผ่านมาแล้วไม่แก้)
- หน้าวันนี้แสดง "สัปดาห์ที่ N จาก 17" หรือ "สัปดาห์สอบปลายภาค" (`SemesterStore`)
