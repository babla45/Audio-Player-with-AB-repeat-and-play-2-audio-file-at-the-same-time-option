================================================================================
        COMPREHENSIVE CODE AUDIT REPORT — MainActivity.txt (AudPlayer)
================================================================================
File:    MainActivity.txt (10,644 lines)
Date:    2026-09-10
Auditor: Arena.ai Agent Mode
================================================================================

════════════════════════════════════════════════════════════════════════════════
  SECTION 1: IDENTIFIED BUGS
════════════════════════════════════════════════════════════════════════════════

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B001
Severity:    Critical
Confidence:  Confirmed
Category:    Crash / Concurrency
Location:    onDestroy(), lines ~5480-5510
             "if (mediaPlayer != null && !mediaPlayer.isPlaying())"
Problem:
    In onDestroy(), the MediaPlayer is only released if it is NOT currently
    playing. If the player IS playing, releaseMediaPlayer() is skipped entirely.
    This means when the Activity is destroyed while audio is playing (e.g.
    configuration change, swiped from recents), the MediaPlayer is NEVER
    released. The service holds the reference and continues playback, which is
    the intent — BUT the Activity's equalizer, BassBoost, Virtualizer,
    EnvironmentalReverb, echoReverb, and NoiseSuppressor effects are released
    on the SAME session ID (lines ~5515-5517) while the MediaPlayer still
    plays. After the effects are released, re-creating the Activity will call
    ensureEqualizerInitialized() and create NEW effect instances on the same
    audio session, which can conflict with the service's effects (if any) or
    fail with "audio session already controlled" errors.

Trigger:
    1. Start playback of a song.
    2. Rotate the device or trigger an Activity recreation.
    3. The new Activity instance creates new Equalizer/BassBoost/etc. on the
       same audio session, but the old effects were already released mid-stream.
    Result: effects may fail to initialize or the audio glitches.

Expected Behavior:
    When the Activity is destroyed with the player still playing, the
    equalizer/effects should either NOT be released (because the service will
    keep them alive) OR the effects should be safely re-attached.

Current Behavior:
    Effects are unconditionally released while playback continues, causing
    potential audio glitches and initialization failures on recreation.

Root Cause:
    unconditional releaseEqualizerEffects() at the end of onDestroy().

Fix Strategy:
    Guard the releaseEqualizerEffects() call: only release effects if the
    MediaPlayer is also being released (i.e., playback is NOT active). If the
    player keeps playing (because the service owns it), skip effect release so
    the new Activity instance can reuse the same session's effects cleanly.

Regression Risk:
    Low. Only changes cleanup behavior for the destroy-while-playing case.
    Destroy-while-paused is unchanged.

Suggested Test:
    Play a song, rotate the device multiple times, verify effects (EQ, bass,
    reverb) remain applied and no errors appear in logcat.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B002
Severity:    Critical
Confidence:  Highly Likely
Category:    State / Playback
Location:    onDestroy(), lines ~5489-5505
Problem:
    In onDestroy(), when the MediaPlayer IS playing, it is NOT released by the
    Activity — but the Activity unbinds from the service first (line ~5482).
    After unbinding, the service holds the reference. However, the Activity
    also does NOT release its LOCAL reference (mediaPlayer field is never
    nulled). On activity recreation, the NEW Activity instance starts with
    mediaPlayer = null (fresh instance), calls syncFromServiceToUi() to adopt
    the service's player, but if syncFromServiceToUi fails or runs before
    onServiceConnected, the old Activity's reference is orphaned in memory
    (cannot be GC'd until the Activity is collected). This is a minor leak,
    but more critically: if onDestroy for a PAUSED player calls
    releaseMediaPlayer(), the player is released while the service may still
    hold a reference to it (because the service was unbound first, then the
    player was released).

Trigger:
    1. Play a song, then pause it.
    2. Swipe the app from recents (triggers onDestroy).
    3. onDestroy unbinds service first, then releases the MediaPlayer.
    4. If the service still references this player (it was handed over in
       onServiceConnected), the service now holds a released MediaPlayer.

Expected Behavior:
    The service should be told to release its reference before the Activity
    releases the player.

Current Behavior:
    Service may hold a released MediaPlayer, causing IllegalStateException on
    the next service interaction.

Root Cause:
    Order of operations in onDestroy: unbind happens before player release.

Fix Strategy:
    Before unbinding (or as part of the unbind flow), null out the service's
    player references: if (audioService != null) { audioService.setMediaPlayers(null, null, null); }
    Then release the local MediaPlayer. OR: don't release the MediaPlayer when
    the service is bound and will take over playback; only release when the
    service won't restart (e.g., EXIT_APP).

Regression Risk:
    Medium. Changing destroy order could affect background playback
    continuation. Must test that the service still plays after Activity
    recreation.

Suggested Test:
    Play a song → pause → swipe from recents → reopen app → press play.
    Verify no crash and playback resumes correctly.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B003
Severity:    Critical
Confidence:  Confirmed
Category:    Crash / Logic
Location:    handleSongCompletion(), PLAYBACK_MODE_RANDOM branch (playlist path),
             lines ~1808-1820
Problem:
    In the RANDOM mode branch within the playlist completion handler, the code
    directly modifies selectedAudioUri and calls prepareMediaPlayer() inline,
    but does NOT call onAudioFileSelected(). The surrounding non-playlist
    RANDOM branch correctly calls onAudioFileSelected(). The inline code also
    has inconsistent indentation — the shouldAutoPlay and prepareMediaPlayer()
    lines are indented at a different level from the if-block, suggesting they
    may be intended to be outside the if-block.

    The specific issue: if randomPlaylistContextList is empty, the code falls
    through without doing anything, but if it has items, it modifies
    selectedAudioUri directly and calls prepareMediaPlayer(), which is
    inconsistent with the non-playlist path. The adapter is updated
    only if audioAdapter != null, but this update is inside the if-block
    while prepareMediaPlayer is at a different indent level.

Trigger:
    1. Open a playlist.
    2. Set playback mode to Random.
    3. Let a song complete naturally.

Expected Behavior:
    A random song from the playback context should play, with full
    onAudioFileSelected() processing (highlight, history sync, etc.).

Current Behavior:
    Only selectedAudioUri is updated and prepareMediaPlayer() is called
    directly, skipping highlightSelectedAudio, saveNowPlayingPrefs, and
    other side effects of onAudioFileSelected().

Root Cause:
    Missing call to onAudioFileSelected(); the playlist RANDOM completion
    handler was written differently from the non-playlist one.

Fix Strategy:
    Replace the inline selectedAudioUri assignment and prepareMediaPlayer()
    call with: onAudioFileSelected(randomPlaylistContextList.get(randomIndex));

Regression Risk:
    Low. This aligns the playlist-random path with the non-playlist-random path.

Suggested Test:
    Open a playlist → set Random mode → let songs complete → verify correct
    behavior matches non-playlist random mode.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B004
Severity:    High
Confidence:  Confirmed
Category:    Concurrency / State
Location:    Multiple locations — playPauseButton click handler (~line 1105),
             prepareMediaPlayer() (~line 1500), onDestroy() (~line 5480)
Problem:
    The Activity maintains its OWN MediaPlayer reference (mediaPlayer field)
    SEPARATE from the service's reference. When the Activity is created:
    1. It creates a MediaPlayer (initMediaPlayer + prepareMediaPlayer).
    2. It binds to the service.
    3. In onServiceConnected, it passes this MediaPlayer to the service via
       setMediaPlayers().
    4. syncFromServiceToUi() may adopt the service's player instead.

    This creates a window where two different MediaPlayer instances could
    exist for the same audio. If the user presses play before the service
    binds, the Activity uses its own MediaPlayer. When onServiceConnected
    fires, it calls setMediaPlayers() with this player. If the service
    already had its own player from a previous session, there could be TWO
    players momentarily.

    Additionally, in the playPauseButton click handler's catch block
    (IllegalStateException), it calls prepareMediaPlayer() which creates a
    NEW MediaPlayer without releasing the old one first (the old one may
    have been released inside prepareMediaPlayer's reset() path, but if
    initMediaPlayer() is called and the old player was in a weird state,
    it could leak).

Trigger:
    1. Start playback quickly after app launch.
    2. The service binds and adopts the player.
    3. A transient MediaPlayer error occurs.
    4. The catch block calls prepareMediaPlayer() which creates a new player.
    5. The service still holds the old (now dead) player reference.

Expected Behavior:
    Only one active MediaPlayer should exist at any time.

Current Behavior:
    Race conditions can lead to two MediaPlayer instances playing
    simultaneously or the service holding a stale reference.

Root Cause:
    Decoupled MediaPlayer lifecycle between Activity and Service with
    insufficient synchronization.

Fix Strategy:
    Before creating a new MediaPlayer in prepareMediaPlayer(), always
    communicate with the service to update its reference:
    if (serviceBound && audioService != null) {
        audioService.setMediaPlayers(null, null, null);
    }
    After prepareMediaPlayer completes (in onPrepared), hand the new player
    to the service.

Regression Risk:
    Medium. Must ensure the service doesn't lose its player reference
    unexpectedly during normal operation.

Suggested Test:
    Rapidly switch songs while the service is bound. Verify no two songs
    play simultaneously.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B005
Severity:    High
Confidence:  Confirmed
Category:    Crash
Location:    onAudioFileSelected() and prepareMediaPlayer()
Problem:
    When onAudioFileSelected() is called, it calls saveNowPlayingPrefs()
    BEFORE prepareMediaPlayer(). At that point, selectedAudioUri has been
    updated to the NEW track, but the MediaPlayer still holds the OLD track.
    saveNowPlayingPrefs() then saves the OLD player's position (from the
    previous track) but under the NEW track's URI. This corrupts the saved
    position: the next app restart will try to seek the NEW track to the OLD
    track's position, which may be beyond the new track's duration.

    This is partially mitigated by the playerPreparedUri check in
    saveNowPlayingPrefs(), which detects that the player doesn't match the
    selected URI and saves position=0. However, this means the user loses
    their playback position for the old track (which they may have wanted
    to resume later).

