# FinanceApp

Offline personal expense tracker for Android (Kotlin, Jetpack Compose, Material 3, Hilt, Room).
Quick manual entry, optional detection of payments from Armenian bank notifications, analytics,
monthly budget, CSV export and an app lock (PIN + optional strong biometrics).

All data stays on the device. The app has no backend and requests no `INTERNET` permission.

---

## Build & test

Requirements: JDK 17, Android SDK 35 (Build-Tools via AGP 8.7.3), Gradle wrapper 8.9.

```bash
./gradlew testDebugUnitTest          # JVM unit tests (money, analytics, parser, PIN, session, CSV…)
./gradlew lintDebug                  # Android lint (aborts on errors)
./gradlew assembleDebug              # debug APK (applicationId com.financeapp.debug)
./gradlew assembleRelease            # R8-minified release (unsigned unless signing env vars are set)
./gradlew connectedDebugAndroidTest  # Room migration + DAO tests (needs device/emulator)
```

**First build after upgrading to 2.0:** Room exports `app/schemas/com.financeapp.data.local.FinanceDatabase/2.json`.
Commit it — the migration test and CI depend on it. (`1.json` was reconstructed by hand from the v1
entities because v1.0 never committed its schema; it only seeds the migration test.)

Release signing uses `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (env vars or
Gradle properties). Never commit them.

---

## Architecture

```
core/          Pure Kotlin, no Android types — unit-tested on the JVM
  money/         exact minor-unit parsing, formatting, keypad state machine
  time/          half-open DateRange, period/comparison windows, date-rollover flow
  analytics/     totals, category shares (sum to 100 %), series, budget status
  notification/  bank notification parser + de-duplication key
  security/      PIN hashing (PBKDF2 + Keystore pepper), PIN policy, lockout policy
  export/        RFC 4180 CSV writer with formula-injection protection
  db/            v1→v2 migration SQL
