========================================================================
SUCCESSFUL BUG FIX — Duplicate Playback via Notification Next/Previous
========================================================================
Date: 2026-09-08
App: AudPlayer (com.example.aud_player)
Status: FIXED & VERIFIED ON DEVICE (M2101K6G, Android 13)

--------------------------------------------------------------------------
1. THE BUG (SYMPTOMS)
--------------------------------------------------------------------------
• Fresh app launch e notification mini-player er Next/Previous thik moto
  kaj korto.
• Kintu "Close App" (notification) ba "Exit App" (bottom sheet) diye app
  bondho kore abar khulle:
    - Main player er Next/Previous button thik chilo (no duplicate)
    - Notification er Next/Previous chaple DUITA song ekshathebajto
      (previous song choltethoto + notun song start hoto) — Mixer OFF
      thakleo!
• Abar app close korleo ekta song background-e choltethoto.

--------------------------------------------------------------------------
2. ROOT CAUSE
--------------------------------------------------------------------------
Notification er Next/Previous SURU theke direct kaj kore na. Chain chilo:

  Notification Next
      → AudioPlaybackService.onStartCommand(ACTION_NEXT)
      → sendBroadcast("MEDIA_NEXT")  (package-targeted)
      → MainActivity er mediaControlsReceiver
      → playNextFromContext()

Main player er Next button DIRECT playNextFromContext() call kore —
broadcast er madhyome NA. EI difference tai bug er mul.

2.1 LEAKED RECEIVER (mul problem):
    MainActivity er onCreate() e mediaControlsReceiver (MEDIA_NEXT /
    MEDIA_PREV / PLAYBACK_SEEKED shono) ar anonymous pausedReceiver
    (PLAYBACK_PAUSED / PLAYBACK_RESUMED shono) register hoto,
    KINTU onDestroy() e kokhono UNREGISTER hoto na (onno receiver gulo
    hoto, ei dutO bad porto).

    Tai Close App → Reopen korle:
      - purono (destroyed) activity er receiver ekhono system-e registered
      - notun activity nijer receiver register kore
      - ek Next chaple EK broadcast DUITA activity pay
      - dutai playNextFromContext() chalay
      - prottek activity-r NIJER alada mediaPlayer field thake
        → duita alada MediaPlayer toiri hoy → DUITA SONG ekshathe

2.2 ORPHAN PLAYER KE KEU CHENE NA:
    Morto activity tar player service-ke setMediaPlayers() diye janiyE
    na. Tai Close App korle service shudhu NIJER JANA player gulo bondho
    kore. Orphan player choltai thake — ei jonnoi "app close korleo
    bg-te song cholse".

2.3 onDestroy TEARDOWN BHANGUR CHILO:
    onNo unbind/unregister exception chu_dle baki shob cleanup skip hoye
    jeto. Mane leak fix korar code-o abort hote parto — ei karonei
    prothom attempt (shudhu unregister add kora) puropuri kaj koreni.

2.4 NEXT/PREV-E "AGERTA BONDHO" NIGGUJOTA CHILO NA:
    Service shudhu broadcast korto; ager track bondho korar day
    purota UI-r upor chilo. UI slow/duplicate/missing holei double
    playback hoito.

--------------------------------------------------------------------------
3. THE FIX (4 LAYERS — DEFENSE IN DEPTH)
--------------------------------------------------------------------------
LAYER 1 — LEAK BANDH (MainActivity):
  • onDestroy() e mediaControlsReceiver + pausedStateReceiver unregister
    kora hoyeche.
  • onDestroy er puro teardown try/catch-e moTa hoyeche (unbindService,
    playbackStoppedReceiver soho) — kono exception 3
    modehe teardown abort korte parbe na.

LAYER 2 — DEAD GUARD (MainActivity):
  • Duita receiver er onReceive() er shuru te:
        if (isFinishing() || isDestroyed()) return;
    Morto/finishing activity KOKHONO playback trigger korte parbe na,
    receiver somehow leak holeo.

LAYER 3 — SINGLE-INSTANCE GUARANTEE (nOtO file: PlaybackRegistry.java):
  • Process-wide static registry — app er prottekta MediaPlayer
    register hoy (create-e), unregister hoy (release-e).
  • prepareMediaPlayer() e notun track prepare-r AGE sweep:
        - Mixer OFF  → PlaybackRegistry.stopAllExcept(mediaPlayer, null)
        - Mixer ON   → stopAllExcept(mediaPlayer, secondMediaPlayer)
    Orphan player JAI path-e toiri hok, notun song suru-r AGE more.
  • Play/resume button e-o ekhi sweep — reopen kore Play chapleo
    overlap hobe na.

LAYER 4 — SERVICE-SIDE GUARANTEE (AudioPlaybackService):
  • ACTION_NEXT / ACTION_PREV: broadcast pathanor AGE service NIJEI
    current main track stop kore dey (second player shudhu mixer ON
    thakle bachay). Notification Next/Previous chaple ager song
    kono obosthay notun song-r niche cholte parbe na.
  • ACTION_STOP (Close App): nijer player chara-o
    PlaybackRegistry.stopAll() — orphan player-o more. App close korle
    process-e kono player bachbe na.
  • Sleep timer CLOSE_APP path + service onDestroy-eo ekhi sweep.

