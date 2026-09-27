package com.fongmi.android.tv.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.github.catvod.crawler.SpiderDebug;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class TvHomeDeepLink {

    public static final String SCHEME = "webhtv";
    public static final String HOST_VOD = "vod";
    public static final String HOST_HOME = "home";

    public static Uri buildUri(String key, String id, String name, String pic, String mark, String wallPic) {
        Uri.Builder builder = new Uri.Builder()
                .scheme(SCHEME)
                .authority(HOST_VOD)
                .appendQueryParameter("key", safeString(key))
                .appendQueryParameter("id", safeString(id))
                .appendQueryParameter("name", safeString(name))
                .appendQueryParameter("pic", safeString(pic));
        if (!TextUtils.isEmpty(mark)) builder.appendQueryParameter("mark", mark);
        if (!TextUtils.isEmpty(wallPic)) builder.appendQueryParameter("wallPic", wallPic);
        return builder.build();
    }

    public static Uri buildHomeUri() {
        return new Uri.Builder()
                .scheme(SCHEME)
                .authority(HOST_HOME)
                .build();
    }

    public static Intent buildIntent(Context context, String key, String id, String name, String pic, String mark, String wallPic) {
        Intent intent = new Intent(Intent.ACTION_VIEW, buildUri(key, id, name, pic, mark, wallPic));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    public static boolean isDeepLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        Uri uri = intent.getData();
        return uri != null && SCHEME.equalsIgnoreCase(uri.getScheme());
    }

    public static boolean handle(Activity activity, Intent intent) {
        if (activity == null || intent == null) return false;
        Uri uri = intent.getData();
        if (uri == null || !SCHEME.equalsIgnoreCase(uri.getScheme())) return false;

        String authority = uri.getAuthority();
        if (HOST_HOME.equalsIgnoreCase(authority)) {
            SpiderDebug.log("tv-home", "DeepLink handled as home fallback");
            return true;
        }

        if (HOST_VOD.equalsIgnoreCase(authority)) {
            try {
                String key = getParam(uri, "key");
                String id = getParam(uri, "id");
                String name = getParam(uri, "name");
                String pic = getParam(uri, "pic");
                String mark = getParam(uri, "mark");
                String wallPic = getParam(uri, "wallPic");

                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(id)) {
                    SpiderDebug.log("tv-home", "DeepLink ignored: missing key or id: %s", uri);
                    return false;
                }

                SpiderDebug.log("tv-home", "DeepLink launching VideoActivity: key=%s id=%s name=%s", key, id, name);
                VideoActivity.start(activity, key, id, name, pic, mark, false, false, wallPic, null);
                return true;
            } catch (Throwable t) {
                SpiderDebug.log("tv-home", "DeepLink handle error: %s", t.getMessage());
                return false;
            }
        }
        return false;
    }

    private static String getParam(Uri uri, String key) {
        String val = uri.getQueryParameter(key);
        if (val == null) return "";
        try {
            return URLDecoder.decode(val, StandardCharsets.UTF_8.name());
        } catch (Throwable ignored) {
            return val;
        }
    }

    private static String safeString(String str) {
        return str == null ? "" : str;
    }
}
