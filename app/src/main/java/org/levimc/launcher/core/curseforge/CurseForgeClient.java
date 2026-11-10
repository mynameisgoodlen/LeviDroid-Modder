package org.levimc.launcher.core.curseforge;

import org.levimc.launcher.BuildConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.levimc.launcher.core.curseforge.models.CategoryListResponse;
import org.levimc.launcher.core.curseforge.models.Content;
import org.levimc.launcher.core.curseforge.models.ContentCategory;
import org.levimc.launcher.core.curseforge.models.ContentGameVersion;
import org.levimc.launcher.core.curseforge.models.ContentSearchResponse;
import org.levimc.launcher.core.curseforge.models.ModFilesResponse;
import org.levimc.launcher.core.curseforge.models.Pagination;
import org.levimc.launcher.core.curseforge.models.StringResponse;
import org.levimc.launcher.core.curseforge.models.VersionListResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class CurseForgeClient {
    private static final String TAG = "CurseForgeClient";
    private static final String BASE_URL = "https://api.curseforge.com";
    private static final String API_KEY = BuildConfig.CURSEFORGE_API_KEY;

    public static final int GAME_ID_MINECRAFT = 78022;

    public static final String SORT_POPULARITY = "2";
    public static final String SORT_LAST_UPDATED = "3";
    public static final String SORT_NAME = "4";
    public static final String SORT_TOTAL_DOWNLOADS = "6";
    
    private final OkHttpClient client;
    private final Gson gson;

    private static CurseForgeClient instance;

    private CurseForgeClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    public static synchronized CurseForgeClient getInstance() {
        if (instance == null) {
            instance = new CurseForgeClient();
        }
        return instance;
    }

    public static boolean isConfigured() {
        return API_KEY != null && !API_KEY.isEmpty();
    }

    private static IOException httpError(Response response) {
        int code = response.code();
        if (code == 401 || code == 403) {
            String reason = API_KEY == null || API_KEY.isEmpty()
                    ? "no CurseForge API key in this build (set curseforge.api_key in local.properties)"
                    : "CurseForge rejected the API key";
            return new IOException(reason + " (HTTP " + code + ")");
        }
        return new IOException("Unexpected code " + response);
    }

    public interface CurseForgeCallback<T> {
        void onSuccess(T result);
        void onError(Throwable t);
    }

    public void searchContent(String query, int classId, List<Integer> categoryIds, List<String> gameVersions, int index, int pageSize, String sortField, String sortOrder, CurseForgeCallback<ContentSearchResponse> callback) {
        List<Integer> cats = categoryIds != null ? categoryIds : new ArrayList<>();
        List<String> versions = gameVersions != null ? gameVersions : new ArrayList<>();
        if (cats.size() <= 1 && versions.size() <= 1) {
            requestSearch(query, classId, cats.isEmpty() ? 0 : cats.get(0), versions.isEmpty() ? null : versions.get(0), index, pageSize, sortField, sortOrder, callback);
            return;
        }

        List<SubFilter> subs = new ArrayList<>();
        if (cats.isEmpty()) {
            for (String ver : versions) subs.add(new SubFilter(0, ver));
        } else if (versions.isEmpty()) {
            for (int cat : cats) subs.add(new SubFilter(cat, null));
        } else {
            for (int cat : cats) {
                for (String ver : versions) subs.add(new SubFilter(cat, ver));
            }
        }

        AtomicInteger pending = new AtomicInteger(subs.size());
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicReference<Throwable> lastError = new AtomicReference<>();
        AtomicLong totalCount = new AtomicLong();
        List<Content> collected = Collections.synchronizedList(new ArrayList<>());
        for (SubFilter sub : subs) {
            final int subCat = sub.categoryId;
            final String subVersion = sub.gameVersion;
            requestSearch(query, classId, subCat, subVersion, index, pageSize, sortField, sortOrder, new CurseForgeCallback<ContentSearchResponse>() {
                @Override
                public void onSuccess(ContentSearchResponse result) {
                    if (result != null) {
                        if (result.pagination != null) totalCount.addAndGet(result.pagination.totalCount);
                        if (result.data != null) collected.addAll(result.data);
                    }
                    finishMerge(pending, failed, lastError, collected, totalCount, index, sortField, callback);
                }

                @Override
                public void onError(Throwable error) {
                    failed.set(true);
                    lastError.set(error);
                    finishMerge(pending, failed, lastError, collected, totalCount, index, sortField, callback);
                }
            });
        }
    }

    private void requestSearch(String query, int classId, int categoryId, String gameVersion, int index, int pageSize, String sortField, String sortOrder, CurseForgeCallback<ContentSearchResponse> callback) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + "/v1/mods/search").newBuilder();
        urlBuilder.addQueryParameter("gameId", String.valueOf(GAME_ID_MINECRAFT));
        urlBuilder.addQueryParameter("sortField", sortField != null ? sortField : SORT_POPULARITY);
        urlBuilder.addQueryParameter("sortOrder", sortOrder != null ? sortOrder : "desc");
        urlBuilder.addQueryParameter("index", String.valueOf(index));
        urlBuilder.addQueryParameter("pageSize", String.valueOf(pageSize));
        
        if (query != null && !query.isEmpty()) {
            urlBuilder.addQueryParameter("searchFilter", query);
        }
        if (classId > 0) {
            urlBuilder.addQueryParameter("classId", String.valueOf(classId));
        }
        if (categoryId > 0) {
            urlBuilder.addQueryParameter("categoryId", String.valueOf(categoryId));
        }
        if (gameVersion != null && !gameVersion.isEmpty()) {
            urlBuilder.addQueryParameter("gameVersion", gameVersion);
        }

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .addHeader("x-api-key", API_KEY)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    callback.onError(httpError(response));
                    return;
                }
                
                try {
                    String json = response.body().string();
                    ContentSearchResponse contentResponse = gson.fromJson(json, ContentSearchResponse.class);
                    callback.onSuccess(contentResponse);
                } catch (Exception e) {
                    callback.onError(e);
                }
            }
        });
    }

    private void finishMerge(AtomicInteger pending, AtomicBoolean failed, AtomicReference<Throwable> lastError, List<Content> collected, AtomicLong totalCount, int index, String sortField, CurseForgeCallback<ContentSearchResponse> callback) {
        if (pending.decrementAndGet() != 0) {
            return;
        }
        if (collected.isEmpty() && failed.get()) {
            callback.onError(lastError.get());
            return;
        }
        List<Content> unique = new ArrayList<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        List<Content> sorted = new ArrayList<>(collected);
        Comparator<Content> cmp = comparatorFor(sortField);
        if (cmp != null) {
            java.util.Collections.sort(sorted, cmp);
        }
        for (Content item : sorted) {
            if (item != null && seen.add(item.id)) {
                unique.add(item);
            }
        }
        ContentSearchResponse merged = new ContentSearchResponse();
        merged.data = unique;
        Pagination pagination = new Pagination();
        pagination.index = index;
        pagination.totalCount = totalCount.get() > 0 ? totalCount.get() : unique.size();
        merged.pagination = pagination;
        callback.onSuccess(merged);
    }

    private Comparator<Content> comparatorFor(String sortField) {
        String field = sortField != null ? sortField : SORT_POPULARITY;
        switch (field) {
            case SORT_LAST_UPDATED:
                return (a, b) -> compareNullable(a.dateModified, b.dateModified, true);
            case SORT_NAME:
                return (a, b) -> {
                    String an = a.name != null ? a.name.toLowerCase(Locale.ROOT) : null;
                    String bn = b.name != null ? b.name.toLowerCase(Locale.ROOT) : null;
                    return compareNullable(an, bn, false);
                };
            case SORT_POPULARITY:
            case SORT_TOTAL_DOWNLOADS:
            default:
                return (a, b) -> Long.compare(b.downloadCount, a.downloadCount);
        }
    }

    private int compareNullable(String a, String b, boolean descending) {
        if (a == null && b == null) return 0;
        if (b == null) return descending ? -1 : 1;
        if (a == null) return descending ? 1 : -1;
        return descending ? b.compareTo(a) : a.compareTo(b);
    }

    private static final class SubFilter {
        final int categoryId;
        final String gameVersion;

        SubFilter(int categoryId, String gameVersion) {
            this.categoryId = categoryId;
            this.gameVersion = gameVersion;
        }
    }

    public void getCategories(CurseForgeCallback<List<ContentCategory>> callback) {
        Request request = new Request.Builder()
                .url(BASE_URL + "/v1/categories?gameId=" + GAME_ID_MINECRAFT)
                .addHeader("x-api-key", API_KEY)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    callback.onError(new IOException("Unexpected code " + response));
                    return;
                }

                try {
                    String json = response.body().string();
                    CategoryListResponse categoriesResponse = gson.fromJson(json, CategoryListResponse.class);
                    callback.onSuccess(categoriesResponse != null ? categoriesResponse.data : null);
                } catch (Exception e) {
                    callback.onError(e);
                }
            }
        });
    }

    public void getGameVersions(CurseForgeCallback<List<ContentGameVersion>> callback) {
        Request request = new Request.Builder()
                .url(BASE_URL + "/v1/games/" + GAME_ID_MINECRAFT + "/versions")
                .addHeader("x-api-key", API_KEY)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    callback.onError(new IOException("Unexpected code " + response));
                    return;
                }

                try {
                    String json = response.body().string();
                    VersionListResponse versionsResponse = gson.fromJson(json, VersionListResponse.class);
                    callback.onSuccess(versionsResponse != null ? versionsResponse.data : null);
                } catch (Exception e) {
                    callback.onError(e);
                }
            }
        });
    }

    public void getContentDescription(int contentId, CurseForgeCallback<String> callback) {
        String url = BASE_URL + "/v1/mods/" + contentId + "/description";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("x-api-key", API_KEY)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError(httpError(response));
                    return;
                }

                try {
                    String json = response.body().string();
                    StringResponse stringResponse = gson.fromJson(json, StringResponse.class);
                    callback.onSuccess(stringResponse.data);
                } catch (Exception e) {
                    callback.onError(e);
                }
            }
        });
    }

    public void getModFiles(int modId, int index, int pageSize, CurseForgeCallback<ModFilesResponse> callback) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + "/v1/mods/" + modId + "/files").newBuilder();
        urlBuilder.addQueryParameter("index", String.valueOf(index));
        urlBuilder.addQueryParameter("pageSize", String.valueOf(pageSize));

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .addHeader("x-api-key", API_KEY)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    callback.onError(httpError(response));
                    return;
                }

                try {
                    String json = response.body().string();
                    ModFilesResponse filesResponse = gson.fromJson(json, ModFilesResponse.class);
                    callback.onSuccess(filesResponse);
                } catch (Exception e) {
                    callback.onError(e);
                }
            }
        });
    }
}
