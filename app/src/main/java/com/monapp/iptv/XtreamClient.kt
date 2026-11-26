package com.monapp.iptv

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

interface XtreamApi {
    @GET("player_api.php")
    suspend fun login(@Query("username") u: String, @Query("password") p: String): LoginResponse

    @GET("player_api.php")
    suspend fun categories(
        @Query("username") u: String, @Query("password") p: String,
        @Query("action") action: String
    ): List<Category>

    @GET("player_api.php")
    suspend fun live(
        @Query("username") u: String, @Query("password") p: String,
        @Query("action") action: String = "get_live_streams"
    ): List<Live>

    @GET("player_api.php")
    suspend fun movies(
        @Query("username") u: String, @Query("password") p: String,
        @Query("action") action: String = "get_vod_streams"
    ): List<Movie>

    @GET("player_api.php")
    suspend fun series(
        @Query("username") u: String, @Query("password") p: String,
        @Query("action") action: String = "get_series"
    ): List<Series>

    @GET("player_api.php")
    suspend fun vodInfoRaw(
        @Query("username") u: String, @Query("password") p: String,
        @Query("vod_id") id: Int,
        @Query("action") action: String = "get_vod_info"
    ): ResponseBody

    @GET("player_api.php")
    suspend fun seriesInfoRaw(
        @Query("username") u: String, @Query("password") p: String,
        @Query("series_id") id: Int,
        @Query("action") action: String = "get_series_info"
    ): ResponseBody
}

private fun JSONObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = optString(k, "")
        if (v.isNotBlank() && v != "null") return v
    }
    return null
}

class XtreamClient(server: String, val user: String, val pass: String) {
    val base: String = normalize(server)

    private val api: XtreamApi = Retrofit.Builder()
        .baseUrl("$base/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(XtreamApi::class.java)

    suspend fun login(): Boolean = api.login(user, pass).user_info?.status == "Active"

    suspend fun categories(s: Section): List<Category> = api.categories(
        user, pass,
        when (s) {
            Section.LIVE -> "get_live_categories"
            Section.FILMS -> "get_vod_categories"
            Section.SERIES -> "get_series_categories"
        }
    )

    suspend fun items(s: Section): List<Item> = when (s) {
        Section.LIVE -> api.live(user, pass).map {
            Item(Section.LIVE, it.stream_id ?: 0, it.name.orEmpty(), it.stream_icon, null, it.category_id)
        }
        Section.FILMS -> api.movies(user, pass).map {
            Item(
                Section.FILMS, it.stream_id ?: 0, it.name.orEmpty(), it.stream_icon,
                it.container_extension, it.category_id, it.added?.toLongOrNull() ?: 0L
            )
        }
        Section.SERIES -> api.series(user, pass).map {
            Item(
                Section.SERIES, it.series_id ?: 0, it.name.orEmpty(), it.cover, null,
                it.category_id, it.last_modified?.toLongOrNull() ?: 0L
            )
        }
    }

    suspend fun vodMeta(id: Int): Meta? = withContext(Dispatchers.IO) {
        try {
            val j = JSONObject(api.vodInfoRaw(user, pass, id).string())
            j.optJSONObject("info")?.let { metaFrom(it) }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun seriesDetail(id: Int): Pair<Meta?, List<Pair<String, List<Ep>>>> =
        withContext(Dispatchers.IO) {
            val j = JSONObject(api.seriesInfoRaw(user, pass, id).string())
            val meta = j.optJSONObject("info")?.let { metaFrom(it) }
            val seasons = mutableListOf<Pair<String, List<Ep>>>()
            val obj = j.optJSONObject("episodes")
            if (obj != null) {
                val keys = obj.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
                for (k in keys) seasons.add(k to parseEps(obj.optJSONArray(k)))
            } else {
                val arr = j.optJSONArray("episodes")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        seasons.add((i + 1).toString() to parseEps(arr.optJSONArray(i)))
                    }
                }
            }
            Pair(meta, seasons.toList())
        }

    private fun parseEps(a: JSONArray?): List<Ep> {
        if (a == null) return emptyList()
        val out = mutableListOf<Ep>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.str("id") ?: continue
            val info = o.optJSONObject("info")
            out.add(
                Ep(
                    id = id,
                    title = o.str("title") ?: "Épisode",
                    ext = o.str("container_extension"),
                    num = o.optInt("episode_num", 0).takeIf { it > 0 },
                    image = info?.str("movie_image"),
                    duration = info?.str("duration")
                )
            )
        }
        return out
    }

    private fun metaFrom(o: JSONObject) = Meta(
        plot = o.str("plot", "description"),
        genre = o.str("genre"),
        year = o.str("releasedate", "releaseDate", "release_date", "year")?.take(4),
        rating = o.str("rating")?.takeIf { it != "0" && it != "0.0" && it != "0.00" },
        duration = o.str("duration"),
        cast = o.str("cast", "actors")
    )

    fun liveUrl(id: Int) = "$base/live/$user/$pass/$id.m3u8"
    fun liveTsUrl(id: Int) = "$base/live/$user/$pass/$id.ts"
    fun movieUrl(id: Int, ext: String?) = "$base/movie/$user/$pass/$id.${ext ?: "mp4"}"
    fun episodeUrl(id: String, ext: String?) = "$base/series/$user/$pass/$id.${ext ?: "mp4"}"

    private fun normalize(s: String): String {
        val t = s.trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
    }
}
