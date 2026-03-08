package cn.binbin323.statuslyricext.provider;

import android.media.MediaMetadata;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import cn.binbin323.statuslyricext.provider.utils.LyricSearchUtil;

public class BinLrcProvider implements ILrcProvider {

    private static final String TAG = "BinLrcProvider";

    // Use LAN IP on real devices, or 10.0.2.2 when testing with Android emulator.
    private static final String BIN_LRC_SERVICE_URL = "http://192.168.137.1:3000/api/lyric";

    @Override
    public LyricResult getLyric(MediaMetadata data) throws IOException {
        String title = safeValue(data.getString(MediaMetadata.METADATA_KEY_TITLE));
        if (TextUtils.isEmpty(title)) {
            return null;
        }

        String artist = safeValue(data.getString(MediaMetadata.METADATA_KEY_ARTIST));
        String album = safeValue(data.getString(MediaMetadata.METADATA_KEY_ALBUM));
        long duration = data.getLong(MediaMetadata.METADATA_KEY_DURATION);

        String url = Uri.parse(BIN_LRC_SERVICE_URL)
                .buildUpon()
                .appendQueryParameter("title", title)
                .appendQueryParameter("artist", artist)
                .appendQueryParameter("album", album)
                .appendQueryParameter("duration", String.valueOf(duration))
                .build()
                .toString();

        String body = getTextResponse(url);
        if (TextUtils.isEmpty(body)) {
            return null;
        }

        LyricResult result = parseLyricResult(data, body, title, artist, album);
        if (result == null || !LyricSearchUtil.isLyricContent(result.mLyric)) {
            return null;
        }
        return result;
    }

    private static LyricResult parseLyricResult(MediaMetadata metadata, String body,
                                                String fallbackTitle,
                                                String fallbackArtist,
                                                String fallbackAlbum) {
        try {
            JSONObject root = new JSONObject(body);
            String lyric = pickLyric(root);
            if (TextUtils.isEmpty(lyric)) {
                return null;
            }

            LyricResult result = new LyricResult();
            result.mLyric = lyric;
            long distance = root.optLong("distance", -1);
            if (distance >= 0) {
                result.mDistance = distance;
                return result;
            }

            String title = pickText(root, "title", fallbackTitle);
            String artist = pickText(root, "artist", fallbackArtist);
            String album = pickText(root, "album", fallbackAlbum);
            result.mDistance = LyricSearchUtil.getMetadataDistance(metadata, title, artist, album);
            return result;
        } catch (JSONException ignore) {
            LyricResult result = new LyricResult();
            result.mLyric = body;
            result.mDistance = 0;
            return result;
        }
    }

    private static String pickLyric(JSONObject root) {
        String direct = root.optString("lyric", "");
        if (!TextUtils.isEmpty(direct)) {
            return direct;
        }

        String lrc = root.optString("lrc", "");
        if (!TextUtils.isEmpty(lrc)) {
            return lrc;
        }

        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            String dataLyric = data.optString("lyric", "");
            if (!TextUtils.isEmpty(dataLyric)) {
                return dataLyric;
            }
            String dataLrc = data.optString("lrc", "");
            if (!TextUtils.isEmpty(dataLrc)) {
                return dataLrc;
            }
        }

        JSONObject lrcObject = root.optJSONObject("lrc");
        if (lrcObject != null) {
            String nested = lrcObject.optString("lyric", "");
            if (!TextUtils.isEmpty(nested)) {
                return nested;
            }
        }

        return "";
    }

    private static String pickText(JSONObject root, String key, String fallback) {
        String value = root.optString(key, "");
        if (!TextUtils.isEmpty(value)) {
            return value;
        }
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            value = data.optString(key, "");
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return fallback;
    }

    private static String getTextResponse(String urlString) throws IOException {
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            connection.setRequestProperty("User-Agent", "StatusBarLyricExt/1.0");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.connect();

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "request failed, code=" + code + ", url=" + urlString);
                return null;
            }

            in = connection.getInputStream();
            return new String(readStream(in), StandardCharsets.UTF_8);
        } finally {
            if (in != null) {
                in.close();
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        return out.toByteArray();
    }

    private static String safeValue(String value) {
        return value == null ? "" : value;
    }
}
