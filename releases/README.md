# Resolve Android test builds

## 2026-06-20 debug APK

- File: `resolve-android-debug-2026-06-20.apk`
- SHA-256: `6d540329043f90407a3eeb2c43c2d79954ca3b7c184256f68800885025de2766`
- Purpose: quick install test on OnePlus 13.

## 2026-06-20 backend debug APK

- File: `resolve-android-backend-debug-2026-06-20.apk`
- SHA-256: `102e89a5a15b2a607e765f54e179b5ea66fc1cd84ea9f6fcdddcc205c7ced7c9`
- Purpose: Android test build for Supabase backend login and server-side Feishu Calendar sync.

Android test flow:

1. Settings -> Account: enter login email and password, then Sign in.
2. Settings -> Feishu Calendar: tap Connect, approve Feishu, return to Resolve.
3. Tap Sync if the first sync has not started automatically.
