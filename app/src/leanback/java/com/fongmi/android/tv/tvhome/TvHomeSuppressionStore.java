package com.fongmi.android.tv.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.github.catvod.crawler.SpiderDebug;

public class TvHomeSuppressionStore {

    private static final String PREF_NAME = "tv_home_suppression";
    private static final long UNSUPPRESS_PLAYBACK_MS = 30_000; // 30 seconds

    private static SharedPreferences getPrefs() {
        return App.get().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static String buildIdentity(String siteKey, String vodId) {
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId)) return "";
        return siteKey + AppDatabase.SYMBOL + vodId;
    }

    public static void suppress(String siteKey, String vodId) {
        String identity = buildIdentity(siteKey, vodId);
        if (TextUtils.isEmpty(identity)) return;
        SpiderDebug.log("tv-home", "Suppressing item from Watch Next: %s", identity);
        getPrefs().edit().putLong(identity, System.currentTimeMillis()).apply();
    }

    public static boolean isSuppressed(String siteKey, String vodId) {
        String identity = buildIdentity(siteKey, vodId);
        if (TextUtils.isEmpty(identity)) return false;
        return getPrefs().contains(identity);
    }

    public static void unsuppress(String siteKey, String vodId) {
        String identity = buildIdentity(siteKey, vodId);
        if (TextUtils.isEmpty(identity)) return;
        if (getPrefs().contains(identity)) {
            SpiderDebug.log("tv-home", "Unsuppressing item: %s", identity);
            getPrefs().edit().remove(identity).apply();
        }
    }

    public static void checkPlaybackUnsuppress(History history) {
        if (history == null) return;
        String siteKey = history.getSiteKey();
        String vodId = history.getVodId();
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId)) return;

        if (isSuppressed(siteKey, vodId)) {
            long suppressedTime = getPrefs().getLong(buildIdentity(siteKey, vodId), 0);
            // If active playback has updated position > 30s or watched anew after suppression
            if (history.getPosition() >= UNSUPPRESS_PLAYBACK_MS || history.getCreateTime() > suppressedTime + 5_000) {
                unsuppress(siteKey, vodId);
            }
        }
    }

    public static void clear() {
        getPrefs().edit().clear().apply();
    }
}
