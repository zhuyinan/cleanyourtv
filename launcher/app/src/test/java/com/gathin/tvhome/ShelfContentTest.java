package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class ShelfContentTest {

    @Test
    public void browsableChannelsOnlyWhenAnyAre() {
        assertEquals(Arrays.asList(2L, 4L), ShelfContent.pickChannels(Arrays.asList(
                new long[]{1, 0}, new long[]{2, 1}, new long[]{3, 0}, new long[]{4, 1})));
    }

    @Test
    public void firstChannelStandsInWhenNoneIsBrowsable() {
        assertEquals(Collections.singletonList(7L), ShelfContent.pickChannels(Arrays.asList(
                new long[]{7, 0}, new long[]{9, 0})));
        assertEquals(Collections.emptyList(), ShelfContent.pickChannels(Collections.emptyList()));
    }

    @Test
    public void progressIsAPercentOrNothing() {
        assertEquals(-1, ShelfContent.progress(0, 1000));
        assertEquals(-1, ShelfContent.progress(500, 0));
        assertEquals(50, ShelfContent.progress(500, 1000));
        assertEquals(1, ShelfContent.progress(1, 100000));
        assertEquals(100, ShelfContent.progress(2000, 1000));
    }

    @Test
    public void titleFallsBackAndBlankIsNothing() {
        assertEquals("Show", ShelfContent.firstNonEmpty("Show", "Episode"));
        assertEquals("Episode", ShelfContent.firstNonEmpty(" ", "Episode"));
        assertNull(ShelfContent.firstNonEmpty(null, ""));
    }

    @Test
    public void sampleSizeKeepsAtLeastTheCardWidth() {
        assertEquals(1, ShelfImages.sampleSize(320, 320));
        assertEquals(2, ShelfImages.sampleSize(1279, 320));
        assertEquals(4, ShelfImages.sampleSize(1280, 320));
        assertEquals(4, ShelfImages.sampleSize(1920, 320));
        assertEquals(1, ShelfImages.sampleSize(100, 320));
    }
}
