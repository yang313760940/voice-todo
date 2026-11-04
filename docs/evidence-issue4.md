# Issue #4 delivery evidence

Date: 2026-11-04 (Asia/Shanghai).
Issue: https://github.com/yang313760940/voice-todo/issues/4
Scope: stories 14, 21-23, 27, 41 and 49 in issue #1.
The issue remains OPEN for maintainer acceptance.

## Commits

- Commit A: `99d451c`, transactional Room storage, item editing, migration and
  revision-safe reminder delivery. It also preserves the pre-existing Instant,
  suspend and UI compatibility edits so this commit builds on its own.
- Commit B: the commit containing this report, titled
  `feat: issue #4 item details and completed-list Compose workflow`.
  Adds the full detail UI, completed list, save feedback and Compose integration
  tests. The receiver change in B is indentation only.
- No reset, checkout, clean, data removal or reversal of the initial #4 work was
  performed. All initial tracked/untracked #4 source changes are preserved in A.

## Implemented behavior

- Full-snapshot `TodoEdit` / `updateItem`, with persistence before cancellation
  and scheduling. Only a changed reminder advances its revision or is validated
  as a future time. Title, plan and deadline edits do not reschedule an alarm.
- Optional reminder, independent planned date/deadline, completion, restoration
  and one-time expired-reminder clearing feedback.
- Room tables for items, successful deliveries and the JSON migration marker;
  the Room v1 schema is checked in under `app/schemas/`.
- Explicit incomplete/completed queries and the requested deterministic order.
  Fixed-precision UTC storage retains Instant nanoseconds.
- Transactional, idempotent legacy migration using an explicit ZoneId. Parse or
  insert failure rolls back all imports and the marker. The JSON file is retained
  unchanged, including after successful migration. Production operations use Room;
  legacy write methods remain available only for compatibility test fixtures.
- Repository initialization gates all production reads, including UI, boot,
  alarm delivery and reconciliation. Receivers use goAsync/IO; ViewModel uses
  viewModelScope. No allowMainThreadQueries is used.
- Receiver precheck and service second check use the current item, revision,
  reminder instant and completion state. Notification titles come from Room.
  Successful foreground publication or fallback notify records deliveredAt;
  service initiation, scheduling, elapsed time and failed publication do not.
- The delivery transaction prevents concurrent/duplicate publication of a
  revision during normal operation. Old-revision records never drive the badge.
- Compose state switches between the existing input/list and details; no large
  navigation framework. Native date/time pickers, editable time fields and
  independent clear buttons are provided. Existing speech/manual reminder entry
  remains, alongside explicit untimed saving. Completed items can be restored.
- Overdue and delivered badges are independent. Overdue uses the current clock
  and excludes completed items or items without a deadline.
- UI operations and refreshes are serialized to avoid stale refresh overwrites.
  Migration failure disables data actions and offers an explicit retry.

## Verification

Commit A: `:app:testDebugUnitTest` passed with 45 tests.
Final: 56 tests, 0 failures/errors, 0 skipped; 11 are Compose UI tests.

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC -Dfile.encoding=UTF-8' --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
git diff --check
```

The final combined command passed. Lint reported 0 errors and 19 warnings
(SDK/dependency age, backup configuration, redundant API/resource qualifiers,
and kapt/KSP advice). No lint baseline or test skip was added.

The first full lint run exhausted the configured 256 MiB Metaspace. The command
above raises limits for that invocation only; project memory settings are not
changed. Earlier deliberate red tests and Robolectric main-looper/semantics
harness corrections are kept in local `.work/issue4-*.log` evidence. Assertions
were not weakened and no test was removed to obtain a green result.

Coverage includes save/validate/edit/cancel/complete/restore, current/old revision
delivery, failed and successful fallback, concurrent duplicate suppression,
database reopen, sorting, legacy conversion, transaction rollback, migration
retry, detail reopening/ViewModel recreation, independent badges, native pickers,
and a 320 x 640 dp narrow-screen workflow.

Local reports:
- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`
- `.work/issue4-delivery-validation.log`

## APKs

`app/build/outputs/apk/debug/app-debug.apk`
SHA-256: `795652787823EBC369BCDE32148D95B813F43053D4619132A9CECE874F4646D7`

`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
SHA-256: `6496685C8F360D7BBD043D49F20CA4D306A930AA797FCFDCF95607DBFAB1AA3F`

## Limits and retained risks

- UI tests ran only on Robolectric/API 33. AndroidTest APK was built, not run on
  a physical device. No adb/device operation, Redmi reinstall/data clearing or
  permission/volume/power-policy change was performed.
- This is not new cross-version/vendor lock-screen or audible-sound certification.
  Maintainer installation and real-device acceptance remain required.
- Tests use temporary SQLite databases and synthetic legacy files, not the
  maintainer's live data. Large/corrupt real-world migration latency is unmeasured;
  foreground-service startup still has Android's platform deadline.
- System notification/foreground publication and the SQLite success commit are
  not a crash-atomic distributed transaction. Process death or disk failure in
  that gap can leave a visible notification without a delivery record. Failures
  are logged, never fabricated as success.
- deliveredAt confirms the specified publication API success, not proof of
  audible playback or visibility under revoked permissions/vendor restrictions.
  Richer delivery-result classification and notification actions remain in #9.
- A pending unsaved detail draft is ViewModel state, not persistent recovery after
  process death. Saved fields survive database reopen.
- No lists, quadrants, repeating rules, multiple reminders or full homepage
  filtering are added; these remain outside #4.
