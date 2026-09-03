# AudPlayer 🎧

A feature-rich, offline audio player and voice changer for Android. Built with native Java — no ads, no tracking, no internet required.

![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Min SDK](https://img.shields.io/badge/min%20SDK-24%20(Android%207.0)-blue)
![License](https://img.shields.io/badge/license-TBD-lightgrey)

## ✨ Features

### Playback
- Play local audio files with a clean, gesture-friendly player UI (swipe up/down on the mini player to expand/collapse)
- Background playback with a foreground service and notification controls (play/pause, next/previous, close app)
- Playback modes: Repeat Current, Next in List, Random
- A-B Repeat — loop any section of a song
- Sleep timer (15/30/45/60 min or custom) with pause or close-app end action
- Configurable seek skip buttons (1–300 seconds)

### Library management
- All-songs list and folder view (toggle in the bottom navigation)
- Instant search and sorting (name, date, size, duration — ascending/descending)
- File options: details (tap the path to copy it), rename, delete, share, add to playlist
- Browse and open any audio file via the system file picker
- Auto-slide to the currently playing song
- Playlists with create/rename/delete, stored in a local SQLite database

### Audio effects
- **Equalizer** — band sliders, device presets, and custom presets (Rock, Pop, Jazz, Soft…)
- **Speed** — 0.25x–4x playback speed
- **Volume Boost** — up to 5x via a loudness enhancer

### Voice Changer
All controls apply live and are remembered between songs:

| Control | What it does |
|---|---|
| Pitch | Raise/lower pitch (0.25x–4x) with quick chips |
| Formant | Shift voice character without changing speed |
| Speed | Playback speed (0.25x–4x) |
| Bass / Reverb | Deepen low end, add room ambience |
| Treble | Boost or cut high frequencies |
| Vocal Clarity | Enhance speech frequencies, reduce muddiness |
| Echo | Repeated reflections |
| Distortion | Metallic, aggressive tone |
| Vibrato | Periodic pitch variation |
| Volume Boost | Up to 3x volume (synced with the Boost tool) |
| Voice Depth | Deeper ↔ thinner character |
| Robot | Synthetic voice effect (toggle) |
| Noise Reduction | Suppress background noise (toggle, device-dependent) |
| Auto-tune | Snap pitch to exact musical semitones (toggle) |

Plus 17 one-tap **voice presets** (Monster, Chipmunk, Alien, Ghost, Underwater, and more), voice-tuned **EQ presets**, per-slider reset, and a master reset.

### Mixer mode (dual track)
- Play a second track alongside the primary one
- Adjust L/R balance, sync track positions
- Independent speeds per track
- Save and reload mixer setups

### Appearance & behavior
- Themes: White, Bluish Black, Milky, Custom color
- Play alongside other apps (audio focus control)
- Choose which settings are resettable (speed, pitch, equalizer, boost)

## 📸 Screenshots

> Add screenshots here, e.g.:
>
> ```
> <p align="center">
>   <img src="docs/screenshots/home.png" width="270">
>   <img src="docs/screenshots/voice_changer.png" width="270">
>   <img src="docs/screenshots/mixer.png" width="270">
> </p>
> ```

## 🛠 Tech stack

- **Language:** Java
- **UI:** Android SDK + Material Components (Material 3)
- **Audio:** `MediaPlayer` + `PlaybackParams` (pitch/speed/formant), Android `audiofx` framework (Equalizer, BassBoost, EnvironmentalReverb, LoudnessEnhancer, NoiseSuppressor)
- **Storage:** SQLite (`PlaylistDatabaseHelper`)
- **Min SDK:** 24 (Android 7.0) · **Target SDK:** 34 · **Compile SDK:** 35

## 🔨 Building from source

1. Clone the repository:
   ```bash
   git clone https://github.com/<your-username>/aud_player.git
   cd aud_player
   ```
2. Open the project in **Android Studio** (Hedgehog or newer recommended) and let Gradle sync, **or** build from the command line:
   ```bash
   ./gradlew assembleDebug
   ```
3. The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

For a release build, configure your signing config and run `./gradlew assembleRelease`.

## 📂 Project structure

```
app/src/main/java/com/example/aud_player/
├── MainActivity.java          # Player UI, audio effects, settings
├── AudioPlaybackService.java  # Foreground playback service (notification, boost, pitch)
├── AudioAdapter.java          # Song list adapter
├── AudioFile.java             # Audio file model
├── FolderAdapter.java         # Folder view adapter
├── Playlist*.java             # Playlist screens, adapters, SQLite helper
├── MainActivity bottom sheets:
│   ├── PitchBottomSheet.java  # Voice Changer
│   ├── SpeedBottomSheet.java  # Speed control
│   ├── BoostBottomSheet.java  # Volume boost
│   ├── ABRepeatBottomSheet.java
│   ├── TimerBottomSheet.java  # Sleep timer
│   ├── MenuBottomSheet.java   # "More" panel
│   └── SortBottomSheet.java
```

## 🔐 Permissions

- `READ_MEDIA_AUDIO` (Android 13+) / `READ_EXTERNAL_STORAGE` (older versions) — to read your local music. Nothing else; the app works fully offline.

## 🤝 Contributing

Issues and pull requests are welcome. For bugs, please include your device model, Android version, and steps to reproduce.

## 📄 License

This project does not currently include a license. If you plan to publish it, consider adding one (e.g., MIT or Apache 2.0) so others know how they can use it.
