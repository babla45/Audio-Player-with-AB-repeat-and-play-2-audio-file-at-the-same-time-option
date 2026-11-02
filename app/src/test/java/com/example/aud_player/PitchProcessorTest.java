package com.example.aud_player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PitchProcessorTest {
    @Test
    public void semitonesConvertToExpectedPitchRatios() {
        double[][] cases = {
                {-12.0, 0.5},
                {-7.0, 0.667419927},
                {-1.0, 0.943874313},
                {0.0, 1.0},
                {1.0, 1.059463094},
                {7.0, 1.498307077},
                {12.0, 2.0}
        };

        for (double[] testCase : cases) {
            assertEquals(testCase[1],
                    PitchProcessor.pitchRatioFromSemitones(testCase[0]), 1.0e-8);
        }
    }

    @Test
    public void pitchRatiosConvertBackToSemitones() {
        double[] semitones = {-12.0, -7.0, -1.0, 0.0, 1.0, 7.0, 12.0};

        for (double expected : semitones) {
            double ratio = PitchProcessor.pitchRatioFromSemitones(expected);
            assertEquals(expected, PitchProcessor.semitonesFromPitchRatio(ratio), 1.0e-10);
        }
    }

    @Test
    public void invalidPitchInputsAreRejectedAndSliderRangeIsPreserved() {
        assertThrows(IllegalArgumentException.class,
                () -> PitchProcessor.pitchRatioFromSemitones(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> PitchProcessor.semitonesFromPitchRatio(0.0));
        assertThrows(IllegalArgumentException.class,
                () -> PitchProcessor.normalizeSemitones(Float.POSITIVE_INFINITY));

        assertEquals(-12.0f, PitchProcessor.normalizeSemitones(-20.0f), 0.0f);
        assertEquals(12.0f, PitchProcessor.normalizeSemitones(20.0f), 0.0f);
        assertEquals(0.5, PitchProcessor.pitchRatioFromSemitones(-12.0), 0.0);
        assertEquals(2.0, PitchProcessor.pitchRatioFromSemitones(12.0), 0.0);
    }
}
