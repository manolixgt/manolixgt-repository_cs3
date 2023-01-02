package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CineHDPlusProvider:MainAPI() {
    override var mainUrl = "https://cinehdplus.org"
    override var name = "CineHDPlus"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("$mainUrl/peliculas/", "Peliculas"),
            // Pair("$mainUrl/estrenos/", "Estrenos"),
        )
        items.add(
            HomePageList(
                "Series",
                app.get("$mainUrl/series/", timeout = 120).document.select("div.card")
                    .map {
                        val title = it.selectFirst("div.card__content h3.a")!!.text()
                        val poster = it.selectFirst("div.card__cover img.lazy")!!.attr("data-src")
                        val url = it.selectFirst("a")!!.attr("href")
                        TvSeriesSearchResponse(
                            title,
                            url,
                            this.name,
                            TvType.TvSeries,
                            poster,
                            null,
                            null,
                        )
                    })
        )
        urls.apmap { (url, name) ->
            val soup = app.get(url).document
            val home = soup.select("div.card").map {
                val title = it.selectFirst("div.card__content h3.a")!!.text()
                val link = it.selectFirst("a")!!.attr("href")
                TvSeriesSearchResponse(
                    title,
                    link,
                    this.name,
                    if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries,
                    it.selectFirst("img.lazy")!!.attr("data-src"),
                    null,
                    null,
                )
            }

            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }
    // private fun Element.toSearchResult(): SearchResponse {
    //     val title = this.select(".listing-content p").text()
    //     val href = this.select("a").attr("href")
    //     val posterUrl = fixUrl(this.select(".Posters-img").attr("src"))
    //     val isMovie = href.contains("/pelicula/")
    //     return if (isMovie) {
    //         MovieSearchResponse(
    //             title,
    //             href,
    //             name,
    //             TvType.Movie,
    //             posterUrl,
    //             null
    //         )
    //     } else {
    //         TvSeriesSearchResponse(
    //             title,
    //             href,
    //             name,
    //             TvType.Movie,
    //             posterUrl,
    //             null,
    //             null
    //         )
    //     }
    // }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query}"
        val document = app.get(url).document

        return document.select("div.card").map {
            val title = it.selectFirst("div.card__content h3.a")!!.text()
            val href = it.selectFirst("a")!!.attr("href")
            val image = it.selectFirst("div.card__cover img.lazy")!!.attr("data-src")
            val isMovie = href.contains("/ver-pelicula/")

            if (isMovie) {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
                    image,
                    null
                )
            } else {
                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.TvSeries,
                    image,
                    null,
                    null
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url).document

        val title = soup.selectFirst("div.details__title h1")?.text()
        val description = soup.selectFirst("div.mCSB_container mCS_y_hidden mCS_no_scrollbar_y p")?.text()?.trim()
        val poster: String? = soup.selectFirst(".lazyloaded")?.attr("src")
        val episodes = soup.select("div.tab-pane .btn").map { li ->
            val href = li.selectFirst("a")?.attr("href")
            val name = li.selectFirst(".btn-primary.btn-block")?.text()?.replace(Regex("(T(\\d+).*E(\\d+):)"),"")?.trim()
            val seasoninfo = href?.substringAfter("temporada/")?.replace("/capitulo/","-")
            val seasonid =
                seasoninfo.let { str ->
                    str?.split("-")?.mapNotNull { subStr -> subStr.toIntOrNull() }
                }
            val isValid = seasonid?.size == 2
            val episode = if (isValid) seasonid?.getOrNull(1) else null
            val season = if (isValid) seasonid?.getOrNull(0) else null
            Episode(
                href!!,
                name,
                season,
                episode,
            )
        }

        val year = soup.selectFirst("div.sub-meta dateCreated")?.text()?.toIntOrNull()
        val tvType = if (url.contains("/ver-pelicula/")) TvType.Movie else TvType.TvSeries
        val tags = soup.select(".p-h-15.text-center a span.font-size-18.text-info.text-semibold")
            .map { it?.text()?.trim().toString().replace(", ","") }

        return when (tvType) {
            TvType.TvSeries -> {
                TvSeriesLoadResponse(
                    title!!,
                    url,
                    this.name,
                    tvType,
                    episodes,
                    fixUrl(poster!!),
                    year,
                    description,
                    null,
                    null,
                    tags,
                )
            }
            TvType.Movie -> {
                MovieLoadResponse(
                    title!!,
                    url,
                    this.name,
                    tvType,
                    url,
                    fixUrl(poster!!),
                    year,
                    description,
                    null,
                    tags,
                )
            }
            else -> null
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("div.MTPlayerTb iframe").apmap {
            val iframe = fixUrl(it.attr("data-src"))
            if (iframe.contains("api.cinehdplus/")) {
                val tomatoRegex =
                    Regex("(\\/\\/api.cineplushd.org\\/ir\\/player.php\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                tomatoRegex.findAll(iframe).map { tomreg ->
                    tomreg.value
                }.toList().apmap { tom ->
                    val tomkey = tom.replace("//apialfa.tomatomatela.club/ir/player.php?h=", "")
                    app.post(
                        "https://apialfa.tomatomatela.club/ir/rd.php", allowRedirects = false,
                        headers = mapOf(
                            "Host" to "apialfa.tomatomatela.club",
                            "User-Agent" to USER_AGENT,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                            "Accept-Language" to "en-US,en;q=0.5",
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Origin" to "null",
                            "DNT" to "1",
                            "Connection" to "keep-alive",
                            "Upgrade-Insecure-Requests" to "1",
                            "Sec-Fetch-Dest" to "iframe",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-Site" to "same-origin",
                        ),
                        data = mapOf(Pair("url", tomkey))
                    ).okhttpResponse.headers.values("location").apmap { loc ->
                        if (loc.contains("goto_ddh.php")) {
                            val gotoregex =
                                Regex("(\\/\\/api.cineplushd.org\\/ir\\/goto_ddh.php\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                            gotoregex.findAll(loc).map { goreg ->
                                goreg.value.replace("//api.cineplushd.org/ir/goto_ddh.php?h=", "")
                            }.toList().apmap { gotolink ->
                                app.post(
                                    "https://api.cineplushd.org/ir/redirect_ddh.php",
                                    allowRedirects = false,
                                    headers = mapOf(
                                        "Host" to "api.cineplushd.org",
                                        "User-Agent" to USER_AGENT,
                                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                                        "Accept-Language" to "en-US,en;q=0.5",
                                        "Content-Type" to "application/x-www-form-urlencoded",
                                        "Origin" to "null",
                                        "DNT" to "1",
                                        "Connection" to "keep-alive",
                                        "Upgrade-Insecure-Requests" to "1",
                                        "Sec-Fetch-Dest" to "iframe",
                                        "Sec-Fetch-Mode" to "navigate",
                                        "Sec-Fetch-Site" to "same-origin",
                                    ),
                                    data = mapOf(Pair("url", gotolink))
                                ).okhttpResponse.headers.values("location").apmap { golink ->
                                    loadExtractor(golink, data, subtitleCallback, callback)
                                }
                            }
                        }
                        if (loc.contains("index.php?h=")) {
                            val indexRegex =
                                Regex("(\\/\\/api.cineplushd.org\\/sc\\/index.php\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                            indexRegex.findAll(loc).map { indreg ->
                                indreg.value.replace("//api.cineplushd.org/sc/index.php?h=", "")
                            }.toList().apmap { inlink ->
                                app.post(
                                    "https://api.cineplushd.org/sc/r.php", allowRedirects = false,
                                    headers = mapOf(
                                        "Host" to "api.cineplushd.org",
                                        "User-Agent" to USER_AGENT,
                                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                                        "Accept-Language" to "en-US,en;q=0.5",
                                        "Accept-Encoding" to "gzip, deflate, br",
                                        "Content-Type" to "application/x-www-form-urlencoded",
                                        "Origin" to "null",
                                        "DNT" to "1",
                                        "Connection" to "keep-alive",
                                        "Upgrade-Insecure-Requests" to "1",
                                        "Sec-Fetch-Dest" to "iframe",
                                        "Sec-Fetch-Mode" to "navigate",
                                        "Sec-Fetch-Site" to "same-origin",
                                        "Sec-Fetch-User" to "?1",
                                    ),
                                    data = mapOf(Pair("h", inlink))
                                ).okhttpResponse.headers.values("location").apmap { link ->
                                    loadExtractor(link, data, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                }
            }
        }
        return true
    }
}
