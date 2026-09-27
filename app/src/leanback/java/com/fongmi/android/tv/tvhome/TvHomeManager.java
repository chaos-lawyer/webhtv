package com.fongmi.android.tv.tvhome;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class TvHomeManager {

    private static volatile TvHomeManager sInstance;
    private static volatile boolean sInitialized = false;

    public static TvHomeManager get() {
        if (sInstance == null) {
            synchronized (TvHomeManager.class) {
                if (sInstance == null) sInstance = new TvHomeManager();
            }
        }
        return sInstance;
    }

    public static synchronized void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;
        try {
            TvHomeManager manager = get();
            if (!EventBus.getDefault().isRegistered(manager)) {
                EventBus.getDefault().register(manager);
            }
            TvHomeChannels.initChannels(context);
            SpiderDebug.log("tv-home", "TvHomeManager initialized");
            syncAll();
            fetchRecommendationsAsync();
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "TvHomeManager init error: %s", t.getMessage());
        }
    }

    public static void onHistoryChanged(History history) {
        if (history == null) return;
        TvHomeSuppressionStore.checkPlaybackUnsuppress(history);
        TvHomeSync.requestHistorySync(false);
    }

    public static void onHistoryChangedImmediate(History history) {
        if (history == null) return;
        TvHomeSuppressionStore.checkPlaybackUnsuppress(history);
        TvHomeSync.requestHistorySync(true);
    }

    public static void onHistoryDeleted(History history) {
        if (history == null) return;
        TvHomeWatchNext.remove(history.getSiteKey(), history.getVodId());
        TvHomeSync.requestHistorySync(true);
    }

    public static void onHistoryCleared() {
        TvHomeWatchNext.removeAll();
        TvHomeSync.requestHistorySync(true);
    }

    public static void onKeepChanged() {
        TvHomeSync.requestFavoritesSync();
    }

    public static void onRecommendChanged(String siteKey, List<Vod> list) {
        TvHomeSync.requestRecommendationsSync(siteKey, list);
    }

    public static void syncAll() {
        TvHomeSync.requestSyncAll();
    }

    public static void fetchRecommendationsAsync() {
        Task.execute(() -> {
            try {
                VodConfig vodConfig = VodConfig.get();
                if (vodConfig == null) return;

                Site homeSite = vodConfig.getHome();
                List<Vod> list = fetchSiteRecommendations(homeSite);
                String siteKey = homeSite == null ? "" : homeSite.getKey();
                Site currentHome = vodConfig.getHome();
                if (currentHome == null || !TextUtils.equals(siteKey, currentHome.getKey())) return;
                if (!list.isEmpty() || !TextUtils.equals(siteKey, TvHomeRecommendStore.getSiteKey())) {
                    onRecommendChanged(siteKey, list);
                }
            } catch (Throwable t) {
                SpiderDebug.log("tv-home", "fetchRecommendationsAsync error: %s", t.getMessage());
            }
        });
    }

    private static List<Vod> fetchSiteRecommendations(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return Collections.emptyList();
        if (site.getType() == null && TextUtils.isEmpty(site.getApi()) && TextUtils.isEmpty(site.getJar())) {
            return Collections.emptyList();
        }
        try {
            SpiderDebug.log("tv-home", "Fetching recommendations for site: %s", site.getKey());
            Result result = SiteApi.homeContent(site);
            if (result != null && result.getList() != null && !result.getList().isEmpty()) {
                return result.getList();
            }
            if (result != null && result.getTypes() != null && !result.getTypes().isEmpty()) {
                Class firstType = result.getTypes().get(0);
                Result catResult = SiteApi.categoryContent(site.getKey(), firstType.getTypeId(), "1", true, new HashMap<>());
                if (catResult != null && catResult.getList() != null && !catResult.getList().isEmpty()) {
                    return catResult.getList();
                }
            }
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "Failed to fetch recommendations for site %s: %s", site.getKey(), t.getMessage());
        }
        return Collections.emptyList();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRefreshEvent(RefreshEvent event) {
        if (event == null) return;
        if (event.getType() == RefreshEvent.Type.HISTORY) {
            TvHomeSync.requestHistorySync(true);
        } else if (event.getType() == RefreshEvent.Type.KEEP) {
            TvHomeSync.requestFavoritesSync();
        } else if (event.getType() == RefreshEvent.Type.HOME) {
            fetchRecommendationsAsync();
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onConfigEvent(ConfigEvent event) {
        if (event == null) return;
        if (event.type() == ConfigEvent.Type.VOD) {
            fetchRecommendationsAsync();
        }
    }
}
