package com.fongmi.android.tv.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.tvprovider.media.tv.TvContractCompat;
import androidx.tvprovider.media.tv.WatchNextProgram;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TvHomeWatchNext {

    private static final int MAX_WATCH_NEXT = 20;

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public static synchronized void sync(List<History> historyList) {
        if (!isSupported() || historyList == null) return;
        Context context = App.get();
        ContentResolver resolver = context.getContentResolver();

        try {
            // Query current WatchNext programs belonging to this app
            Map<String, Long> existingMap = new HashMap<>(); // contentId -> programId
            try (Cursor cursor = resolver.query(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    WatchNextProgram.PROJECTION,
                    null,
                    null,
                    null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        try {
                            WatchNextProgram program = WatchNextProgram.fromCursor(cursor);
                            String contentId = program.getContentId();
                            if (!TextUtils.isEmpty(contentId)) {
                                existingMap.put(contentId, program.getId());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable t) {
                SpiderDebug.log("tv-home", "Failed to query WatchNext programs: %s", t.getMessage());
                return;
            }

            // Filter valid continue watching items
            List<History> candidates = new ArrayList<>();
            Set<String> seenIdentities = new HashSet<>();

            for (History h : historyList) {
                if (candidates.size() >= MAX_WATCH_NEXT) break;
                if (h == null) continue;
                String siteKey = h.getSiteKey();
                String vodId = h.getVodId();
                if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId) || TextUtils.isEmpty(h.getVodName())) continue;

                String identity = siteKey + AppDatabase.SYMBOL + vodId;
                if (!seenIdentities.add(identity)) continue; // deduplicate

                // Position > 0, not finished
                if (h.getPosition() <= 0 || h.isNearEnding()) continue;
                if (h.getDuration() > 0 && h.getPosition() >= h.getDuration() - 5_000) continue;

                // Check suppression
                if (TvHomeSuppressionStore.isSuppressed(siteKey, vodId)) {
                    continue;
                }

                candidates.add(h);
            }

            Set<String> targetContentIds = new HashSet<>();

            for (History h : candidates) {
                String siteKey = h.getSiteKey();
                String vodId = h.getVodId();
                String contentId = siteKey + AppDatabase.SYMBOL + vodId;
                targetContentIds.add(contentId);

                String title = h.getVodName();
                String desc = h.getVodRemarks();
                String pic = cleanUrl(h.getVodPic());
                Uri posterUri = TextUtils.isEmpty(pic) ? null : Uri.parse(pic);
                Uri intentUri = TvHomeDeepLink.buildUri(siteKey, vodId, h.getVodName(), pic, h.getVodRemarks(), cleanUrl(h.getWallPic()));

                WatchNextProgram.Builder builder = new WatchNextProgram.Builder();
                builder.setType(TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                        .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                        .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
                        .setBrowsable(true)
                        .setTitle(title)
                        .setDescription(desc)
                        .setIntentUri(intentUri)
                        .setLastEngagementTimeUtcMillis(h.getCreateTime())
                        .setLastPlaybackPositionMillis((int) Math.max(0, h.getPosition()))
                        .setDurationMillis((int) Math.max(0, h.getDuration()))
                        .setContentId(contentId);

                if (posterUri != null) builder.setPosterArtUri(posterUri);

                WatchNextProgram program = builder.build();

                if (existingMap.containsKey(contentId)) {
                    long programId = existingMap.get(contentId);
                    resolver.update(
                            TvContractCompat.buildWatchNextProgramUri(programId),
                            program.toContentValues(),
                            null,
                            null
                    );
                } else {
                    resolver.insert(
                            TvContractCompat.WatchNextPrograms.CONTENT_URI,
                            program.toContentValues()
                    );
                }
            }

            // Remove items that are no longer in candidates (e.g. finished or deleted)
            for (Map.Entry<String, Long> entry : existingMap.entrySet()) {
                if (!targetContentIds.contains(entry.getKey())) {
                    resolver.delete(
                            TvContractCompat.buildWatchNextProgramUri(entry.getValue()),
                            null,
                            null
                    );
                }
            }
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "Error syncing WatchNext: %s", t.getMessage());
        }
    }

    public static synchronized void remove(String siteKey, String vodId) {
        if (!isSupported()) return;
        String contentId = siteKey + AppDatabase.SYMBOL + vodId;
        Context context = App.get();
        ContentResolver resolver = context.getContentResolver();

        try (Cursor cursor = resolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                WatchNextProgram.PROJECTION,
                null,
                null,
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    WatchNextProgram program = WatchNextProgram.fromCursor(cursor);
                    if (contentId.equals(program.getContentId())) {
                        resolver.delete(
                                TvContractCompat.buildWatchNextProgramUri(program.getId()),
                                null,
                                null
                        );
                    }
                }
            }
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "Failed to remove WatchNext: %s", t.getMessage());
        }
    }

    public static synchronized void removeAll() {
        if (!isSupported()) return;
        Context context = App.get();
        ContentResolver resolver = context.getContentResolver();
        try {
            resolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null);
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "Failed to remove all WatchNext: %s", t.getMessage());
        }
    }

    private static String cleanUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        return url.split("@")[0];
    }
}
