package com.fongmi.android.tv.tvhome;

import android.os.Handler;
import android.os.Looper;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.crawler.SpiderDebug;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TvHomeSync {

    private static final long DEBOUNCE_DELAY_MS = 5_000;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "tv-home-sync"));
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static final Runnable sHistoryDebounceRunnable = () -> executeHistorySync();
    private static volatile boolean sHistoryDebounceScheduled = false;

    private static volatile String sLatestRecommendSite = "";
    private static volatile List<Vod> sLatestRecommendList = Collections.emptyList();

    public static void requestHistorySync(boolean immediate) {
        if (immediate) {
            sHandler.removeCallbacks(sHistoryDebounceRunnable);
            sHistoryDebounceScheduled = false;
            sExecutor.execute(TvHomeSync::executeHistorySync);
        } else {
            if (!sHistoryDebounceScheduled) {
                sHistoryDebounceScheduled = true;
                sHandler.postDelayed(sHistoryDebounceRunnable, DEBOUNCE_DELAY_MS);
            }
        }
    }

    public static void requestFavoritesSync() {
        sExecutor.execute(TvHomeSync::executeFavoritesSync);
    }

    public static void requestRecommendationsSync(String siteKey, List<Vod> list) {
        sLatestRecommendSite = siteKey;
        sLatestRecommendList = list == null ? Collections.emptyList() : list;
        TvHomeRecommendStore.save(siteKey, list);
        sExecutor.execute(() -> executeRecommendationsSync(siteKey, list));
    }

    public static void requestSyncAll() {
        sExecutor.execute(() -> {
            executeHistorySync();
            executeFavoritesSync();
            if (sLatestRecommendList.isEmpty()) {
                sLatestRecommendSite = TvHomeRecommendStore.getSiteKey();
                sLatestRecommendList = TvHomeRecommendStore.getVodList();
            }
            executeRecommendationsSync(sLatestRecommendSite, sLatestRecommendList);
        });
    }

    private static void executeHistorySync() {
        sHistoryDebounceScheduled = false;
        try {
            List<History> list = History.get();
            SpiderDebug.log("tv-home", "Executing history sync count=%d", list == null ? 0 : list.size());
            TvHomeWatchNext.sync(list);
            TvHomeChannels.syncContinueWatching(list);
            TvHomeChannels.syncRecentHistory(list);
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "executeHistorySync error: %s", t.getMessage());
        }
    }

    private static void executeFavoritesSync() {
        try {
            List<Keep> list = Keep.getVod();
            SpiderDebug.log("tv-home", "Executing favorites sync count=%d", list == null ? 0 : list.size());
            TvHomeChannels.syncFavorites(list);
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "executeFavoritesSync error: %s", t.getMessage());
        }
    }

    private static void executeRecommendationsSync(String siteKey, List<Vod> list) {
        try {
            SpiderDebug.log("tv-home", "Executing recommendations sync count=%d site=%s", list == null ? 0 : list.size(), siteKey);
            TvHomeChannels.syncRecommendations(siteKey, list);
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "executeRecommendationsSync error: %s", t.getMessage());
        }
    }
}
