package com.example.aud_player;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Storage for the user's own voice-changer settings ("Save" in the voice
 * controls sheet).
 *
 * <p>An entry is a full snapshot of the sheet: pitch / formant / speed, every
 * effect slider, the three toggles and the voice EQ preset. Entries live as a
 * JSON array in SharedPreferences — no database or file handling is needed,
 * mirroring how the mixer presets are stored. The list is newest-first and the
 * sheet renders it as a horizontally scrolling chip row, so it never depends on
 * how many settings the user keeps.
 */
final class VoicePresetStore {

    private static final String TAG = "VoicePresetStore";
    private static final String PREFS_NAME = "audio_player_prefs";
    private static final String PREFS_KEY = "saved_voice_presets_v1";

    /** Upper bound on stored settings — past this the sheet asks for a delete first. */
    static final int MAX_PRESETS = 20;

    /** The EQ preset name that means "no preset applied" (see MainActivity.applyVoiceEqPreset). */
    static final String EQ_NONE = "None";

    /** One named snapshot of the voice controls. */
    static class SavedVoicePreset {
        String name = "";
        float pitch = 0.0f;   // semitones
        float formant = 1.0f;
        float speed = 1.0f;
        float volumeBoost = 1.0f;
        int bass = 0;          // 0..1000 per-mille
        int reverb = 0;        // 0..1000
        int treble = 0;        // -1000..1000
        int clarity = 0;       // 0..1000
        int echo = 0;          // 0..1000
        int distortion = 0;    // 0..1000
        int vibrato = 0;       // 0..1000
        int depth = 0;         // -1000..1000
        boolean robot = false;
        boolean noiseReduction = false;
        boolean autoTune = false;
        String eqPreset = EQ_NONE;
    }

    private VoicePresetStore() {
    }

    /** Reads the saved settings, newest first. Never throws on a corrupt blob. */
    static List<SavedVoicePreset> load(Context context) {
        List<SavedVoicePreset> presets = new ArrayList<>();
        if (context == null) {
            return presets;
        }

        String raw = prefs(context).getString(PREFS_KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj != null) {
                    presets.add(fromJson(obj));
                }
            }
        } catch (JSONException e) {
            // Start clean rather than crash the sheet on an unreadable blob.
            Log.e(TAG, "Failed to parse saved voice settings", e);
        }
        return presets;
    }

    /**
     * Stores a new setting at the top of the list. Returns false when the list
     * is already full, so the caller can tell the user instead of silently
     * dropping one of their settings.
     */
    static boolean add(Context context, SavedVoicePreset preset) {
        if (context == null || preset == null) {
            return false;
        }
        List<SavedVoicePreset> presets = load(context);
        if (presets.size() >= MAX_PRESETS) {
            return false;
        }
        presets.add(0, preset);
        persist(context, presets);
        return true;
    }

    /** Removes the given entry (matched by {@link #key(SavedVoicePreset)}). */
    static void delete(Context context, SavedVoicePreset preset) {
        if (context == null || preset == null) {
            return;
        }
        String target = key(preset);
        List<SavedVoicePreset> presets = load(context);
        for (int i = 0; i < presets.size(); i++) {
            if (key(presets.get(i)).equals(target)) {
                presets.remove(i);
                break;
            }
        }
        persist(context, presets);
    }

    /**
     * Identity of a setting: its canonical JSON form. Used to match an entry
     * for deletion and to keep the "applied" highlight on the right chip across
     * a list reload. The key order is fixed by {@link #toJson}, so the string is
     * stable for equal values.
     */
    static String key(SavedVoicePreset preset) {
        return preset == null ? "" : toJson(preset).toString();
    }

    private static SavedVoicePreset fromJson(JSONObject obj) {
        SavedVoicePreset preset = new SavedVoicePreset();
        preset.name = obj.optString("name", "");
        if (obj.has("pitchSemitones")) {
            float semitones = (float) obj.optDouble("pitchSemitones", 0.0);
            try {
                preset.pitch = PitchProcessor.normalizeSemitones(semitones);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid saved pitch; restoring unity pitch", e);
                preset.pitch = 0.0f;
            }
        } else {
            float oldPitchRatio = (float) obj.optDouble("pitch", 1.0);
            try {
                preset.pitch = PitchProcessor.normalizeSemitones(
                        (float) PitchProcessor.semitonesFromPitchRatio(oldPitchRatio));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid legacy pitch ratio; restoring unity pitch", e);
                preset.pitch = 0.0f;
            }
        }
        preset.formant = (float) obj.optDouble("formant", 1.0);
        preset.speed = (float) obj.optDouble("speed", 1.0);
        preset.volumeBoost = (float) obj.optDouble("volumeBoost", 1.0);
        preset.bass = obj.optInt("bass", 0);
        preset.reverb = obj.optInt("reverb", 0);
        preset.treble = obj.optInt("treble", 0);
        preset.clarity = obj.optInt("clarity", 0);
        preset.echo = obj.optInt("echo", 0);
        preset.distortion = obj.optInt("distortion", 0);
        preset.vibrato = obj.optInt("vibrato", 0);
        preset.depth = obj.optInt("depth", 0);
        preset.robot = obj.optBoolean("robot", false);
        preset.noiseReduction = obj.optBoolean("noiseReduction", false);
        preset.autoTune = obj.optBoolean("autoTune", false);
        preset.eqPreset = obj.optString("eqPreset", EQ_NONE);
        return preset;
    }

    private static JSONObject toJson(SavedVoicePreset preset) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("name", preset.name);
            obj.put("pitchSemitones", preset.pitch);
            obj.put("formant", preset.formant);
            obj.put("speed", preset.speed);
            obj.put("volumeBoost", preset.volumeBoost);
            obj.put("bass", preset.bass);
            obj.put("reverb", preset.reverb);
            obj.put("treble", preset.treble);
            obj.put("clarity", preset.clarity);
            obj.put("echo", preset.echo);
            obj.put("distortion", preset.distortion);
            obj.put("vibrato", preset.vibrato);
            obj.put("depth", preset.depth);
            obj.put("robot", preset.robot);
            obj.put("noiseReduction", preset.noiseReduction);
            obj.put("autoTune", preset.autoTune);
            obj.put("eqPreset", preset.eqPreset);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize a voice setting", e);
        }
        return obj;
    }

    private static void persist(Context context, List<SavedVoicePreset> presets) {
        JSONArray array = new JSONArray();
        for (SavedVoicePreset preset : presets) {
            array.put(toJson(preset));
        }
        prefs(context).edit().putString(PREFS_KEY, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