domain/        Models, PinManager / PinFlow (PIN state machines), SessionManager (app lock)
data/          Room (entities, DAOs, migration), DataStore settings, PIN store, Keystore keys
di/            Hilt modules
presentation/  Compose UI: theme, shared components, screens + ViewModels (StateFlow UI state)
service/       NotificationListenerService
payment/       Google Pay request builder (not wired to UI; fails closed — see below)
```

Screens expose a single immutable `StateFlow<UiState>` collected with
`collectAsStateWithLifecycle()`. One-shot events (snackbars, sharing) use channels. Loading, empty,
error and success states are explicit on every data screen.

---

## Money

* Amounts are `Long` **minor units** (1/100) everywhere: database, domain, UI state, CSV.
  No `Double` touches money. Parsing uses `BigDecimal` and **rejects** (never rounds) more than two
  decimals. Valid range: 0.01 … 999 999 999.99.
* Currency (AMD, RUB, USD, EUR — all 2 minor digits) is a single app-wide **display** setting.
  Changing it does **not** convert amounts; the app has no exchange-rate source and never invents one.
* Periods are half-open `[start, end)` local-date ranges converted with one zone, so the home card,
  analytics, budget and charts always agree. Ranges roll over at midnight and follow time-zone changes.
* Comparisons are like-for-like: "1–18 Oct" vs "1–18 Sep" (capped at short months).
* Category percentages use the largest-remainder method so they always sum to exactly 100 %.

### Database migration v1 → v2

`expenses.amount REAL` → `amountMinor INTEGER` via `ROUND(amount * 100)`; `budgets.monthlyLimit` →
`monthlyLimitMinor` likewise; new nullable `sourceKey` with a UNIQUE index (de-dup); indexes on
`timestamp` and `category`. Rows are copied, never filtered; ids and the AUTOINCREMENT high-water
mark are preserved. Room runs it in a transaction, so a failure leaves v1 intact.
Destructive fallback migrations are **disabled**: a missing migration crashes instead of wiping data.
The SQL is verified against SQLite (row preservation, cent rounding, id sequence, schema parity) and
by `MigrationTest` on device.

---

## Security model

**Protects against:** a person holding the unlocked phone (app lock), screenshots/Recents leaks,
offline brute force of a copied PIN hash, data leaving via cloud backup or over-broad file sharing,
duplicate or fabricated "successful" transactions.

**Does not protect against:** a rooted/compromised OS, malware with accessibility or root access,
or forensic access to an unlocked, decrypted device. The database is protected by the Android
sandbox and file-based encryption, **not** by app-level encryption (see "Remaining risks").

| Area | Implementation |
|---|---|
| PIN storage | `Pepper(PBKDF2-HMAC-SHA256(pin, 16-byte salt, 120 000 it.))`. Pepper = HMAC with a non-exportable Android Keystore key (StrongBox when available). One Keystore op per check; runs off the main thread. Hash bound to the device. |
| PIN rules | 4 digits; trivially weak PINs (0000, 1234, 9876…) rejected at creation. |
| Brute force | 5 free attempts, then 30 s doubling to a 15 min cap. Persisted (survives restarts). A backwards clock change can't inflate the lockout beyond the policy maximum. |
| Biometrics | Optional, **strong** class only, bound to a Keystore AES key with `setInvalidatedByBiometricEnrollment(true)` and per-use auth. A new fingerprint/face disables biometric unlock until the PIN is used. A single failed fingerprint does not count toward PIN lockout. |
| Lock behaviour | Locked on cold start, process recreation and reboot; auto-lock after a configurable delay measured with `elapsedRealtime` (wall-clock changes can't bypass it). The lock screen *replaces* the navigation host, so Back, deep links and accessibility can't reach content underneath. |
| Forgot PIN | Confirm with the device screen lock → set a new PIN (data kept). If the device has no screen lock, the only option is erasing app data. |
| Window privacy | `FLAG_SECURE` on PIN entry and lock screen. "Hide in Recents" (default on): `setRecentsScreenshotEnabled(false)` on Android 13+, `FLAG_SECURE` on older versions. |
| Backup | `allowBackup=false` + data-extraction rules exclude everything (cloud and device transfer). |
| Export | CSV via Storage Access Framework (no storage permission) or share sheet from `cache/exports/` through `FileProvider` (only that directory is exposed). Formula injection neutralised; no ids or hashes exported. |
| Manifest | Permissions reduced to `POST_NOTIFICATIONS` and `USE_BIOMETRIC`. Only the launcher activity and the system-bound listener are exported. No cleartext traffic. Release builds are non-debuggable and R8-minified. |
| Logging | No financial data or notification text is logged; `Log.v/d/i` stripped in release. |

**Legacy lock:** v1.0 let users enable a lock based on the device credential, and that lock
could never be unlocked (see CHANGELOG). Users upgrading with that flag set are asked for their
device screen lock until they create an app PIN; if the device has no screen lock, the
unenforceable lock is switched off and the user is told why.

### Remaining risks

* Database not encrypted at app level. Adding SQLCipher would need a Keystore-wrapped key and a
  one-time re-encryption migration; it isn't done because the main threat (unlocked phone) is
  covered by the app lock and the OS already encrypts at rest.
* A forward wall-clock change can shorten a PIN lockout (no trusted time source offline).
* A 4-digit PIN is weak against a well-resourced attacker who has full device access.
* Root/emulator detection is heuristic, used only for a warning, and on Android 11+ the
  installed-package check can't see other apps without a `<queries>` declaration.
* Bank notification parsing is heuristic (see below).

---

## Bank notification detection

Optional and off until the user grants notification access in system settings.

* Only notifications from these package names are read: `am.ameriabank.mobilebank`,
  `am.inecobank.app`, `am.inecobank.mobilebank`, `am.ardshinbank.mobilebanking`,
  `am.ardshinbank.app`. **The package names come from v1.0 and have not been verified against
  current store releases.**
* Pipeline: normalise → classify (declined / refund / incoming / debit / informational) → extract
  money tokens → drop balance/limit amounts → pick exactly one amount or **reject**. Ambiguous input
  is never guessed.
* Handles `12 500`, `12,500`, `12 500,00`, `1,234.56`, `1.234,56`, NBSP/NNBSP grouping, `֏`/`AMD`/`драм`/`դրամ`,
  `$`/`USD`, `€`/`EUR`, `₽`/`RUB`, Unicode minus, Spannable text (`getCharSequence`) and `BIG_TEXT`.
* Nothing is saved automatically. A prompt notification (amount hidden on the lock screen) opens a
  quick-add sheet; picking a category saves it. A SHA-256 key of package + notification key + text
  de-duplicates updates and re-posts (in memory and with a UNIQUE database index).
* A foreign-currency amount (e.g. USD while the app shows AMD) can't be quick-saved; the user is
  sent to the full form with a warning.
* No notification text is stored.

**Tested formats:** only the **synthetic** Russian, English and Armenian fixtures in
`BankNotificationParserTest` (purchases, balances, foreign currency, declined, refunds, top-ups,
OTP/login messages, ambiguous multi-amount texts). No real bank notification was available while
these were written. Before relying on detection, capture real notifications (with personal data
redacted) and add them as test cases.

Android 10+ blocks starting activities from the background, so v1.0's automatic pop-up never
appeared. The tappable notification is the supported replacement.

---

## Google Pay

`payment/` builds valid Google Pay API requests but is **not reachable from any screen**, and the
app has no payment backend. It therefore **fails closed**:

* Environment is TEST unless built with `-PgooglePay.production=true` **and** `googlePay.gateway`,
  `googlePay.gatewayMerchantId` and `googlePay.merchantId` are provided (Gradle properties or env vars;
  never committed). v1.0 shipped release builds in PRODUCTION with the placeholder `"example"`
  gateway and a hard-coded merchant id.
* A Google Pay token is an encrypted card credential, **not** proof of payment. Only a server that
  charges it through the gateway can confirm a payment. `PaymentVerifier` must be implemented
  against such a server; until then every token ends in `Unverified`, never `Confirmed`.
  (v1.0's verifier stub always returned success.)

---

## Accessibility & localisation

Russian UI with all strings in `res/values/strings.xml`. Locale-aware number/date formatting.
Content descriptions on icons, a merged semantic label per row, live-region announcements for PIN
and form errors, an accessibility action for swipe-to-delete, 48 dp+ touch targets, text that wraps
or scrolls at large font sizes, and status shown by text or symbol rather than colour alone. Light and
dark themes meet WCAG AA contrast for body text.