Trigger:
    1. Play track A, pause at 2:00.
    2. Select track B from the list.
    3. saveNowPlayingPrefs() fires with selectedAudioUri=B but the player
       still holds track A at 2:00.
    4. If playerPreparedUri check catches it, position=0 is saved for B
       (correct), but track A's position is lost.

Expected Behavior:
    Track A's position should be saved before switching to track B.

Current Behavior:
    Track A's saved position is overwritten by the new track's entry.

Root Cause:
    saveNowPlayingPrefs() is called after selectedAudioUri is changed but
    before the new player is prepared.

Fix Strategy:
    In onAudioFileSelected(), call saveNowPlayingPrefs() BEFORE changing
    selectedAudioUri, to persist the OLD track's state. Then set the new URI
    and proceed with prepareMediaPlayer().

Regression Risk:
    Low. Only changes the ordering of the save call.

Suggested Test:
    Play track A, pause at 1:00, select track B, kill app, reopen, verify
    track A's last position is still accessible (e.g., via Previous).

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B006
Severity:    High
Confidence:  Highly Likely
Category:    Concurrency / Crash
Location:    prepareSecondMediaPlayer(), lines ~5253-5395
Problem:
    After prepareAsync() on the secondMediaPlayer, the code immediately
    (synchronously) checks if the first player is still playing:
    "if (wasPlaying && !isStillPlaying)" and potentially calls
    prepareMediaPlayer() to restore it. However, prepareMediaPlayer() is
    asynchronous — it calls prepareAsync() on a NEW MediaPlayer. This means
    if the first player was accidentally stopped by the second player's
    creation (unlikely but possible on some devices), a full re-prepare
    happens, which resets the UI, clears the seekbar, etc. The user would
    see a brief interruption.

    More critically: if prepareMediaPlayer() is called from this path, it
    sets shouldAutoPlay=false by default (no auto-play flag is set), so
    the song would not resume playing automatically.

Trigger:
    1. Play a song.
    2. Select a second audio file for the mixer.
    3. On some devices, MediaPlayer creation for the second track may
       briefly interfere with the first player.

Expected Behavior:
    The first player should not be affected by the second player's creation.

Current Behavior:
    On some devices, the first player may stop, triggering a full re-prepare
    without auto-play, causing a silent interruption.

Root Cause:
    Defensive check after second player prep that re-prepares the first
    player without preserving playback state.

Fix Strategy:
    If the first player stops unexpectedly, restart it (start()) rather than
    calling prepareMediaPlayer() which does a full re-prepare. Only
    re-prepare if the first player is in an unrecoverable state.

Regression Risk:
    Medium. Need to verify that simply calling start() works in all edge cases.

Suggested Test:
    Play a song → enable mixer → select second file → verify first player
    keeps playing without interruption.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B007
