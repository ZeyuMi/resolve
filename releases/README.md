# Resolve Android test builds

## 2026-06-20 debug APK

- File: `resolve-android-debug-2026-06-20.apk`
- SHA-256: `6d540329043f90407a3eeb2c43c2d79954ca3b7c184256f68800885025de2766`
- Purpose: quick install test on OnePlus 13.

## 2026-06-20 backend debug APK

- File: `resolve-android-backend-debug-2026-06-20.apk`
- SHA-256: `9d6937b91e530531d543513beb9a02b720bf1b69219a41b0c19d0a6164dcfd76`
- Purpose: Android test build for Supabase backend login and server-side Feishu Calendar sync.

Android test flow:

1. Settings -> Resolve Backend: enter Supabase URL, anon key, login email, password, then Sign in.
2. Settings -> Feishu Calendar: enter Feishu App ID and App Secret once.
3. Add this redirect URI in Feishu console: `https://<project-ref>.supabase.co/functions/v1/feishu-oauth-callback`.
4. Tap Connect via Backend, approve Feishu in the browser, return to Resolve, then Sync.
