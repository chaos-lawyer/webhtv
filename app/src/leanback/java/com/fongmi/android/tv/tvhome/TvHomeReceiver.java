package com.fongmi.android.tv.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.text.TextUtils;

import androidx.tvprovider.media.tv.TvContractCompat;
import androidx.tvprovider.media.tv.WatchNextProgram;

import com.fongmi.android.tv.db.AppDatabase;
import com.github.catvod.crawler.SpiderDebug;

public class TvHomeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        SpiderDebug.log("tv-home", "TvHomeReceiver received action: %s", action);

        if (TvContractCompat.ACTION_INITIALIZE_PROGRAMS.equals(action)) {
            TvHomeManager.init(context);
            TvHomeManager.syncAll();
        } else if (TvContractCompat.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED.equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                long programId = intent.getLongExtra(TvContractCompat.EXTRA_WATCH_NEXT_PROGRAM_ID, -1);
                if (programId > 0) {
                    handleWatchNextDisabled(context, programId);
                }
            }
        }
    }

    private void handleWatchNextDisabled(Context context, long programId) {
        try (Cursor cursor = context.getContentResolver().query(
                TvContractCompat.buildWatchNextProgramUri(programId),
                WatchNextProgram.PROJECTION,
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                WatchNextProgram program = WatchNextProgram.fromCursor(cursor);
                String contentId = program.getContentId();
                if (!TextUtils.isEmpty(contentId)) {
                    String[] parts = contentId.split(AppDatabase.SYMBOL);
                    if (parts.length >= 2) {
                        TvHomeSuppressionStore.suppress(parts[0], parts[1]);
                        SpiderDebug.log("tv-home", "WatchNext program suppressed via broadcast: %s", contentId);
                    }
                }
            }
        } catch (Throwable t) {
            SpiderDebug.log("tv-home", "Failed to handle watch next disabled: %s", t.getMessage());
        }
    }
}
