package com.fongmi.android.tv.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.crawler.SpiderDebug;

import java.util.Collections;
import java.util.List;

public class TvHomeRecommendStore {

    private static final String PREF_NAME = "tv_home_recommend";
    private static final String KEY_SITE = "site_key";
    private static final String KEY_LIST = "vod_list_json";

    private static SharedPreferences getPrefs() {
        return App.get().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static void save(String siteKey, List<Vod> list) {
        if (list == null || list.isEmpty() || TextUtils.isEmpty(siteKey)) {
            getPrefs().edit().remove(KEY_SITE).remove(KEY_LIST).apply();
            return;
        }
        try {
            String json = App.gson().toJson(list);
            getPrefs().edit()
                    .putString(KEY_SITE, siteKey)
                    .putString(KEY_LIST, json)
                    .apply();
            SpiderDebug.log("tv-home", "Saved %d recommendations for site %s to local store", list.size(), siteKey);
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "Failed to save recommendations: %s", t.getMessage());
        }
    }

    public static String getSiteKey() {
        return getPrefs().getString(KEY_SITE, "");
    }

    public static List<Vod> getVodList() {
        try {
            String json = getPrefs().getString(KEY_LIST, "");
            if (TextUtils.isEmpty(json)) return Collections.emptyList();
            return Vod.arrayFrom(json);
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "Failed to load recommendations: %s", t.getMessage());
            return Collections.emptyList();
        }
    }

    public static boolean hasRecommendations() {
        return !getVodList().isEmpty();
    }
}