Severity:    High
Confidence:  Confirmed
Category:    Playback / Logic
Location:    setSleepTimer() and timer broadcast receiver, lines ~5128-5250
Problem:
    The timerUpdateReceiver is registered in setSleepTimer() with
    RECEIVER_NOT_EXPORTED, but it's never unregistered until onDestroy().
    If setSleepTimer() is called multiple times (user sets a new timer), the
    check "if (timerUpdateReceiver == null)" prevents re-registration, which
    is correct. However, the timer action (TIMER_ACTION_CLOSE_APP vs
    TIMER_ACTION_PAUSE) is captured by the service at setTimer() time, but
    the receiver's logic also checks timerAction — and timerAction is a
    class field that can be changed between timer set and timer fire. If the
    user changes the timer action in the UI while a timer is running, the
    wrong action may execute.

    Specifically: the service stores the action at set time, but the
    receiver also reads the action from the intent extra. If the service
    broadcasts the action correctly, this is fine. But if there's a
    discrepancy (e.g., service doesn't include the action in the intent),
    the receiver falls back to timerAction which may have changed.

Trigger:
    1. Set a sleep timer with "Close App" action.
    2. While timer is running, open the timer settings and change action to
       "Pause".
    3. When timer fires, the service sends CLOSE_APP (from step 1), but the
       UI's timerAction is now PAUSE.

Expected Behavior:
    The action captured at timer set time should be executed, regardless of
    later UI changes.

Current Behavior:
    The receiver reads the action from the intent (which should be correct),
    but falls back to timerAction if the extra is missing. If the service
    doesn't include TIMER_ACTION in its broadcast, the changed field value
    is used instead.

Root Cause:
    Ambiguous action source: intent extra vs class field.

Fix Strategy:
    Ensure the service ALWAYS includes the timer action in the
    TIMER_FINISHED intent, and the receiver ALWAYS reads from the intent
    extra. Remove the class field fallback.

Regression Risk:
    Low. Making the action source unambiguous.

Suggested Test:
    Set timer with Close App → change action to Pause → wait for timer →
    verify Close App still executes (or vice versa).

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B008
Severity:    High
Confidence:  Confirmed
Category:    State / Playback
Location:    updateUIForPlaybackStopped(), lines ~7440-7470
Problem:
    When PLAYBACK_STOPPED is received (from notification stop), the code calls
    mediaPlayer.reset() and then prepareMediaPlayer(). This is problematic:
    1. prepareMediaPlayer() calls setDataSourceCompat() which sets a new data
       source, but after reset(), the player is in the Initialized state —
       prepareMediaPlayer() expects the player to be in a state where
       setDataSource can be called.
    2. More critically, prepareMediaPlayer() calls requestAudioFocus() first,
       which is wrong — the notification explicitly stopped playback, so
       re-requesting focus and re-preparing defeats the purpose of "stop".
    3. The isPlaying flag is set to false, but prepareMediaPlayer() will
       prepare the same selectedAudioUri again. If shouldAutoPlay is false
       (which it is by default), the player will be prepared but not started.
       This means the user gets a prepared-but-paused player, which is
       confusing — they stopped playback but the player is still alive.

Trigger:
    1. Play a song.
    2. Press the notification's Stop button.
    3. The PLAYBACK_STOPPED broadcast is received.
    4. The player is reset and re-prepared for the same track.

Expected Behavior:
    Stop should stop playback completely and not re-prepare the player.

Current Behavior:
    The player is reset and immediately re-prepared, keeping the track loaded.

Root Cause:
    updateUIForPlaybackStopped() resets and re-prepares instead of simply
    stopping.

Fix Strategy:
    In updateUIForPlaybackStopped(), simply update the UI (icon, progress=0,
    time=0:00) without resetting or re-preparing the player. If a full stop
    is desired, release the MediaPlayer instead.

Regression Risk:
    Medium. Need to verify the notification's "stop" semantics — does stop
    mean "pause and seek to 0" or "release the player"?

Suggested Test:
    Play a song → press notification Stop → reopen app → verify the song
    title is shown but playback is stopped (not re-prepared).

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B009
Severity:    Medium
Confidence:  Confirmed
Category:    Logic / Calculation
Location:    syncSecondPlayerPosition(), lines ~5395-5414
Problem:
    The method calculates the second player's position as a percentage of the
    main player's position relative to its duration, then seeks the second
    player to the same percentage of its duration. This is correct for
    syncing tracks of different lengths. However, there's no check for
    mainDuration <= 0, which would cause a division by zero (resulting in NaN
    or Infinity), leading to an invalid seekTo() call.

Trigger:
    1. Enable mixer mode.
    2. If the primary player reports getDuration() = 0 (which can happen
       briefly during preparation or for certain audio formats).

Expected Behavior:
    Gracefully handle zero/negative duration by skipping the sync.

Current Behavior:
    Division by zero produces NaN/Infinity, which when cast to int produces
    0 or garbage, causing the second player to seek to an unexpected position.

Root Cause:
    Missing guard for mainDuration <= 0.

Fix Strategy:
    Add: if (mainDuration <= 0 || secondDuration <= 0) return;

Regression Risk:
    Negligible.

Suggested Test:
    Sync two tracks where one has an unusual format that reports 0 duration
    initially. Verify no crash or incorrect seek.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B010
Severity:    Medium
Confidence:  Confirmed
Category:    Memory / Resource
Location:    createBlurredBackground(), lines ~3430-3470
Problem:
    The method creates a full-size screenshot bitmap (shot), then a
    downsampled version (small), then a blurred version. While shot is
    recycled after creating small, and small is recycled after blurring,
    the full-size shot creation (decor.getWidth() × decor.getHeight()) can
    be very large on high-resolution screens (e.g. 1440×3200 = 18 MB ARGB_8888).
    If the user opens the mixer/options dialog frequently, this allocation
    happens each time and may cause GC pressure or OOM on low-memory devices.

Trigger:
    1. On a high-resolution device, repeatedly open the mixer dialog.

Expected Behavior:
    Efficient memory usage for the blur effect.

Current Behavior:
    Full-resolution bitmap allocated on every dialog open.

Root Cause:
    Screenshot captured at full resolution before downsampling.

Fix Strategy:
    Capture at the reduced scale directly using a scaled Canvas or use
    RenderScript/Toolkit blur for more efficient processing. Alternatively,
    cache the blurred bitmap and invalidate it only when the view hierarchy
    changes.

Regression Risk:
    Low. This is a performance optimization, not a logic change.

Suggested Test:
    Open the mixer dialog on a high-resolution device, monitor memory
    allocation in Android Profiler.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B011
Severity:    Medium
Confidence:  Confirmed
Category:    State
Location:    syncFromServiceToUi(), lines ~517-555
Problem:
    syncFromServiceToUi() is called from onServiceConnected(). It checks
    if mediaPlayer == null and svcMain != null, and if so, adopts the
    service's player. However, it does NOT check if the adopted player
    matches the currently selectedAudioUri. If the service is playing a
    different track (e.g., from a notification-initiated next/prev that
    the Activity missed), the Activity will adopt that player but still
    display the old track's metadata until the next UI update.

    Additionally, after adopting the player, it calls
    restoreNowPlayingPrefsToUi() which may OVERWRITE the title/metadata
    from the service with stale preference data.

Trigger:
    1. Play track A.
    2. Kill the Activity (not the service) via memory pressure.
    3. Use notification to skip to track B.
    4. Activity is recreated and binds to the service.
    5. syncFromServiceToUi adopts the player playing track B, but
       restoreNowPlayingPrefsToUi may show track A's title from prefs.

Expected Behavior:
    The UI should reflect the service's current state (track B).

Current Behavior:
    The UI may briefly show track A's metadata before correcting.

Root Cause:
    restoreNowPlayingPrefsToUi() runs after the service state is adopted
    and may overwrite with stale data.

Fix Strategy:
    In syncFromServiceToUi(), after adopting the service's player, also
    update selectedAudioUri from the service (the service should expose
    the current track URI). Skip restoreNowPlayingPrefsToUi() when the
    service provides fresh state.

Regression Risk:
    Medium. The ordering of restore vs service-sync affects the first-
    render experience.

Suggested Test:
    Play track A → use notification to skip to track B → force-stop the
    Activity (adb shell am kill) → reopen → verify track B is shown.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B012
Severity:    Medium
Confidence:  Confirmed
Category:    State / UI
Location:    setupEndOfSongTimer(), lines ~5195-5240
Problem:
    The "end of song" timer replaces the MediaPlayer's OnCompletionListener
    with a one-shot listener that pauses on completion, then restores the
    original listener. However:
    1. The original listener is captured as a lambda "mp ->
       handleSongCompletion()" — this is a NEW lambda, not the actual
       registered listener. If the completion listener was changed elsewhere
       (e.g., by prepareMediaPlayer), this captured reference is stale.
    2. The one-shot listener does NOT call handleSongCompletion(). So when
       the song ends, instead of proceeding to the next song, it just pauses.
       This is the intended behavior for "end of song" timer. But if the
       user cancels the timer and the one-shot listener is still registered
       (cancel doesn't restore the original), the next song completion will
       NOT trigger handleSongCompletion().
    3. cancelSleepTimer() does NOT restore the original completion listener.
       So after canceling the end-of-song timer, the next song completion
       will use the one-shot listener (which pauses and restores), but only
       once — if the user had set a new completion listener between cancel
       and the next song end, it would be lost.

Trigger:
    1. Set "end of song" timer.
    2. Cancel the timer via the timer bottom sheet.
    3. Let the current song play to completion.
    4. Instead of handleSongCompletion() (which navigates to next song),
       the one-shot listener fires: it pauses and restores the "original"
       listener (which is a fresh lambda, not the one set by
       prepareMediaPlayer).

Expected Behavior:
    After canceling the timer, normal completion behavior (next/prev/random)
    should work.

Current Behavior:
    After cancel, the first completion fires the one-shot listener (pauses
    instead of going to next), then subsequent completions use the captured
    lambda which may not match the current listener.

Root Cause:
    The end-of-song listener replacement doesn't integrate with the normal
    lifecycle of the completion listener.

Fix Strategy:
    Use a flag (e.g., endOfSongTimerActive) that the existing
    handleSongCompletion() checks. If the flag is set, pause instead of
    advancing. The completion listener is never replaced. Canceling the
    timer simply clears the flag.

Regression Risk:
    Low. This simplifies the implementation.

Suggested Test:
    Set end-of-song timer → cancel → let song finish → verify next song
    plays normally.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B013
Severity:    Medium
Confidence:  Confirmed
Category:    Crash / NullPointerException
Location:    getFileNameFromUri(), line ~1495
Problem:
    The method calls uri.getScheme() without null-checking the scheme. If
    the URI has no scheme (e.g., a raw file path like "/sdcard/file.mp3"),
    uri.getScheme() returns null, and uri.getScheme().equals("content")
    throws NullPointerException.

Trigger:
    1. The app receives a URI without a scheme (e.g., from a share intent
       or a corrupted saved preference).

Expected Behavior:
    Gracefully handle URIs without a scheme.

Current Behavior:
    NullPointerException crash.

Root Cause:
    Missing null check on uri.getScheme().

Fix Strategy:
    Use "content".equals(uri.getScheme()) instead of
    uri.getScheme().equals("content").

Regression Risk:
    Negligible.

Suggested Test:
    Pass a URI without a scheme (e.g., Uri.parse("/sdcard/test.mp3")) and
    verify no crash.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B014
Severity:    Medium
Confidence:  Confirmed
Category:    UI / Interaction
Location:    bottomNavigation.setOnItemSelectedListener, line ~1040
Problem:
    The bottom navigation's listener returns `false` for every item. This
    means no item ever becomes selected/checked (which is the stated
    intent). However, returning `false` also means the navigation item's
    ripple/highlight animation doesn't trigger properly on some devices.
    Combined with clearBottomNavSelectionSoon() in onResume(), there's a
    brief flash where the item IS selected before being cleared.

    More importantly, the "More" option (nav_more) opens the bottom sheet
    menu every time it's tapped, but doesn't prevent rapid double-taps
    from opening two instances. The isBottomSheetMenuVisible() check in
    showBottomSheetMenu() mitigates this, but there's a small window
    between the tap and the fragment becoming visible where a second tap
    can get through.

Trigger:
    1. Rapidly double-tap the "More" bottom nav item.

Expected Behavior:
    Only one bottom sheet menu opens.

Current Behavior:
    On very fast double-taps, two menu instances may briefly coexist.

Root Cause:
    Race between tap handling and fragment visibility check.

Fix Strategy:
    Add a debounce flag (e.g., menuOpenInProgress) that's set on tap and
    cleared when the sheet is shown/dismissed.

Regression Risk:
    Negligible.

Suggested Test:
    Rapidly tap More button 10 times → verify only one menu is shown.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B015
Severity:    Medium
Confidence:  Highly Likely
Category:    State / Playback
Location:    applySavedMixerPreset(), lines ~4950-5050
Problem:
    When applying a saved mixer preset, the code sets shouldAutoPlay = true
    and calls prepareMediaPlayer() and prepareSecondMediaPlayer(). Both are
    asynchronous (prepareAsync). However, secondAudioActive is set to true
    BEFORE prepareSecondMediaPlayer's onPrepared callback fires. This means
    any code that checks secondAudioActive between the call and the callback
    will think the second player is ready when it isn't. For example,
    startSecondTrackIfMixerReady() checks secondAudioActive and tries to
    start the second player, but the second player may not be prepared yet.

Trigger:
    1. Apply a saved mixer preset.
    2. The primary player prepares quickly and starts playing.
    3. onPrepared for the primary player calls startSecondTrackIfMixerReady().
    4. secondAudioActive is true but the second player isn't prepared.
    5. secondMediaPlayer.isPlaying() or start() throws IllegalStateException.

Expected Behavior:
    The second player should only be started after it's fully prepared.

Current Behavior:
    Potential IllegalStateException when trying to start an unprepared second
    player.

Root Cause:
    secondAudioActive is set before the second player's onPrepared fires.

Fix Strategy:
    Don't set secondAudioActive = true until the second player's
    onPrepared callback fires (which already happens inside
    prepareSecondMediaPlayer). Remove the premature assignment in
    applySavedMixerPreset().

Regression Risk:
    Low. The onPrepared callback already sets this flag.

Suggested Test:
    Apply a saved mixer preset with two different tracks → verify both
    start playing without errors.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B016
Severity:    Medium
Confidence:  Confirmed
Category:    Resource / Leak
Location:    BroadcastReceiver registrations in onCreate(), lines ~430-490
Problem:
    The timerUpdateReceiver is conditionally registered in setSleepTimer()
    (only when timerUpdateReceiver == null). However, the other receivers
    (playbackStoppedReceiver, closeAppReceiver, mediaControlsReceiver,
    pausedStateReceiver) are all registered in onCreate() and unregistered
    in onDestroy(). If the Activity is recreated (configuration change),
    onDestroy fires and unregisters them, then onCreate fires and
    re-registers them. This is correct.

    However, there's a subtle issue: pausedStateReceiver is stored as a
    local variable initially (BroadcastReceiver pausedReceiver = ...) and
    then assigned to the field pausedStateReceiver. The registration uses
    the local variable. If the code were to check pausedStateReceiver != null
    before registering (to avoid double registration), it would work
    correctly. But as written, there's no such check, so on recreation the
    old receiver is unregistered (correct) and a new one is registered
    (correct). This seems fine.

    The actual issue is with the mediaControlsReceiver: it's registered with
    RECEIVER_NOT_EXPORTED, meaning only the app's own broadcasts can trigger
    it. However, the service broadcasts "MEDIA_NEXT", "MEDIA_PREV", etc.
    using sendBroadcast() without specifying the package. On Android 14+,
    implicit broadcasts may be restricted. If the service uses
    LocalBroadcastManager instead, the RECEIVER_NOT_EXPORTED registration
    won't receive them. This needs verification against the service code.

Trigger:
    1. On Android 14+, tap notification Next/Previous.
    2. The service sends MEDIA_NEXT broadcast.
    3. The Activity's receiver may not receive it due to export restrictions.

Expected Behavior:
    Notification controls should work on all Android versions.

Current Behavior:
    Potential failure on Android 14+ depending on how the service sends
    broadcasts.

Root Cause:
    RECEIVER_NOT_EXPORTED combined with potentially non-local broadcasts.

Fix Strategy:
    Verify that the service sends broadcasts with explicit package targeting
    (intent.setPackage(getPackageName())) or use LocalBroadcastManager
    consistently. If the service already targets the package, this is not
    a bug.

Regression Risk:
    Low.

Suggested Test:
    On Android 14+, verify notification Next/Previous buttons work correctly.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B017
Severity:    Medium
Confidence:  Confirmed
Category:    UI / Logic
Location:    updateABGreenBand(), lines ~2220-2310
Problem:
    The A-B green band uses seekBar.setSecondaryProgress(pointB). However,
    secondary progress on a SeekBar represents a value from 0 to max, not
    a visual range from A to B. Setting secondaryProgress to pointB shows
    progress from 0 to pointB, not from pointA to pointB. The green band
    would cover the range 0→B, not A→B, making it misleading.

Trigger:
    1. Set A-B repeat with pointA at 30s and pointB at 60s.
    2. The green band shows from 0 to 60s instead of 30s to 60s.

Expected Behavior:
    The green band should visually highlight only the A→B range.

Current Behavior:
    The green band shows from 0 to B, not A to B.

Root Cause:
    Android's SeekBar secondaryProgress goes from 0 to the set value. There's
    no native support for a "from A to B" range.

Fix Strategy:
    Use a custom overlay/drawable or a LayerDrawable approach that positions
    the green segment correctly. Alternatively, shift the secondary progress
    by adjusting the drawable's clip bounds, or use a separate View overlay
    positioned from A to B.

Regression Risk:
    Medium. Visual changes to the seekbar need careful testing across themes.

Suggested Test:
    Set A at 30s and B at 60s on a 3-minute track. Verify the green band
    appears only between 30s and 60s.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B018
Severity:    Low
Confidence:  Confirmed
Category:    State
Location:    handleSongCompletion(), REPEAT_CURRENT mode, lines ~1770 and ~1840
Problem:
    In REPEAT_CURRENT mode, when the song completes, the code calls
    mediaPlayer.seekTo(0) and mediaPlayer.start(). However, it does NOT:
    1. Reset the second player to position 0 (if mixer is active).
    2. Update isPlaying = true.
    3. Update the UI icon to pause.
    4. Call updateSeekBar().
    5. Call updateMiniPlayer().
    This means the UI shows the "play" icon while the player is actually
    playing, and the seekbar doesn't update.

Trigger:
    1. Set playback mode to "Repeat Current Song".
    2. Let the song play to completion.

Expected Behavior:
    Song restarts from 0, UI shows pause icon, seekbar updates.

Current Behavior:
    Song restarts but UI shows play icon, seekbar doesn't move.

Root Cause:
    Missing UI update calls in the REPEAT_CURRENT completion handler.

Fix Strategy:
    After mediaPlayer.start(), add:
    isPlaying = true;
    safeSetImageResource(playPauseButton, R.drawable.ic_pause_improved);
    updateSeekBar();
    updateMiniPlayer();
    // Also sync second player if mixer is active

Regression Risk:
    Negligible.

Suggested Test:
    Set repeat current → let song finish → verify UI shows pause icon and
    seekbar moves.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B019
Severity:    Low
Confidence:  Confirmed
Category:    UI / UX
Location:    showHowToUseDialog(), navigate click listener, line ~7660
Problem:
    The navigate click listener compares v.getId() with R.id.howToUsePrevMatch
    to determine direction. However, both buttons are wired with the SAME
    click listener. If the listener is set on both buttons, clicking the
    "Next" button (which is not howToUsePrevMatch) will correctly increment.
    This seems correct.

    The actual issue is that if the search box has no matches (matches list
    is empty), the prev/next buttons are disabled (updateHowToUseNavButtons
    sets enabled=false). However, the buttons still have the click listener.
    If they're somehow clicked while disabled (unlikely but possible with
    accessibility services), the code checks "if (matches.isEmpty()) return"
    which handles it. This is fine.

    Minor issue: the afterTextChanged callback runs on every keystroke,
    which calls findHowToUseMatches → renderHowToUseMatches. For a large
    help text with many TextViews, this could cause jank on slower devices.

Trigger:
    N/A (performance, not a bug per se)

Expected Behavior:
    Smooth search in the help dialog.

Current Behavior:
    May be slow with very long help text on older devices.

Root Cause:
    Full re-render on every keystroke without debouncing.

Fix Strategy:
    Add a small debounce (e.g., 150ms) to the search text watcher.

Regression Risk:
    Negligible.

Suggested Test:
    Type quickly in the How To Use search box on an older device.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B020
Severity:    Low
Confidence:  Confirmed
Category:    UI
Location:    clearBottomNavSelectionSoon() vs clearBottomNavSelectionSoon2()
Problem:
    There are TWO methods for clearing the bottom nav selection:
    clearBottomNavSelectionSoon() and clearBottomNavSelectionSoon2(). The
    "2" variant uses postDelayed with 0ms and doesn't toggle
    setGroupCheckable. The primary variant uses post() and does toggle
    setGroupCheckable. Only the primary variant is called. The "2" variant
    is dead code.

Trigger:
    N/A (dead code)

Expected Behavior:
    Only one clearing method exists.

Current Behavior:
    clearBottomNavSelectionSoon2() is unused dead code.

Root Cause:
    Leftover from development iteration.

Fix Strategy:
    Remove clearBottomNavSelectionSoon2().

Regression Risk:
    Negligible.

Suggested Test:
    Compile and verify no references to the removed method.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B021
Severity:    Low
Confidence:  Confirmed
Category:    UI / UX
Location:    checkRadioByTag(), line ~8830
Problem:
    The method casts child.getTag() to (int) without a null check or
    instance check. If a RadioButton in the RadioGroup doesn't have a tag
    set (or has a non-Integer tag), this will throw NullPointerException
    or ClassCastException.

Trigger:
    1. A RadioButton is added to the RadioGroup without a tag.

Expected Behavior:
    Gracefully skip RadioButtons without integer tags.

Current Behavior:
    Potential ClassCastException.

Root Cause:
    Unsafe cast of tag to int.

Fix Strategy:
    Add: if (child.getTag() instanceof Integer && value == (int) child.getTag())

Regression Risk:
    Negligible.

Suggested Test:
    Add a RadioButton without a tag to the theme dialog's RadioGroup.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B022
Severity:    Low
Confidence:  Confirmed
Category:    Persistence / Data
Location:    savePlaybackMode() / loadPlaybackMode(), lines ~2650-2680
Problem:
    These methods use the hardcoded string "audio_player_prefs" instead of
    the class constant PREFS_NAME. While PREFS_NAME has the same value
    ("audio_player_prefs"), using the hardcoded string is inconsistent and
    error-prone if the constant's value is ever changed. There are also
    several other locations that use the hardcoded string (e.g.,
    toggleMixerMode line ~7060, applySavedMixerPreset line ~5020).

Trigger:
    N/A (consistency issue, not a runtime bug)

Expected Behavior:
    All SharedPreferences access should use PREFS_NAME.

Current Behavior:
    Some methods use the hardcoded string.

Fix Strategy:
    Replace all "audio_player_prefs" string literals with PREFS_NAME.

Regression Risk:
    Negligible.

Suggested Test:
    Search for "audio_player_prefs" string literals and verify all use
    PREFS_NAME.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B023
Severity:    Low
Confidence:  Confirmed
Category:    UI / UX
Location:    seekRelative(), line ~7500
Problem:
    The method returns early if !mediaPlayer.isPlaying(). This means
    seeking backward/forward is impossible while playback is paused. Users
    may want to seek while paused to find a specific position.

Trigger:
    1. Pause playback.
    2. Tap the seek forward/backward button.
    3. Nothing happens.

Expected Behavior:
    Seek should work while paused (update position without starting playback).

Current Behavior:
    Seek is silently ignored when paused.

Root Cause:
    The isPlaying() check prevents seeking while paused.

Fix Strategy:
    Remove the isPlaying() check. Only require mediaPlayer != null.

Regression Risk:
    Negligible.

Suggested Test:
    Pause playback → tap seek forward → verify the seekbar and time update.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B024
Severity:    Low
Confidence:  Confirmed
Category:    UI / UX
Location:    expandSearch(), line ~6931
Problem:
    When collapsing the search (expand = false), the method calls
    searchEditText.setText("") which clears the query. This also triggers
    the TextWatcher's onTextChanged with s.length() == 0, which calls
    filterAudioFiles("") — effectively restoring the full list. This is
    the intended behavior. However, if the user had typed a query and
    then scrolled the list (which triggers outside-touch collapse), the
    full list is restored even though the user may have wanted to keep
    the filtered results.

Trigger:
    1. Open search, type a query to filter the list.
    2. Scroll the RecyclerView (outside the search bar).
    3. The search collapses and the query is cleared.

Expected Behavior:
    If a query is typed, the search should NOT auto-collapse on scroll.
    Only closing via the clear button or back should clear the query.

Current Behavior:
    Scrolling outside the search bar collapses it and clears the query.

Root Cause:
    handleSearchCollapseOnOutsideTouch() only checks
    searchEditText.length() > 0 to prevent collapse — but the touch
    handling in dispatchTouchEvent calls this before the RecyclerView
    consumes the event, so the collapse happens even while scrolling
    results.

    Actually, looking at the code more carefully: handleSearchCollapseOnOutsideTouch
    does check "if (searchEditText != null && searchEditText.length() > 0)
    return;" at the top, so it should NOT collapse when there's text. This
    means the behavior is actually correct. Disregard this bug.

Fix Strategy:
    N/A — the code already handles this correctly.

Regression Risk:
    N/A.

Suggested Test:
    Type a query → scroll the results → verify the search bar stays open.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B025
Severity:    Low
Confidence:  Confirmed
Category:    UX
Location:    showRenameFileDialog(), lines ~6180-6195
Problem:
    The rename dialog captures the positive button click in the
    setPositiveButton lambda. The EditText value is read inside the lambda.
    However, AlertDialog's setPositiveButton automatically dismisses the
    dialog. If the rename fails (e.g., permission denied, file in use), the
    dialog is already dismissed and the user has to reopen it. There's no
    way to prevent dismissal on validation failure with AlertDialog.

Trigger:
    1. Try to rename a file without write permission.
    2. The dialog dismisses, the rename fails silently (or shows a toast).

Expected Behavior:
    The dialog should stay open if validation fails, allowing the user to
    correct the input.

Current Behavior:
    Dialog dismisses regardless of rename success/failure.

Root Cause:
    AlertDialog's default dismiss-on-positive-button behavior.

Fix Strategy:
    Override the button's click listener after show() to conditionally
    dismiss, or use a custom Dialog with manual dismiss control.

Regression Risk:
    Low.

Suggested Test:
    Open rename dialog → enter an invalid name → verify dialog stays open
    with an error message.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B026
Severity:    Low
Confidence:  Possible / Needs Verification
Category:    Playback / Logic
Location:    onPrepared callback in prepareMediaPlayer(), around line 1600
Problem:
    In the onPrepared callback, after calling mp.start() when shouldAutoPlay
    is true, the code also calls startSecondTrackIfMixerReady(). This method
    checks if the second player exists and is prepared, then starts it.
    However, the second player's position is synced via
    syncSecondPlayerPosition(), which calculates a percentage-based position.
    If the primary player was just seeked to pendingRestoreSeekMs, the
    second player is synced to that position. This is correct.

    The potential issue is that if the primary player's duration is 0 at
    this point (which shouldn't happen after onPrepared, but some edge-case
    formats might report 0), the division in syncSecondPlayerPosition()
    would divide by zero.

Trigger:
    1. Restore a saved position with mixer active.
    2. If the primary player reports duration=0 briefly.

Expected Behavior:
    No division by zero.

Current Behavior:
    Depends on whether getDuration() can return 0 after onPrepared.

Root Cause:
    Same as B009 — missing guard in syncSecondPlayerPosition().

Fix Strategy:
    Same as B009.

Regression Risk:
    Negligible.

Suggested Test:
    Same as B009.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B027
Severity:    Low
Confidence:  Confirmed
Category:    Code Quality
Location:    Multiple locations
Problem:
    The file contains several instances of:
    1. Methods that are defined but never called (dead code):
       - clearBottomNavSelectionSoon2() (line ~1008)
       - browseAudioFiles() (line ~5580) — uses deprecated startActivityForResult
       - showPopupMenu() (line ~2310) — redirects to showBottomSheetMenu
       - showSortMenu() (line ~5930) — replaced by SortBottomSheet
       - showABRepeatMenu() (line ~6000) — replaced by ABRepeatBottomSheet
    2. The file is 10,644 lines — extremely large for a single Activity.
       Many features (audio effects, mixer, themes, color picker, etc.)
       could be extracted into separate classes.

Trigger:
    N/A (code quality, not a runtime bug)

Expected Behavior:
    Clean, maintainable codebase.

Current Behavior:
    Dead code and a monolithic Activity class.

Fix Strategy:
    Remove dead code. Consider extracting major features into separate classes
    (AudioEffectsManager, MixerManager, ThemeManager, etc.).

Regression Risk:
    Low for dead code removal. Higher for extraction refactoring.

Suggested Test:
    Compile after removal and verify no regressions.

────────────────────────────────────────────────────────────────────────────────
Bug ID:      B028
Severity:    Low
Confidence:  Confirmed
Category:    UI
Location:    getPitchListener() — voiceVolumeBoostChanged callback, line ~3200
Problem:
    The onVolumeBoostChanged callback in the pitch listener constrains boost
    to 1.0f–3.0f, while the BoostBottomSheet (showBoostBottomSheet) likely
    has a different range (the setBoostListener's onBoostChanged doesn't
    clamp). If the user sets boost from the pitch sheet and then opens the
    boost sheet, the displayed value may be outside the boost sheet's range.

Trigger:
    1. Set volume boost to 3.0 from the voice changer sheet.
    2. Open the boost bottom sheet.
    3. The boost sheet may show an out-of-range value.

Expected Behavior:
    Both UIs should show consistent boost ranges.

Current Behavior:
    The voice changer constrains to 1.0–3.0, but the boost sheet may not.

Root Cause:
    Inconsistent boost range definitions between the two UIs.

Fix Strategy:
    Use a single constant for the boost range and apply it in both locations.

Regression Risk:
    Negligible.

Suggested Test:
    Set boost to 3.0 from voice changer → open boost sheet → verify range.


════════════════════════════════════════════════════════════════════════════════
  SECTION 2: POTENTIALLY RISKY / NEEDS VERIFICATION
════════════════════════════════════════════════════════════════════════════════

────────────────────────────────────────────────────────────────────────────────
Risk ID:     R001
Category:    Concurrency
Location:    Handler/postDelayed chain in updateSeekBar()
Problem:
    updateSeekBar() recursively posts itself via handler.postDelayed(runnable,
    100). The runnable field is shared — if any other code writes to the
    runnable field, the old runnable reference is lost and can't be removed
    in onDestroy. The code does handler.removeCallbacks(runnable) in
    onDestroy, but only for the LAST assigned runnable. If updateSeekBar()
    was called multiple times (which it is, from onPrepared and other
    places), each call reassigns the runnable field, and the previous
    runnable reference is lost. However, since handler.removeCallbacks(runnable)
    uses the object reference, it can only remove the LAST one. Any earlier
    runnable that was posted but not yet executed would still fire.

    In practice, since each updateSeekBar() call removes the old runnable
    by posting a new one that replaces it in the handler queue (actually no,
    it doesn't — it just posts a NEW runnable), multiple updateSeekBar()
    calls could cause multiple concurrent update chains running in parallel,
    each updating the seekbar every 100ms.

    Actually, looking more carefully: the runnable is a field, and each
    updateSeekBar() call assigns a new lambda to it. The handler.postDelayed
    posts the CURRENT value of the runnable field. So if updateSeekBar()
    is called twice, there are TWO runnables in the handler queue, both
    pointing to the same lambda (the second one). When the first fires,
    it calls updateSeekBar() again, which posts a third. This could
    accumulate if multiple entry points call updateSeekBar() simultaneously.

    The mitigation is that each call to updateSeekBar() starts with
    "if (mediaPlayer != null && mediaPlayer.isPlaying())" — if not playing,
    it doesn't post. So in theory, only one chain runs at a time. But there's
    a window where two chains can overlap.

What to test:
    Call updateSeekBar() from multiple entry points (onPrepared, play button,
    seek completion) and verify no duplicate chains accumulate.

────────────────────────────────────────────────────────────────────────────────
Risk ID:     R002
Category:    Lifecycle
Location:    BroadcastReceiver for phone state (PhoneStateReceiver)
Problem:
    The PhoneStateReceiver is registered in onCreate() and unregistered in
    onDestroy(). On Android 13+, READ_PHONE_STATE permission is required
    for ACTION_PHONE_STATE_CHANGED. The code does NOT request this
    permission. On Android 13+, the receiver may silently fail to receive
    phone state changes, meaning music won't pause on incoming calls.

What to test:
    On Android 13+, receive an incoming call while music is playing → verify
    music pauses. If it doesn't, READ_PHONE_STATE permission is needed.

────────────────────────────────────────────────────────────────────────────────
Risk ID:     R003
Category:    Playback
Location:    vibrato tick handler (voiceVibratoRunnable)
Problem:
    The vibrato effect is applied by periodically calling
    applyVoiceSettingsWithPitchModulation() with a sine-wave modulation.
    This calls applyPlaybackParams() which sets the player's PlaybackParams.
    If the modulation frequency is high (e.g., every 50ms) and
    setPlaybackParams() is slow on some devices, this could cause audio
    glitches or ANR. The exact post delay is not visible in the excerpts,
    but the code uses handler.post() (immediate) with no visible delay
    between ticks.

What to test:
    Enable vibrato on a slow device and listen for audio glitches.

────────────────────────────────────────────────────────────────────────────────
Risk ID:     R004
Category:    State
Location:    handleSongCompletion() — PLAYBACK_MODE_RANDOM in else branch
Problem:
    In the non-playlist RANDOM branch, a do-while loop picks a random index
    until it differs from the current index. If the context list has only 1
    song, the do-while would loop infinitely (randomIndex always equals idx
    because idx=0 and randomIndex can only be 0).

    However, the code has: "while (randomPlaylistContextList.size() > 1 &&
    ...)" — the "size > 1" check prevents the infinite loop. So this is
    actually handled correctly.

What to test:
    Verify behavior with a 1-song list in random mode.

────────────────────────────────────────────────────────────────────────────────
Risk ID:     R005
Category:    Persistence
Location:    loadSavedMixerPresets() backward compatibility
Problem:
    The method tries to parse as JSONArray first, then falls back to
    JSONObject. If the stored string is neither valid JSON array nor object
    (e.g., corrupted data), it silently returns an empty list. This is
    acceptable degradation, but the user loses their saved presets with no
    warning.

What to test:
    Corrupt the PREFS_MIXER_PRESETS_KEY preference value → open saved mixer
    dialog → verify graceful empty state.

────────────────────────────────────────────────────────────────────────────────
Risk ID:     R006
Category:    UI / Crash
Location:    showRgbColorPickerDialog() — satValPanel touch listener
Problem:
    The satValPanel's touch listener updates HSV values on every ACTION_MOVE
    and calls refreshPickerUi(), which updates multiple EditText fields. This
    triggers their TextWatchers, which in turn update the HSV values and call
    refreshPickerUi() again. The programmaticUpdate flag prevents this
    re-entrant loop. However, if the flag is not properly set/restored (e.g.,
    due to an exception in refreshPickerUi), the loop could run infinitely.

What to test:
    Rapidly drag the color picker's saturation/value panel and verify no ANR
    or UI freeze.

────────────────────────────────────────────────────────────────────────────────
Risk ID:     R007
Category:    Lifecycle
Location:    closeAppNow() — finishAffinity() + finishAndRemoveTask()
Problem:
    closeAppNow() calls both finishAffinity() and finishAndRemoveTask().
    finishAffinity() finishes all activities in the task, while
    finishAndRemoveTask() removes the task from recents. Calling both is
    redundant — finishAndRemoveTask() already finishes the activity. On some
    devices, calling finishAffinity() first may trigger onDestroy() which
    unbinds the service, then finishAndRemoveTask() may try to finish again.

What to test:
    Verify exit app works correctly on multiple Android versions without
    crashes in onDestroy.


════════════════════════════════════════════════════════════════════════════════
  SECTION 3: BUG SUMMARY TABLE
════════════════════════════════════════════════════════════════════════════════

| Bug ID | Severity | Confidence    | Category      | Location                       | Short Description                                    | Fix Priority |
|--------|----------|---------------|---------------|--------------------------------|------------------------------------------------------|--------------|
| B001   | Critical | Confirmed     | Crash/Res     | onDestroy()                    | Effects released while player still playing           | 1            |
| B002   | Critical | Highly Likely | State/Play    | onDestroy()                    | Service holds released player after unbind            | 2            |
| B003   | Critical | Confirmed     | Crash/Logic   | handleSongCompletion()         | Playlist RANDOM skips onAudioFileSelected             | 3            |
| B004   | High     | Confirmed     | Concurrency   | Multiple locations             | Dual MediaPlayer instances possible                   | 4            |
| B005   | High     | Confirmed     | Crash         | onAudioFileSelected()          | saveNowPlayingPrefs with wrong track's URI            | 5            |
| B006   | High     | Highly Likely | Concurrency   | prepareSecondMediaPlayer()     | First player may stop during second player creation   | 6            |
| B007   | High     | Confirmed     | Playback      | setSleepTimer() timer receiver | Timer action ambiguity between service and UI         | 7            |
| B008   | High     | Confirmed     | State/Play    | updateUIForPlaybackStopped()   | Stop re-prepares player instead of stopping           | 8            |
| B009   | Medium   | Confirmed     | Logic         | syncSecondPlayerPosition()     | Division by zero when mainDuration is 0               | 9            |
| B010   | Medium   | Confirmed     | Memory        | createBlurredBackground()      | Full-resolution bitmap on every dialog open           | 10           |
| B011   | Medium   | Confirmed     | State         | syncFromServiceToUi()          | Stale prefs overwrite service state                   | 11           |
| B012   | Medium   | Confirmed     | Playback      | setupEndOfSongTimer()          | Completion listener not properly managed              | 12           |
| B013   | Medium   | Confirmed     | Crash/NPE     | getFileNameFromUri()           | Null scheme causes NPE                                | 13           |
| B014   | Medium   | Confirmed     | UI            | bottomNavigation listener      | Double-tap opens two menus                            | 14           |
| B015   | Medium   | Highly Likely | State/Play    | applySavedMixerPreset()        | secondAudioActive set before second player prepared    | 15           |
| B016   | Medium   | Confirmed     | Resource      | BroadcastReceiver registrations| RECEIVER_NOT_EXPORTED may block service broadcasts    | 16           |
| B017   | Medium   | Confirmed     | UI/Logic      | updateABGreenBand()            | Green band shows 0→B instead of A→B                   | 17           |
| B018   | Low      | Confirmed     | State         | handleSongCompletion()         | REPEAT_CURRENT missing UI update                      | 18           |
| B019   | Low      | Confirmed     | UI/UX         | showHowToUseDialog()           | No debounce on search keystroke                       | 19           |
| B020   | Low      | Confirmed     | UI            | clearBottomNavSelectionSoon2() | Dead code                                             | 20           |
| B021   | Low      | Confirmed     | UI            | checkRadioByTag()              | Unsafe cast of tag to int                             | 21           |
| B022   | Low      | Confirmed     | Persistence   | savePlaybackMode()             | Hardcoded prefs name instead of constant              | 22           |
| B023   | Low      | Confirmed     | UI/UX         | seekRelative()                 | Can't seek while paused                               | 23           |
| B025   | Low      | Confirmed     | UI/UX         | showRenameFileDialog()         | Dialog dismisses on validation failure                | 24           |
| B026   | Low      | Needs Verify  | Playback      | onPrepared callback            | Potential div-by-zero in second player sync           | 25           |
| B027   | Low      | Confirmed     | Code Quality  | Multiple locations             | Dead code and monolithic class                        | 26           |
| B028   | Low      | Confirmed     | UI            | getPitchListener() boost       | Inconsistent boost range between UIs                  | 27           |


════════════════════════════════════════════════════════════════════════════════
  SECTION 4: RECOMMENDED FIX ORDER
════════════════════════════════════════════════════════════════════════════════

Fix 1: B001 + B002 (onDestroy lifecycle — CRITICAL)
  What:     Guard effect release and player release in onDestroy() based on
            whether playback continues via the service. Clear service
            references before releasing the local player.
  Why:      These are the most dangerous bugs — they affect every Activity
            recreation and app close.
  Retest:   Background playback, Activity recreation, app close/reopen,
            notification controls.
  Verify:   logcat shows no "IllegalStateException" from MediaPlayer or
            audio effects.

Fix 2: B003 (Playlist RANDOM completion handler)
  What:     Replace inline code with onAudioFileSelected() call.
  Why:      Causes inconsistent behavior in playlist random mode.
  Retest:   Playlist playback in all three modes (repeat, next, random).
  Verify:   Random playlist completion behaves identically to non-playlist
            random.

Fix 3: B004 (Dual MediaPlayer prevention)
  What:     Always clear service player references before creating a new
            MediaPlayer in prepareMediaPlayer().
  Why:      Can cause two songs playing simultaneously.
  Retest:   Rapid song switching, notification controls while switching.
  Verify:   Only one song plays at any time.

Fix 4: B005 (saveNowPlayingPrefs ordering)
  What:     Move saveNowPlayingPrefs() call before selectedAudioUri change.
  Why:      Prevents position data corruption.
  Retest:   Switch songs → kill app → reopen → verify saved state.
  Verify:   Each track's position is correctly preserved.

Fix 5: B008 (updateUIForPlaybackStopped)
  What:     Don't re-prepare the player on stop. Just update UI.
  Why:      Stop should stop, not prepare.
  Retest:   Play → notification stop → reopen app.
  Verify:   Player is not prepared after stop.

Fix 6: B009 (Division by zero guard)
  What:     Add duration > 0 check in syncSecondPlayerPosition().
  Why:      Prevents crash with unusual audio formats.
  Retest:   Mixer with various audio formats.
  Verify:   No crash or incorrect seeking.

Fix 7: B012 (End-of-song timer listener)
  What:     Use a flag instead of replacing the completion listener.
  Why:      Current approach breaks the completion lifecycle.
  Retest:   Set/cancel end-of-song timer → verify normal completion works.
  Verify:   Songs advance normally after timer cancellation.

Fix 8: B013 (Null scheme check)
  What:     Use "content".equals(uri.getScheme()).
  Why:      Prevents NPE with unusual URIs.
  Retest:   Open files with various URI formats.
  Verify:   No crash with scheme-less URIs.

Fix 9: B006, B011, B015 (State synchronization)
  What:     Improve synchronization between Activity and Service state.
  Why:      Prevents stale state and playback interruption.
  Retest:   Full lifecycle test: play, pause, recreate, notification
            controls, mixer preset.
  Verify:   UI always reflects actual playback state.

Fix 10: B010, B014, B017, B018, B019, B020, B021, B022, B023, B025, B028
        (Low priority improvements)
  What:     Various UI improvements, dead code removal, consistency fixes.
  Why:      Code quality and UX polish.
  Retest:   Each fix individually.
  Verify:   No regressions in affected areas.


════════════════════════════════════════════════════════════════════════════════
  SECTION 5: OVERALL CODE HEALTH SUMMARY
════════════════════════════════════════════════════════════════════════════════

Overall Stability:
    The app is FUNCTIONAL for the primary use case (select a song, play it,
    use basic controls). The most dangerous areas are the Activity/Service
    lifecycle management in onDestroy() and the MediaPlayer state
    synchronization between the Activity and Service. These affect every
    app close, recreation, and notification interaction.

Most Dangerous Bugs:
    B001 (effects released during playback) and B002 (service holds released
    player) — these affect the core playback lifecycle.

Most Likely Source of Crashes:
    NullPointerException in getFileNameFromUri() (B013), IllegalStateException
    from MediaPlayer operations after Activity recreation (B001/B002).

Most Likely Source of Inconsistent State:
    The dual MediaPlayer architecture (Activity + Service each hold
    references) without proper synchronization (B004, B011, B015).

Most Likely Source of Duplicate Playback:
    B004 — multiple MediaPlayer instances created during rapid state
    transitions.

Most Fragile Areas:
    1. onDestroy() — order of unbind/release/cleanup is critical.
    2. onServiceConnected / syncFromServiceToUi — adopting the service's
       player after Activity recreation.
    3. prepareSecondMediaPlayer() — two-player coordination with async
       preparation.
    4. handleSongCompletion() — three playback modes × playlist vs
       non-playlist = 6 branches with subtle differences.

Areas Unnecessarily Complex:
    1. The monolithic 10,644-line Activity class.
    2. The voice changer system (13+ voice effects, each with separate
       persistence) could be extracted to a VoiceEffectsManager.
    3. The theme system (4 theme modes, 3 mini-player themes, custom colors,
       saved themes) could be extracted to a ThemeManager.
    4. The mixer system (dual players, individual speeds, balance, sync)
       could be extracted to a MixerManager.
    5. The blur effect implementation is a custom box blur — could use
       RenderScript or a library.

Areas That Should NOT Be Changed:
    1. The seek bar update loop (updateSeekBar) — it works correctly.
    2. The A-B repeat logic (setPointA, setPointB, enableABRepeat) —
       well-implemented with proper guards.
    3. The search system (filter, subsequence mode, history) — working
       correctly.
    4. The folder view system — functional.
    5. The playlist system — functional.
    6. The equalizer system (ensureEqualizerInitialized, band management,
       presets) — well-designed.
    7. The sort system — works correctly.
    8. The settings backup/restore — functional.

Recommended Testing Priorities:
    1. P0: Play → rotate device → verify playback continues smoothly.
    2. P0: Play → swipe from recents → reopen → verify state is correct.
    3. P0: Play → notification Next → verify no duplicate playback.
    4. P1: Playlist playback in all three modes through multiple songs.
    5. P1: Mixer mode with preset save/restore.
    6. P1: Sleep timer with all three actions.
    7. P2: Voice changer effects with vibrato and auto-tune.
    8. P2: Theme switching with custom colors.
    9. P2: File rename/delete operations.
   10. P3: Search, sort, folder view interactions.

================================================================================
                              END OF AUDIT REPORT
================================================================================
