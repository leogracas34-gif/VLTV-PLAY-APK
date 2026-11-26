package com.vltv.play

import android.content.Context

/**
 * Avatares próprios do VLTV — os MESMOS 12 da TV (bichinhos originais, sem personagem de ninguém).
 *
 * - Na nuvem/TV o avatar é "vltv:<id>"  (ex.: "vltv:raposa").
 * - No celular é o drawable "av_vltv_<id>" (ex.: "av_vltv_raposa").
 * - Perfil infantil continua "av_infantil".
 * - Qualquer valor antigo (personagem, cor, imagem que não existe) vira o avatar automático,
 *   calculado a partir do id do perfil com a MESMA conta da TV — então os dois aparelhos
 *   mostram sempre o mesmo bichinho.
 */
object AvatarHelper {

    const val KIDS = "av_infantil"
    private const val PREFIXO = "av_vltv_"

    /** Ordem igual à da TV (avatares.js). NÃO mude a ordem: o avatar automático depende dela. */
    val IDS = arrayOf(
        "raposa", "gato", "coruja", "urso", "robo", "astronauta",
        "dino", "polvo", "coelho", "sapo", "pinguim", "alien"
    )

    val NOMES = mapOf(
        "raposa" to "Raposa", "gato" to "Gato", "coruja" to "Coruja", "urso" to "Urso",
        "robo" to "Robô", "astronauta" to "Astronauta", "dino" to "Dino", "polvo" to "Polvo",
        "coelho" to "Coelho", "sapo" to "Sapo", "pinguim" to "Pinguim", "alien" to "Alien"
    )

    /** Avatar automático: mesma conta da TV (h = h*31 + código, módulo 9973; índice = h % 12). */
    fun automatico(perfilId: String): String {
        var h = 0
        for (ch in perfilId) h = (h * 31 + ch.code) % 9973
        return PREFIXO + IDS[h % IDS.size]
    }

    /** "av_vltv_raposa" → "raposa" (ou null se não for um dos 12 avatares). */
    fun idDoAnimal(drawableName: String?): String? {
        if (drawableName == null || !drawableName.startsWith(PREFIXO)) return null
        val id = drawableName.removePrefix(PREFIXO)
        return if (id in IDS) id else null
    }

    fun ehAnimal(drawableName: String?) = idDoAnimal(drawableName) != null

    /**
     * Transforma o que está salvo no perfil (pode ser valor antigo) num drawable que EXISTE.
     * [nome] é o nome do perfil; dele sai o id usado no avatar automático.
     */
    fun resolver(ctx: Context, salvo: String?, nome: String?, kids: Boolean = false): String {
        if (kids) return KIDS
        if (salvo == KIDS) return KIDS
        if (ehAnimal(salvo) && existe(ctx, salvo!!)) return salvo
        return automatico(CloudSync.perfilId(nome ?: ""))
    }

    /** Id do drawable (R.drawable.*) ou 0 se não existir. */
    fun resId(ctx: Context, drawableName: String?): Int =
        if (drawableName.isNullOrEmpty()) 0
        else ctx.resources.getIdentifier(drawableName, "drawable", ctx.packageName)

    private fun existe(ctx: Context, drawableName: String) = resId(ctx, drawableName) != 0
}
