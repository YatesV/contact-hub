# Orbit

(formerly Contact Hub)

Texts, contacts, calls, socials and Life360 in one Android app, built for a Galaxy S24.

## Install
Open this on your phone and install it:
**https://github.com/YatesV/contact-hub/releases/latest/download/Orbit.apk**

Every push to `main` builds a new APK. New versions install over the old one and keep your data.

## First-time setup (in the app: Settings → Setup & permissions)
1. Allow permissions (texts, contacts, calls, notifications)
2. **Allow restricted settings**: App info → ⋮ → Allow restricted settings (needed for 3 and 4)
3. Notification access → Orbit
4. Accessibility → Installed apps → **Orbit RCS sender** → On
5. Set Orbit as the default **phone** app

Keep **Google Messages as the default texting app**. Orbit sends through it, so RCS works and nobody can tell you're using a different app.

## How sending works (stealth rule)
1. Through Google/Samsung Messages' own reply button (RCS when available)
2. Through Google/Samsung Messages itself: the accessibility service taps Send for you
3. Plain SMS, 1-on-1 only, and only if 1 and 2 definitely didn't send

- No branding, ever
- No duplicate texts
- Reactions only over RCS: if a chat is on SMS, the reaction waits (🕓) and is never sent as a "Liked…" text
- Group names/photos stay on your phone
- No polls

## Features
- **Inbox**: categories (Personal / Groups / Codes / Deliveries / Spam), custom folders, pin, archive, mute, swipe actions, search, starred, hidden chats
- **Chat**: photos/videos, reactions, "Liked…" texts shown as reactions, copy one-time codes, schedule send, @mentions, per-member colors, per-chat wallpaper, bubble color and sound
- **Contact page**: Instagram / Snapchat / TikTok buttons, Life360 mini map + place + distance + battery + last updated, stats and streaks, birthday + notes, recent calls, shared media, priority, block
- **Phone**: keypad with contact search, recents, favorites, custom call screen, call blocking
- **Look**: Google / Samsung / iMessage / custom presets, colors, fonts, text size, bubble shape, density, avatars, swipe actions, wallpaper
- **Privacy**: fingerprint/PIN lock, hidden chats, hidden notification previews
- **Extras**: auto-reply (Quick Settings tile + widget), home screen widget, daily backup + export/restore
- **Smart**: smart replies (on-device, or in your style with a Grok/OpenAI key), "Catch me up" summaries, translate any message, voice messages to text, snooze chats, search inside a chat (links / photos / codes), repeating scheduled texts, pinned messages
- **People**: birthday reminders with a one-tap text, "haven't talked in a while" check-ins, relationship history (texting since, best streak, who texts more), contact groups with colors and sounds, call notes, spam caller warnings
- **Location**: "text when I arrive", today's Life360 trail on contact pages, Life360 people on the mock map
- **Extras**: 4 app icons (Settings → Look & feel), one-person home screen widgets, text from your laptop over Wi-Fi, quick replies on your watch, shake to hide
- **Mock location** (Settings → Developer): fake GPS for single points or routes that follow real roads (slows for turns, speed presets + slider in mph, stay / stop / loop / back and forth at the end), favorites, recent, scheduled starts, pause/stop from the notification, Quick Settings tile, resumes after a restart. Needs Developer options → Select mock location app → Orbit (the app walks you through it). Maps are Mapbox with a free token, or OpenStreetMap without one. Details: `docs/mock-location.md`

## Known limits
- No public RCS API exists. The RCS sender depends on how Google/Samsung Messages' screens are built, so their updates can break it. SMS fallback keeps 1-on-1 texting working.
- Life360 has no public API. It uses the same unofficial connection Home Assistant does, so Life360 changes can break it. The Open Life360 button always works.
- Not in v1: call recording (Android blocks other apps from recording the other person), visual voicemail, unified Instagram/Snapchat inbox.

## Build locally
JDK 17 + Android SDK 35: `./gradlew testDebugUnitTest assembleDebug`
