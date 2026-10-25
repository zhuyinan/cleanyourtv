package com.gathin.tvhome;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the top shelf shows for one app (config.xml top_shelf): its Watch Next entries, newest first, then the
 * programs of its preview channels, all read from the TV provider the stock launcher uses.
 *
 * Needs READ_EPG_DATA (a normal permission). Without ACCESS_ALL_EPG_DATA the provider only returns rows other apps
 * published as searchable, which is what launchers are meant to see. Any failure gives an empty shelf, never a crash.
 */
final class ShelfContent {

    private static final String TAG = "TvHome.Shelf";

    /** One card: a title, the picture to show (or null), the intent that opens it, and progress 0-100 or -1. */
    static final class Item {
        final String title;
        final String image;
        final String intentUri;
        final int progress;

        Item(String title, String image, String intentUri, int progress) {
            this.title = title;
            this.image = image;
            this.intentUri = intentUri;
            this.progress = progress;
        }
    }

    private ShelfContent() {
    }

    // Columns 0-6 are shared by Watch Next and preview programs; 7-8 differ and are named per query.
    private static final String[] COMMON = {
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
    };

    private static String[] columns(String... extra) {
        String[] all = new String[COMMON.length + extra.length];
        System.arraycopy(COMMON, 0, all, 0, COMMON.length);
        System.arraycopy(extra, 0, all, COMMON.length, extra.length);
        return all;
    }

    /**
     * The shelf for pkg, at most max cards. Runs provider queries: call it off the main thread.
     *
     * The provider refuses a selection or sort from a caller without ACCESS_ALL_EPG_DATA ("Selection not allowed"),
     * so Watch Next and the channel list are read whole and filtered here; a channel's programs come from its own
     * URI (TvContract.buildPreviewProgramsUriForChannel).
     */
    static List<Item> load(ContentResolver cr, String pkg, int max) {
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int rows = 0, mine = 0, channelCount = 0;
        try {
            List<Object[]> next = new ArrayList<>();   // {Item, last engagement}
            try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI,
                    columns(TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS), null, null, null)) {
                while (c != null && c.moveToNext()) {
                    rows++;
                    if (!pkg.equals(c.getString(8))) continue;
                    mine++;
                    Item it = item(c);
                    if (it != null) next.add(new Object[]{it, c.isNull(9) ? 0L : c.getLong(9)});
                }
            }
            next.sort((a, b) -> Long.compare((Long) b[1], (Long) a[1]));   // most recently watched first
            for (Object[] n : next) add(items, seen, (Item) n[0], max);

            List<Long> channels = previewChannels(cr, pkg);
            channelCount = channels.size();
            for (long channel : channels) {
                if (items.size() >= max) break;
                List<Object[]> progs = new ArrayList<>();   // {Item, weight}
                try (Cursor c = cr.query(TvContract.buildPreviewProgramsUriForChannel(channel),
                        columns(TvContract.PreviewPrograms.COLUMN_WEIGHT), null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        Item it = item(c);
                        if (it != null) progs.add(new Object[]{it, c.isNull(8) ? 0 : c.getInt(8)});
                    }
                }
                progs.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));   // the app's own order
                for (Object[] p : progs) add(items, seen, (Item) p[0], max);
            }
        } catch (RuntimeException e) {   // SecurityException without the permission, or a provider that is missing
            Log.w(TAG, "cannot read the shelf for " + pkg + ": " + e);
        }
        Log.d(TAG, pkg + ": " + mine + " of " + rows + " Watch Next rows, " + channelCount + " channels, "
                + items.size() + " cards");
        return items;
    }

    /** The app's preview channels to show, in order (see {@link #pickChannels}). */
    private static List<Long> previewChannels(ContentResolver cr, String pkg) {
        List<long[]> channels = new ArrayList<>();
        try (Cursor c = cr.query(TvContract.Channels.CONTENT_URI, new String[]{TvContract.Channels._ID,
                        TvContract.Channels.COLUMN_BROWSABLE, TvContract.Channels.COLUMN_PACKAGE_NAME,
                        TvContract.Channels.COLUMN_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (pkg.equals(c.getString(2)) && TvContract.Channels.TYPE_PREVIEW.equals(c.getString(3))) {
                    channels.add(new long[]{c.getLong(0), c.getInt(1)});
                }
            }
        }
        channels.sort((a, b) -> Long.compare(a[0], b[0]));
        return pickChannels(channels);
    }

    /**
     * Channels as {id, browsable} in id order. The browsable ones are those the user (or the app's default channel)
     * put on the home screen; when an app has none marked, its first channel stands in, so the shelf is not empty.
     */
    static List<Long> pickChannels(List<long[]> channels) {
        List<Long> out = new ArrayList<>();
        for (long[] ch : channels) if (ch[1] != 0) out.add(ch[0]);
        if (out.isEmpty() && !channels.isEmpty()) out.add(channels.get(0)[0]);
        return Collections.unmodifiableList(out);
    }

    /** The card for the cursor's row, or null when it is hidden or has no title or intent. */
    private static Item item(Cursor c) {
        if (!c.isNull(7) && c.getInt(7) == 0) return null;   // browsable 0: the app or user hid it
        String title = firstNonEmpty(c.getString(0), c.getString(1));
        String intent = c.getString(4);
        if (title == null || intent == null) return null;
        String image = firstNonEmpty(c.getString(3), c.getString(2));   // 16:9 thumbnail first
        long pos = c.isNull(5) ? 0 : c.getLong(5);
        long dur = c.isNull(6) ? 0 : c.getLong(6);
        return new Item(title, image, intent, progress(pos, dur));
    }

    private static void add(List<Item> items, Set<String> seen, Item it, int max) {
        if (items.size() < max && seen.add(it.intentUri)) items.add(it);
    }

    /** Percent watched, or -1 when there is nothing to show (no duration, or not started). */
    static int progress(long positionMs, long durationMs) {
        if (durationMs <= 0 || positionMs <= 0) return -1;
        return (int) Math.max(1, Math.min(100, positionMs * 100 / durationMs));
    }

    static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a;
        if (b != null && !b.trim().isEmpty()) return b;
        return null;
    }
}
