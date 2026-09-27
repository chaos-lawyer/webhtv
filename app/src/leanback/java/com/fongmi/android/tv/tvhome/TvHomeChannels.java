package com.fongmi.android.tv.tvhome;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.tvprovider.media.tv.ChannelLogoUtils;
import androidx.tvprovider.media.tv.PreviewChannel;
import androidx.tvprovider.media.tv.PreviewChannelHelper;
import androidx.tvprovider.media.tv.PreviewProgram;
import androidx.tvprovider.media.tv.TvContractCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.utils.ImgUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.FutureTarget;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TvHomeChannels {

    public static final String ID_CONTINUE = "channel_continue";
    public static final String ID_RECENT = "channel_recent";
    public static final String ID_FAVORITE = "channel_favorite";
    public static final String ID_RECOMMEND = "channel_recommend";

    private static final int MAX_PROGRAMS = 20;
    private static final int MAX_POSTER_FILES = 120;
    private static final ExecutorService sPosterExecutor = Executors.newFixedThreadPool(4);

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public static synchronized void initChannels(Context context) {
        if (!isSupported() || context == null) return;
        try {
            getOrCreateChannel(context, ID_CONTINUE, "继续观看", "WebHomeTV 继续观看");
            getOrCreateChannel(context, ID_RECENT, "最近观看", "WebHomeTV 最近观看");
            getOrCreateChannel(context, ID_FAVORITE, "我的收藏", "WebHomeTV 我的收藏");
            getOrCreateChannel(context, ID_RECOMMEND, "推荐", "WebHomeTV 首页推荐");
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "initChannels error: %s", t.getMessage());
        }
    }

    public static synchronized long getOrCreateChannel(Context context, String internalId, String name, String description) {
        if (!isSupported()) return -1;
        try {
            PreviewChannelHelper helper = new PreviewChannelHelper(context);
            List<PreviewChannel> channels = helper.getAllChannels();
            if (channels != null) {
                for (PreviewChannel c : channels) {
                    if (internalId.equals(c.getInternalProviderId())) {
                        if (!name.equals(c.getDisplayName())) {
                            ContentValues values = new ContentValues();
                            values.put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, name);
                            context.getContentResolver().update(TvContractCompat.buildChannelUri(c.getId()), values, null, null);
                        }
                        return c.getId();
                    }
                }
            }

            PreviewChannel.Builder builder = new PreviewChannel.Builder();
            builder.setInternalProviderId(internalId)
                    .setDisplayName(name)
                    .setDescription(description)
                    .setAppLinkIntentUri(TvHomeDeepLink.buildHomeUri());

            Bitmap logo = getChannelLogo(context);
            if (logo != null) {
                builder.setLogo(logo);
            }

            long channelId = helper.publishChannel(builder.build());
            if (channelId > 0) {
                try {
                    if (logo != null) ChannelLogoUtils.storeChannelLogo(context, channelId, logo);
                } catch (Throwable ignored) {
                }
                try {
                    TvContractCompat.requestChannelBrowsable(context, channelId);
                } catch (Throwable ignored) {
                }
            }
            return channelId;
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "Failed to create/get channel %s: %s", internalId, t.getMessage());
            return -1;
        }
    }

    private static Bitmap getChannelLogo(Context context) {
        try {
            Drawable drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher);
            if (drawable == null) {
                drawable = context.getApplicationInfo().loadIcon(context.getPackageManager());
            }
            if (drawable == null) return null;
            if (drawable instanceof BitmapDrawable) {
                Bitmap bmp = ((BitmapDrawable) drawable).getBitmap();
                if (bmp != null) return bmp;
            }
            int width = Math.max(1, drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 160);
            int height = Math.max(1, drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 160);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "Failed to create channel logo: %s", t.getMessage());
            return null;
        }
    }

    public static synchronized void syncContinueWatching(List<History> historyList) {
        if (!isSupported() || historyList == null) return;
        Context context = App.get();
        long channelId = getOrCreateChannel(context, ID_CONTINUE, "继续观看", "WebHomeTV 继续观看");
        if (channelId <= 0) return;

        List<ProgramItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (History h : historyList) {
            if (items.size() >= MAX_PROGRAMS) break;
            if (h == null) continue;
            String siteKey = h.getSiteKey();
            String vodId = h.getVodId();
            if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId) || TextUtils.isEmpty(h.getVodName())) continue;

            String identity = siteKey + AppDatabase.SYMBOL + vodId;
            if (!seen.add(identity)) continue;

            if (h.getPosition() <= 0 || h.isNearEnding()) continue;
            if (h.getDuration() > 0 && h.getPosition() >= h.getDuration() - 5_000) continue;
            if (TvHomeSuppressionStore.isSuppressed(siteKey, vodId)) continue;

            ProgramItem item = new ProgramItem();
            item.contentId = identity;
            item.title = h.getVodName();
            item.description = h.getVodRemarks();
            item.posterUrl = h.getVodPic();
            item.posterUri = cachedPosterUri(context, item.posterUrl);
            item.intentUri = TvHomeDeepLink.buildUri(siteKey, vodId, h.getVodName(), h.getVodPic(), h.getVodRemarks(), h.getWallPic());
            item.position = h.getPosition();
            item.duration = h.getDuration();
            items.add(item);
        }

        syncProgramsToChannel(context, channelId, items, true);
    }

    public static synchronized void syncRecentHistory(List<History> historyList) {
        if (!isSupported() || historyList == null) return;
        Context context = App.get();
        long channelId = getOrCreateChannel(context, ID_RECENT, "最近观看", "WebHomeTV 最近观看");
        if (channelId <= 0) return;

        List<ProgramItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (History h : historyList) {
            if (items.size() >= MAX_PROGRAMS) break;
            if (h == null) continue;
            String siteKey = h.getSiteKey();
            String vodId = h.getVodId();
            if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId) || TextUtils.isEmpty(h.getVodName())) continue;

            String identity = siteKey + AppDatabase.SYMBOL + vodId;
            if (!seen.add(identity)) continue;

            ProgramItem item = new ProgramItem();
            item.contentId = identity;
            item.title = h.getVodName();
            item.description = h.getVodRemarks();
            item.posterUrl = h.getVodPic();
            item.posterUri = cachedPosterUri(context, item.posterUrl);
            item.intentUri = TvHomeDeepLink.buildUri(siteKey, vodId, h.getVodName(), h.getVodPic(), h.getVodRemarks(), h.getWallPic());
            item.position = h.getPosition();
            item.duration = h.getDuration();
            items.add(item);
        }

        syncProgramsToChannel(context, channelId, items, true);
    }

    public static synchronized void syncFavorites(List<Keep> keepList) {
        if (!isSupported() || keepList == null) return;
        Context context = App.get();
        long channelId = getOrCreateChannel(context, ID_FAVORITE, "我的收藏", "WebHomeTV 我的收藏");
        if (channelId <= 0) return;

        List<ProgramItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Keep k : keepList) {
            if (items.size() >= MAX_PROGRAMS) break;
            if (k == null) continue;
            String siteKey = k.getSiteKey();
            String vodId = k.getVodId();
            if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId) || TextUtils.isEmpty(k.getVodName())) continue;

            String identity = siteKey + AppDatabase.SYMBOL + vodId;
            if (!seen.add(identity)) continue;

            ProgramItem item = new ProgramItem();
            item.contentId = identity;
            item.title = k.getVodName();
            item.description = k.getSiteName();
            item.posterUrl = k.getVodPic();
            item.posterUri = cachedPosterUri(context, item.posterUrl);
            item.intentUri = TvHomeDeepLink.buildUri(siteKey, vodId, k.getVodName(), k.getVodPic(), null, null);
            items.add(item);
        }

        syncProgramsToChannel(context, channelId, items, false);
    }

    public static synchronized void syncRecommendations(String siteKey, List<Vod> vodList) {
        if (!isSupported()) return;
        Context context = App.get();
        long channelId = getOrCreateChannel(context, ID_RECOMMEND, "推荐", "WebHomeTV 首页推荐");
        if (channelId <= 0) return;

        List<ProgramItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (vodList != null && !TextUtils.isEmpty(siteKey)) {
            for (Vod v : vodList) {
                if (items.size() >= MAX_PROGRAMS) break;
                if (v == null || TextUtils.isEmpty(v.getId()) || TextUtils.isEmpty(v.getName())) continue;

                String identity = siteKey + AppDatabase.SYMBOL + v.getId();
                if (!seen.add(identity)) continue;

                ProgramItem item = new ProgramItem();
                item.contentId = identity;
                item.siteKey = siteKey;
                item.title = v.getName();
                item.description = v.getRemarks();
                item.posterUrl = v.getPic();
                item.posterUri = cachedPosterUri(context, item.posterUrl);
                item.intentUri = TvHomeDeepLink.buildUri(siteKey, v.getId(), v.getName(), v.getPic(), v.getRemarks(), null);
                items.add(item);
            }
        }

        syncProgramsToChannel(context, channelId, items, false);
        trimPosterCache(context);
    }

    private static void syncProgramsToChannel(Context context, long channelId, List<ProgramItem> items, boolean includeProgress) {
        ContentResolver resolver = context.getContentResolver();
        try {
            Map<String, Long> existing = new HashMap<>(); // contentId -> programId
            Uri channelProgramsUri = TvContractCompat.buildPreviewProgramsUriForChannel(channelId);

            try (Cursor cursor = resolver.query(
                    channelProgramsUri,
                    PreviewProgram.PROJECTION,
                    null,
                    null,
                    null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        try {
                            PreviewProgram p = PreviewProgram.fromCursor(cursor);
                            String cid = p.getContentId();
                            if (!TextUtils.isEmpty(cid)) {
                                existing.put(cid, p.getId());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            Set<String> targetIds = new HashSet<>();
            int weight = items.size();

            for (ProgramItem item : items) {
                targetIds.add(item.contentId);

                PreviewProgram.Builder b = new PreviewProgram.Builder();
                b.setChannelId(channelId)
                        .setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                        .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
                        .setBrowsable(true)
                        .setTitle(item.title)
                        .setContentId(item.contentId)
                        .setWeight(weight--)
                        .setIntentUri(item.intentUri);

                if (!TextUtils.isEmpty(item.description)) b.setDescription(item.description);
                if (!TextUtils.isEmpty(item.posterUri)) b.setPosterArtUri(Uri.parse(item.posterUri));

                if (includeProgress && item.position > 0 && item.duration > 0) {
                    b.setLastPlaybackPositionMillis((int) Math.max(0, item.position))
                            .setDurationMillis((int) Math.max(0, item.duration));
                }

                PreviewProgram program = b.build();

                if (existing.containsKey(item.contentId)) {
                    long pid = existing.get(item.contentId);
                    resolver.update(
                            TvContractCompat.buildPreviewProgramUri(pid),
                            program.toContentValues(),
                            null,
                            null
                    );
                } else {
                    resolver.insert(
                            TvContractCompat.PreviewPrograms.CONTENT_URI,
                            program.toContentValues()
                    );
                }
            }

            // Remove no longer present items
            for (Map.Entry<String, Long> entry : existing.entrySet()) {
                if (!targetIds.contains(entry.getKey())) {
                    resolver.delete(
                            TvContractCompat.buildPreviewProgramUri(entry.getValue()),
                            null,
                            null
                    );
                }
            }
            for (ProgramItem item : items) {
                if (TextUtils.isEmpty(item.posterUrl) || item.posterUri.startsWith("content://")) continue;
                sPosterExecutor.execute(() -> updatePoster(context, channelId, item));
            }
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "Failed to sync programs for channel %d: %s", channelId, t.getMessage());
        }
    }

    private static String cachedPosterUri(Context context, String url) {
        String fallback = fallbackPosterUri(context);
        if (TextUtils.isEmpty(url)) return fallback;
        try {
            File poster = posterFile(context, url);
            if (!poster.isFile() || poster.length() == 0) return fallback;
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", poster);
            if (grantPosterToHome(context, uri)) {
                poster.setLastModified(System.currentTimeMillis());
                return uri.toString();
            }
        } catch (Throwable error) {
            SpiderDebug.log("tv-home", "Cached poster access failed: %s", error.getMessage());
        }
        return fallback;
    }

    private static String posterUri(Context context, String url) {
        String fallback = fallbackPosterUri(context);
        if (TextUtils.isEmpty(url)) return fallback;
        try {
            File poster = posterFile(context, url);
            File directory = poster.getParentFile();
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) return fallback;
            if (!poster.isFile() || poster.length() == 0) {
                FutureTarget<Bitmap> target = Glide.with(context).asBitmap().load(ImgUtil.getUrl(url)).override(360, 540).submit();
                try {
                    Bitmap bitmap = target.get(6, TimeUnit.SECONDS);
                    File pending = new File(directory, poster.getName() + ".tmp");
                    try (FileOutputStream output = new FileOutputStream(pending)) {
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) throw new IllegalStateException("poster encode failed");
                    }
                    if (!pending.renameTo(poster)) throw new IllegalStateException("poster cache rename failed");
                } finally {
                    target.cancel(true);
                }
            }
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", poster);
            if (grantPosterToHome(context, uri)) {
                poster.setLastModified(System.currentTimeMillis());
                return uri.toString();
            }
        } catch (Throwable error) {
            SpiderDebug.log("tv-home", "Poster cache failed: %s", error.getMessage());
        }
        return fallback;
    }

    private static File posterFile(Context context, String url) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte value : hash) name.append(String.format("%02x", value & 0xff));
        return new File(new File(context.getFilesDir(), "tv_home_posters"), name + ".jpg");
    }

    private static String fallbackPosterUri(Context context) {
        return "android.resource://" + context.getPackageName() + "/" + R.drawable.artwork;
    }

    private static void updatePoster(Context context, long channelId, ProgramItem item) {
        String resolved = posterUri(context, item.posterUrl);
        if (!resolved.startsWith("content://")) return;
        if (!TextUtils.isEmpty(item.siteKey) && !item.siteKey.equals(VodConfig.get().getHome().getKey())) return;
        try (Cursor cursor = context.getContentResolver().query(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId), PreviewProgram.PROJECTION, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                PreviewProgram program = PreviewProgram.fromCursor(cursor);
                if (!item.contentId.equals(program.getContentId())) continue;
                ContentValues values = new ContentValues();
                values.put(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI, resolved);
                context.getContentResolver().update(TvContractCompat.buildPreviewProgramUri(program.getId()), values, null, null);
                break;
            }
        } catch (Throwable error) {
            SpiderDebug.log("tv-home", "Poster update failed: %s", error.getMessage());
        }
    }

    private static boolean grantPosterToHome(Context context, Uri uri) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homes = context.getPackageManager().queryIntentActivities(homeIntent, 0);
        boolean granted = false;
        for (ResolveInfo home : homes) {
            if (home.activityInfo == null) continue;
            try {
                context.grantUriPermission(home.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                granted = true;
            } catch (Throwable error) {
                SpiderDebug.log("tv-home", "Poster grant failed for %s: %s", home.activityInfo.packageName, error.getMessage());
            }
        }
        return granted;
    }

    private static void trimPosterCache(Context context) {
        File[] posters = new File(context.getFilesDir(), "tv_home_posters").listFiles((dir, name) -> name.endsWith(".jpg"));
        if (posters == null || posters.length <= MAX_POSTER_FILES) return;
        Arrays.sort(posters, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < posters.length - MAX_POSTER_FILES; i++) posters[i].delete();
    }

    private static class ProgramItem {
        String contentId;
        String siteKey;
        String title;
        String description;
        String posterUri;
        String posterUrl;
        Uri intentUri;
        long position;
        long duration;
    }
}
