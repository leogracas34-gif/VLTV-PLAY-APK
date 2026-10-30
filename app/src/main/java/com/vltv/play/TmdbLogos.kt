package com.vltv.play

import org.json.JSONArray

/**
 * TmdbLogos — mesma regra de escolha da logo do título que o app da LG usa (tmdb.js › escolherLogo).
 *
 * Antes, o APK só aceitava logo em PORTUGUÊS: título sem logo em português ficava só com o nome em texto
 * (na tela de detalhes, no banner e na tela de pausa). Agora segue a ordem da LG:
 *   1) português do Brasil  2) português  3) inglês (a original de muitas séries)  4) sem idioma (arte limpa).
 */
object TmdbLogos {

    /** Valor do parâmetro include_image_language da consulta /images do TMDB. */
    const val IDIOMAS = "pt,en,null"

    /** Devolve o file_path da melhor logo, ou null se a lista não tiver nenhuma. */
    fun escolher(logos: JSONArray?): String? {
        if (logos == null || logos.length() == 0) return null
        fun caminho(i: Int) = logos.getJSONObject(i).optString("file_path").takeIf { it.isNotEmpty() }
        fun idioma(i: Int): String? {
            val o = logos.getJSONObject(i)
            return if (o.isNull("iso_639_1")) null else o.optString("iso_639_1").lowercase().ifEmpty { null }
        }
        fun regiao(i: Int) = logos.getJSONObject(i).optString("iso_3166_1").uppercase()

        for (i in 0 until logos.length()) if (idioma(i) == "pt" && regiao(i) == "BR") caminho(i)?.let { return it }
        for (i in 0 until logos.length()) if (idioma(i) == "pt") caminho(i)?.let { return it }
        for (i in 0 until logos.length()) if (idioma(i) == "en") caminho(i)?.let { return it }
        for (i in 0 until logos.length()) if (idioma(i) == null) caminho(i)?.let { return it }
        return null
    }
}
