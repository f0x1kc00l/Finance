# Changelog

## 2.0.0

### Critical fixes (P0)
- **Enabling the app lock made the app unusable.** `LockScreen` cast the context to `FragmentActivity`
  but `MainActivity` was a `ComponentActivity`, so the biometric prompt never appeared and nothing else
  could unlock. `MainActivity` is now a `FragmentActivity` and the lock is PIN-based.
- **Lock bypass via Back.** Auto-lock pushed a lock *destination* on top of the previous screen, so
  pressing Back revealed the app. The lock now replaces the navigation host entirely.
- **App PIN was unreachable and unusable.** No screen navigated to PIN setup, and verification ran
  about 310 000 Android Keystore HMAC operations on the main thread. Replaced with PBKDF2 plus a single
  Keystore-HMAC pepper, run off the main thread, with setup/change/disable flows wired into Settings.
- **Google Pay reported success without any payment.** `validatePaymentWithBackend` always returned
  `Success`, and release builds used PRODUCTION with the `"example"` gateway. Now fails closed (see README).
- **Biometric failures counted toward PIN lockout**, so a few misread fingers locked the app.

### Financial correctness (P1)
- Money is stored as integer minor units (Room migration v1→v2, verified); `Double` math removed.
- The home "this month" total ignored expenses added after the screen opened (the range ended at
  subscription time). Ranges are now half-open calendar periods that roll over at midnight.
- The bank parser read `12 500,00 AMD` as 1 250 000. Decimal and grouping rules were rewritten;
  balances, declined payments, refunds and incoming transfers are excluded; ambiguous texts are rejected.
- CSV export broke columns in Russian locale (`1234,50` unquoted). Amounts are now always `1234.50`,
  every field is quoted, and spreadsheet formula injection is neutralised.
- Category percentages now sum to exactly 100 %. Zero-division and empty-period cases are handled.
- Duplicate submissions: save buttons are guarded, and auto-detected expenses carry a unique de-dup key.

### Reliability
- Notifications posted as `Spannable` text were silently ignored (`getString` → `getCharSequence`).
- The "tap to record" notification had no tap action, and the background activity launch is blocked
  on Android 10+. It is now a proper notification with a `PendingIntent`, hidden on the lock screen.
- `POST_NOTIFICATIONS` (Android 13+) was never requested. Settings now shows its status and asks for it.
- `runBlocking` DataStore reads on the main thread were removed. Settings load asynchronously while
  the splash screen stays up.
- Destructive downgrade fallback removed, so a missing migration can no longer wipe data.

### Security & privacy
- Strong-biometric unlock is bound to a Keystore key that a new enrollment invalidates.
- Escalating capped lockout (30 s → 15 min), persisted, resistant to backwards clock changes.
- Auto-lock uses monotonic time. "Hide in Recents" was added. `FLAG_SECURE` is on during PIN entry.
- Unused permissions removed (`RECEIVE_BOOT_COMPLETED`, `NFC`, `VIBRATE`, `FOREGROUND_SERVICE`,
  `USE_FINGERPRINT`, `SCHEDULE_EXACT_ALARM`, `WRITE_EXTERNAL_STORAGE`, `INTERNET`). The no-op boot and
  auto-lock receivers were removed.
- `FileProvider` previously exposed all of external storage (`path="."`); now only `cache/exports/`.
- The quick-add sheet no longer appears over the device lock screen.
- The fake Play Integrity stub (always "clean") was removed. A false "settings encrypted (AES-256)"
  claim was replaced with accurate privacy statements.
- R8 no longer keeps all of Compose runtime, biometric and security-crypto.

### Features & UX
- New design system: light and dark themes, tokens, tabular figures, consistent cards and components.
- Home: month total with like-for-like comparison, budget progress with remaining amount and per-day
  allowance, insights (average per day, largest expense, top category), top categories, recent list,
  search (matches localized category names and escapes `%`/`_`), swipe-to-delete with **undo**.
- **Editing expenses** (tap a row): amount, category, method, **date**, note; delete with confirmation.
- Analytics: day/week/month/year/custom range, comparison with the previous period, donut and
  zero-filled bar trend (monthly bars for long ranges), accessible descriptions.
- Settings: currency (labelled as display-only), budget with validation and removal, lock/PIN/biometric/
  auto-lock, notification access and permission status, CSV **save or share**, honest privacy notes.
- All strings moved to resources. Accessibility labels, live regions and 48 dp touch targets throughout.

### Tests & tooling
- 92 JVM unit tests (money, keypad, formatting, periods, analytics, budget, parser, de-dup, PIN hashing,
  PIN policy/lockout, PIN flows, session/auto-lock, CSV). The previous mock-based PIN tests mirrored
  the implementation and were replaced.
- Instrumented `MigrationTest` (v1→v2) and `ExpenseDaoTest`.
- CI runs unit tests, lint, debug and release (R8) builds, compiles instrumentation tests, validates the
  Gradle wrapper and checks that Room schema JSON is committed.