--------------------------------------------------------------------------
4. FILES CHANGED
--------------------------------------------------------------------------
  • PlaybackRegistry.java        (NOTUN) — global MediaPlayer registry
  • MainActivity.java            — receiver unregister, dead guard,
                                   teardown try/catch, registry wiring,
                                   sweep on prepare + play/resume
  • AudioPlaybackService.java    — stop-before-broadcast (Next/Prev),
                                   stopAll() on Stop/timer-close/onDestroy

KONO existing feature remove/poriborton kora hoy nai — Mixer ON thakle
second track sob khetre protected (stopAllExcept dutO keeper rakhe).

--------------------------------------------------------------------------
5. VERIFY KORAR STEPS
--------------------------------------------------------------------------
  1. Fresh launch e song bajao → notification Next/Prev test koro ✓
  2. Close App / Exit App diye bondho koro
  3. Abar khulo, song bajao
  4. Main player Next/Prev → no duplicate ✓
  5. Notification Next/Prev → no duplicate ✓
  6. Abar Close App → background-e kichu bajar kotha na ✓

Debug: logcat-e "PlaybackRegistry" filter korle orphan marle
"Stopped and released an orphaned MediaPlayer" log porbe.
========================================================================


========================================================================
BUG FIX — A-B Repeat Green Band: Not Showing + Crash on Disable
========================================================================
Date: 2026-09-11
App: AudPlayer (com.example.aud_player)
Status: FIXED & VERIFIED ON DEVICE (1399e3f4)

--------------------------------------------------------------------------
1. THE BUG (SYMPTOMS)
--------------------------------------------------------------------------
• A-B repeat set korle main seek bar e A→B porsi green band dekhato na.
• Pore band dekhao hoyeo AB repeat DISABLE korle app CRASH korto.

--------------------------------------------------------------------------
2. ROOT CAUSE
--------------------------------------------------------------------------
Debug logging diye (tag: AudioPlayerApp, "ABGB" prefix) device-e
progress drawable er structure dekha gelo:

  Layer 0: background   = GradientDrawable
  Layer 1: secondary    = ScaleDrawable   (!)
  Layer 2: progress     = ScaleDrawable

2.1 BAND DEKHATO NA / MOTO HOYETO NA:
    Code dhorechilo secondary layer ta ClipDrawable. Kintu ei device
    (Material3 theme) te ta ScaleDrawable. Java er generic cast
    (ClipDrawable) silent fail na — instanceof check er karone
    bounds-shift code SKIP hoto. Fole green tint full track 0 theke
    dekhato, A theke na.

2.2 DISABLE-EO CRASH:
    Restore path-e chilo direct cast:
        (ClipDrawable) findDrawableByLayerId(secondaryProgress)
    ScaleDrawable ke ClipDrawable e cast → ClassCastException → CRASH.

2.3 THICKNESS ISSUE:
    First fix-e band ta layer er FULL bounds e akikto holo (seekbar er
    puro height, thumb area soho) — band mota dekhacchilo.

--------------------------------------------------------------------------
3. THE FIX (B031)
--------------------------------------------------------------------------
Platform drawable-er Clip/Scale structure er upor AR nirdoshito kora
hoyeche. Sudhu secondaryProgress layer-ta ekta custom drawable
(ABBandDrawable) diye replace kora hoy:

  • ABBandDrawable (inner static class, MainActivity):
      - nijer bounds er MIDDLE-e TRACK THICKNESS-e bar drawing kore
        (thickness = original layer er getIntrinsicHeight(),
         fallback 4dp) — full height e na
  • Enable er somoy:
      - original layer reference rakha (abOriginalSecondaryLayer)
      - LayerDrawable.setDrawableByLayerId(secondaryProgress,
        abBandDrawable) diye swap
      - bar er window: bounds.left  = A/duration * trackWidth
                       bounds.right = B/duration * trackWidth
      - setSecondaryProgress(1) — layer-ta draw hoyar jonno level > 0
        lagbe; custom drawable level ignore kore
  • Disable/Clear er somoy:
      - original layer abar setDrawableByLayerId diye phire boshano
      - setSecondaryProgressTintList(null) + setSecondaryProgress(0)
      - kono cast nei → crash er shohoj achei na

Redundant-redraw guard (lastGreenBandA/B/Width) intact — performance
unchanged. Background/progress layer chhoya hoy nai, tai normal
seekbar look/behavior ekdom same.

--------------------------------------------------------------------------
4. FILES CHANGED
--------------------------------------------------------------------------
  • MainActivity.java — abOriginalSecondaryLayer, abBandDrawable fields;
                        updateABGreenBand() rework; ABBandDrawable class

Kono existing feature remove/poriborton kora hoy nai.

--------------------------------------------------------------------------
5. VERIFY KORAR STEPS
---------------------------------------------------------------------
  1. Song bajao → A-B repeat set koro → A theke B porsi thin green
     band ✓
  2. AB repeat disable/clear koro → band chole jay, crash nei ✓
  3. Normal seek/scrub kaj kore ✓
  4. Theme dark/light dutoite check ✓
========================================================================
