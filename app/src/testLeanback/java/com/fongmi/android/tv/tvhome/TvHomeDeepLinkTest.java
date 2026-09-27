package com.fongmi.android.tv.tvhome;

import android.content.Intent;
import android.net.Uri;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TvHomeDeepLinkTest {

    @Test
    public void testBuildAndParseDeepLink() {
        String key = "douban";
        String id = "123456";
        String name = "流浪地球 2";
        String pic = "https://example.com/poster.jpg?token=abc&v=1";
        String mark = "HD中字";
        String wallPic = "https://example.com/wall.jpg";

        Uri uri = TvHomeDeepLink.buildUri(key, id, name, pic, mark, wallPic);
        Assert.assertNotNull(uri);
        Assert.assertEquals("webhtv", uri.getScheme());
        Assert.assertEquals("vod", uri.getAuthority());
        Assert.assertEquals(key, uri.getQueryParameter("key"));
        Assert.assertEquals(id, uri.getQueryParameter("id"));
        Assert.assertEquals(name, uri.getQueryParameter("name"));
        Assert.assertEquals(pic, uri.getQueryParameter("pic"));
        Assert.assertEquals(mark, uri.getQueryParameter("mark"));
        Assert.assertEquals(wallPic, uri.getQueryParameter("wallPic"));

        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        Assert.assertTrue(TvHomeDeepLink.isDeepLink(intent));
    }

    @Test
    public void testDeepLinkHome() {
        Uri homeUri = TvHomeDeepLink.buildHomeUri();
        Assert.assertEquals("webhtv", homeUri.getScheme());
        Assert.assertEquals("home", homeUri.getAuthority());

        Intent intent = new Intent(Intent.ACTION_VIEW, homeUri);
        Assert.assertTrue(TvHomeDeepLink.isDeepLink(intent));
    }

    @Test
    public void testInvalidDeepLink() {
        Intent normalIntent = new Intent(Intent.ACTION_MAIN);
        Assert.assertFalse(TvHomeDeepLink.isDeepLink(normalIntent));

        Intent otherScheme = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/video"));
        Assert.assertFalse(TvHomeDeepLink.isDeepLink(otherScheme));
    }

    @Test
    public void testSuppressionStore() {
        String siteKey = "site_a";
        String vodId = "vod_123";

        Assert.assertFalse(TvHomeSuppressionStore.isSuppressed(siteKey, vodId));
        TvHomeSuppressionStore.suppress(siteKey, vodId);
        Assert.assertTrue(TvHomeSuppressionStore.isSuppressed(siteKey, vodId));

        TvHomeSuppressionStore.unsuppress(siteKey, vodId);
        Assert.assertFalse(TvHomeSuppressionStore.isSuppressed(siteKey, vodId));
    }
}
