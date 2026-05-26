package eu.kanade.tachiyomi.animeextension.en.anikage

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toRequestBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class Anikage :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val baseUrl: String = "https://anikage.cc"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val name: String = "Anikage"

    private val preferences by getPreferencesLazy()

    override fun getFilterList(): AnimeFilterList = AnikageFilters.FILTER_LIST

    override fun popularAnimeRequest(page: Int): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                put("type", "ANIME")
                put("page", page)
                put("sort", "TRENDING_DESC")
                put("isAdult", preferences.isAdult)
            }
            put("query", QUERY)
        }

        return buildPost(data)
    }

    override fun popularAnimeParse(response: Response) = parseAnime(response)

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val searchParams = AnikageFilters.getSearchParameters(filters)
        val data = buildJsonObject {
            putJsonObject("variables") {
                if (query != "") put("search", query)
                if (searchParams.season != "ALL") put("season", searchParams.season)
                if (searchParams.origin != "ALL") put("countryOfOrigin", searchParams.origin)
                if (searchParams.types != "ALL") put("format_in", searchParams.types)
                if (searchParams.releaseYear != "ALL") put("seasonYear", searchParams.releaseYear.toInt())
                if (searchParams.genres.count() != 0) {
                    putJsonArray("genre_in") {
                        searchParams.genres.forEach {
                            add(it)
                        }
                    }
                }
                put("page", page)
                put("sort", searchParams.sortBy)
                put("isAdult", preferences.isAdult)
            }
            put("query", QUERY)
        }
        return buildPost(data)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnime(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                put("type", "ANIME")
                put("page", page)
                put("sort", "ID_DESC")
                put("isAdult", preferences.isAdult)
            }
            put("query", QUERY)
        }
        return buildPost(data)
    }

    override fun latestUpdatesParse(response: Response) = parseAnime(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val soup = response.asJsoup()
        val studioTag = soup.selectFirst("div.flex.uppercase")
        val studioNameDiv = studioTag?.nextElementSibling()

        val englishName = soup.selectFirst("h1.text-center.tracking-tighter")?.text()
        val romajiName = soup.selectFirst("h2.text-center.line-clamp-2")?.text()

        val titleName = if (preferences.titleStyle == "english") {
            englishName
        } else {
            romajiName
        }

        val authorName = studioNameDiv
            ?.select("span.cursor-default")
            ?.eachText()?.joinToString(", ").orEmpty()

        val statusName = soup.selectFirst("span.uppercase.font-semibold")?.text()

        return SAnime.create().apply {
            title = titleName ?: ""
            author = authorName
            update_strategy = if (statusName == "Finished") {
                AnimeUpdateStrategy.ONLY_FETCH_ONCE
            } else {
                AnimeUpdateStrategy.ALWAYS_UPDATE
            }
            status = when (statusName) {
                "Finished" -> SAnime.COMPLETED
                "Airing" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.split("/").last()
        val token = makeToken(id.toInt())
        val getHeaders = headersBuilder()
            .add("Referer", "$baseUrl${anime.url}")
            .add("Origin", baseUrl)
            .add("Accept", "*/*")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .build()

        return GET(token.animeEpisodeBuilder(), headers = getHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val referer = response.request.header("Referer").orEmpty()
        val animeId = referer.substringAfterLast("/")

        val provider = if (preferences.subOrDub == "dub") {
            preferences.dubSource
        } else {
            preferences.subSource
        }

        val jsonResponse = response.parseAs<List<EpisodeResult>>()
        val episode = jsonResponse.reversed().map {
            SEpisode.create().apply {
                episode_number = it.number.toFloat()
                name = if (!it.title.isNullOrBlank()) {
                    "Episode ${it.number} - ${it.title}"
                } else {
                    "Episode ${it.number}"
                }
                date_upload = 0L
                url = animeEpisodeUrlFormat(
                    animeId.toInt(),
                    provider,
                    it.number,
                    preferences.subOrDub ?: "sub",
                )
            }
        }
        return episode
    }

    // Video Links

    override fun videoListRequest(episode: SEpisode): Request {
        val animeId = episode.url.substringAfterLast("/").substringBefore("?")

        val provider = if (preferences.subOrDub == "dub") {
            preferences.dubSource
        } else {
            preferences.subSource
        }

        val token = makeSourcesToken(
            animeId.toInt(),
            episode.episode_number.toInt(),
            provider,
        )

        val getHeaders = headersBuilder()
            .add("Referer", episode.url)
            .add("Origin", baseUrl)
            .add("Accept", "*/*")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .build()

        return GET(token.episodeUrlBuilder(), headers = getHeaders)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).await()
        val episodeData = response.parseAs<EpisodeSource>()

        val getHeaders = headers.newBuilder().apply {
            episodeData.headers.referer?.let { set("Referer", it) }
            episodeData.headers.origin?.let { set("Origin", it) }
            episodeData.headers.userAgent?.let { set("User-Agent", it) }
        }.build()

        val tracks = episodeData.subtitles.map {
            Track(it.url, it.lang)
        }

        val videos = episodeData.sources.map {
            Video(
                url = it.url,
                quality = it.quality,
                videoUrl = it.url,
                subtitleTracks = tracks,
                headers = getHeaders,
            )
        }
        return videos
    }

    // Utils

    fun Int.animeUrlBuilder(): String = "/anime/info/$this"
    fun String.animeEpisodeBuilder(): String = "$baseUrl/api/anime/episodes/$this"
    fun String.episodeUrlBuilder(): String = "$baseUrl/api/anime/sources/$this"

    fun animeEpisodeUrlFormat(id: Int, host: String, episodeId: Int, type: String): String = "$baseUrl/anime/watch/$id?host=$host&ep=$episodeId&type=$type"

    fun parseAnime(response: Response): AnimesPage {
        val jsonData = response.parseAs<GraphQLResult>()

        val media = jsonData.data.page.media

        val pageInfo = jsonData.data.page.pageInfo
        val hasNextPage = pageInfo.hasNextPage

        val animes = media.map {
            val id = it.id
            val titleFormat = preferences.titleStyle ?: "romaji"
            val titleName = if (titleFormat == "english") {
                it.title.english ?: it.title.romaji
            } else {
                it.title.romaji
            }

            SAnime.create().apply {
                setUrlWithoutDomain(id.animeUrlBuilder())
                thumbnail_url = it.coverImage.extraLarge
                title = titleName
                description = it.description
                status = when (it.status) {
                    "FINISHED" -> SAnime.COMPLETED
                    "RELEASING" -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
                update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
                genre = it.genres.joinToString(", ")
            }
        }

        return AnimesPage(animes, hasNextPage)
    }

    fun makeSourcesToken(
        animeId: Int,
        ep: Int,
        provider: String,
        type: String = preferences.subOrDub ?: "sub",
    ): String {
        val payload = JSONObject().apply {
            put("id", animeId)
            put("epNum", ep)
            put("host", provider)
            put("type", type)
            put("_t", (System.currentTimeMillis() / 1000).toString())
        }.toString()

        Log.e("Anikage", "Payload: $payload")

        val raw = payload.toByteArray(Charsets.UTF_8)
        val key = preferences.apiKey.toByteArray()

        val out = ByteArray(raw.size)
        for (i in raw.indices) {
            out[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        return Base64.encodeToString(out, Base64.NO_WRAP)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
    }

    fun makeToken(animeId: Int, refresh: Boolean = false): String {
        val payload = JSONObject().apply {
            put("id", animeId)
            put("refresh", refresh.toString().lowercase())
            put("_t", (System.currentTimeMillis() / 1000).toString())
        }.toString()

        Log.e("Anikage", "Payload: $payload")

        val raw = payload.toByteArray(Charsets.UTF_8)

        val out = ByteArray(raw.size)

        val key = preferences.apiKey.toByteArray()

        for (i in raw.indices) {
            out[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        return Base64.encodeToString(out, Base64.NO_WRAP)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
    }

    private fun buildPost(dataObject: JsonObject): Request {
        val payload = dataObject.toRequestBody()

        val postHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Content-Length", payload.contentLength().toString())
            add("Content-Type", payload.contentType().toString())
            add("Host", ANILIST_API.toHttpUrl().host)
            add("Origin", baseUrl)
            add("Referer", "$ANILIST_API/")
        }.build()

        return POST(ANILIST_API, headers = postHeaders, body = payload)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SITE_TITLE_FORMAT
            title = "Preferred Title Style"
            entries = arrayOf("english", "romaji")
            entryValues = arrayOf("english", "romaji")
            setDefaultValue(PREF_SITE_TITLE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ADULT_KEY
            title = "Enable NSFW Content"
            setDefaultValue(PREF_ADULT_DEFAULT)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_API_KEY
            title = "API key"
            setDefaultValue(PREF_API_DEFAULT)
            summary = "Private API key"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_ISSUBORDUB_SOURCE
            title = "Sub or Dub?"
            entries = arrayOf("sub", "dub")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue(PREF_ISSUBORDUB_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUB_SOURCE
            title = "Preferred Sub Server"
            entries = SUB_PROVIDER
            entryValues = SUB_PROVIDER
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_DUB_SOURCE
            title = "Preferred Dub Server"
            entries = DUB_PROVIDER
            entryValues = DUB_PROVIDER
            setDefaultValue(PREF_DUB_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    private val SharedPreferences.titleStyle
        get() = getString(PREF_SITE_TITLE_FORMAT, PREF_SITE_TITLE_DEFAULT)

    private val SharedPreferences.isAdult
        get() = getBoolean(PREF_ADULT_KEY, PREF_ADULT_DEFAULT)

    private val SharedPreferences.apiKey
        get() = getString(PREF_API_KEY, PREF_API_DEFAULT)!!

    private val SharedPreferences.subOrDub
        get() = getString(PREF_ISSUBORDUB_SOURCE, PREF_ISSUBORDUB_DEFAULT)

    private val SharedPreferences.subSource
        get() = getString(PREF_SUB_SOURCE, PREF_SUB_DEFAULT)!!

    private val SharedPreferences.dubSource
        get() = getString(PREF_DUB_SOURCE, PREF_DUB_DEFAULT)!!

    companion object {

        private const val ANILIST_API = "https://graphql.anilist.co"
        private const val PREF_ADULT_KEY = "nsfw"
        private const val PREF_ADULT_DEFAULT = false

        private const val PREF_API_KEY = "private_api_key"
        private const val PREF_API_DEFAULT = "x9f2k7m4q1w8e3r6t5y0"

        private val SUB_PROVIDER = arrayOf(
            "uwu",
            "mochi",
            "mimi",
            "kami",
            "vee",
            "shiro",
            "wave",
            "zaza",
        )
        private val DUB_PROVIDER = arrayOf(
            "uwu",
            "mochi",
            "kami",
        )

        private const val PREF_SUB_SOURCE = "preferred_sub_source"
        private val PREF_SUB_DEFAULT = SUB_PROVIDER.first()

        private const val PREF_DUB_SOURCE = "preferred_dub_source"
        private val PREF_DUB_DEFAULT = DUB_PROVIDER.first()

        private const val PREF_ISSUBORDUB_SOURCE = "is_sub_or_dub"
        private const val PREF_ISSUBORDUB_DEFAULT = "sub"
        private const val PREF_SITE_TITLE_FORMAT = "title_format"
        private const val PREF_SITE_TITLE_DEFAULT = "english"
    }
}
