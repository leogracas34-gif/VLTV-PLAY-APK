package com.vltv.play

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ✅ Sincronização entre aparelhos (estilo Netflix) pela VPS/gateway.
 *
 * Mesmo login + mesmo perfil = mesmo "Continuar assistindo" e mesmos favoritos
 * (filmes, séries e canais) no celular, na TV Android, na LG e na Samsung.
 *
 * Rotas usadas (ver perfis.js no gateway):
 *   POST /conta/progresso   POST /conta/favoritos
 *
 * Regras:
 *  - O id do perfil na nuvem sai do NOME do perfil (perfilId): "Léo" vira "leo".
 *    O app da LG/Samsung usa a MESMA regra, senão os perfis não se encontram.
 *  - Posição e duração vão em SEGUNDOS (o player do Android usa milissegundos aqui dentro).
 *  - Vale sempre o registro com "t" (hora do aparelho) mais novo.
 *  - Tudo roda em segundo plano e falha em silêncio: sem internet o app funciona como antes.
 */
object CloudSync {

    private const val TAG = "VLTV_CloudSync"
    private const val BASE_URL = "https://tv.vltvplay.tech"
    private const val PREFS = "vltv_prefs"
    private const val PREFS_FAV_FILMES = "vltv_favoritos"
    private const val PREFS_SYNC = "vltv_sync"

    private const val ENVIO_ESPERA_MS = 2_000L          // junta várias gravações seguidas num envio só
    private const val SYNC_INTERVALO_MIN_MS = 15_000L   // não busca na nuvem mais que 1x a cada 15 s
    private const val SEMENTE_MAX = 400                 // limite do gateway por envio

    private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Envios que ainda não saíram (o mais novo de cada item vence).
    private class Pend(val chave: String, val ehProgresso: Boolean, val perfil: String, val item: JSONObject)
    private val pendentes = LinkedHashMap<String, Pend>()
    private var envioAgendado = false

    private val sincronizando = AtomicBoolean(false)
    @Volatile private var ultimaSync = 0L
    @Volatile private var ultimoPerfilSync = ""

    // ───────────────────────── Utilidades ─────────────────────────

    /** Id do perfil na nuvem: nome sem acento, minúsculo, só a-z 0-9 e "-". Igual na LG/Samsung. */
    fun perfilId(nome: String): String {
        val semAcento = Normalizer.normalize(nome.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val s = semAcento.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)
        return if (s.isEmpty()) "padrao" else s
    }

    private fun credenciais(ctx: Context): Pair<String, String>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val u = p.getString("username", "") ?: ""
        val s = p.getString("password", "") ?: ""
        return if (u.isBlank() || s.isBlank()) null else Pair(u, s)
    }

    private fun corpoBase(user: String, pass: String, pid: String): JSONObject =
        JSONObject().put("user", user).put("pass", pass).put("perfil", pid)

    private fun post(path: String, corpo: JSONObject): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(BASE_URL + path)
                .post(corpo.toString().toRequestBody(JSON_MT))
                .build()
            SharedHttpClient.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w(TAG, "$path respondeu ${r.code}")
                    return null
                }
                val txt = r.body?.string() ?: return null
                val j = JSONObject(txt)
                if (j.optBoolean("ok", false)) j else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "$path falhou: ${e.message}")
            null
        }
    }

    private fun metaProgresso(nome: String, icone: String, ehSerie: Boolean): JSONObject =
        JSONObject().put("n", nome.take(300)).put("i", icone.take(400)).put("s", ehSerie)

    /** Dados do canal que viajam junto com o favorito (para o outro aparelho conseguir montar a lista). */
    fun metaCanal(id: Int, nome: String, icone: String?, epg: String?, categoria: String?): JSONObject =
        JSONObject()
            .put("n", nome.take(200))
            .put("i", (icone ?: "").take(400))
            .put("e", (epg ?: "").take(200))
            .put("c", (categoria ?: "").take(60))
            .put("id", id)

    // ───────────────────────── Envio (fila com espera) ─────────────────────────

    private fun colocar(ctx: Context, p: Pend) {
        val app = ctx.applicationContext
        var agendar = false
        synchronized(pendentes) {
            pendentes[p.chave] = p
            if (!envioAgendado) { envioAgendado = true; agendar = true }
        }
        if (agendar) {
            scope.launch {
                delay(ENVIO_ESPERA_MS)
                synchronized(pendentes) { envioAgendado = false }
                enviarPendentes(app)
            }
        }
    }

    /** Manda para a nuvem o que está na fila. Se falhar, o que não saiu volta para a fila. */
    private fun enviarPendentes(ctx: Context) {
        val cred = credenciais(ctx) ?: return
        val lote = synchronized(pendentes) {
            val l = pendentes.values.toList()
            pendentes.clear()
            l
        }
        if (lote.isEmpty()) return

        val grupos = lote.groupBy { Pair(it.ehProgresso, it.perfil) }
        grupos.forEach { (chave, itens) ->
            val pid = perfilId(chave.second)
            val arr = JSONArray()
            itens.forEach { arr.put(it.item) }
            val corpo = corpoBase(cred.first, cred.second, pid).put("itens", arr)
            val rota = if (chave.first) "/conta/progresso" else "/conta/favoritos"
            val ok = post(rota, corpo) != null
            if (!ok) {
                synchronized(pendentes) { itens.forEach { if (!pendentes.containsKey(it.chave)) pendentes[it.chave] = it } }
            }
        }
    }

    /** Progresso de filme/episódio assistindo agora (chamar ao pausar, sair e durante a reprodução). */
    fun enviarProgresso(
        ctx: Context, perfilNome: String, ehSerie: Boolean, id: Int,
        posMs: Long, durMs: Long, nome: String, icone: String
    ) {
        if (durMs < 1000L || credenciais(ctx) == null) return
        val k = (if (ehSerie) "e" else "f") + id
        val item = JSONObject()
            .put("k", k)
            .put("pos", posMs / 1000L)
            .put("dur", durMs / 1000L)
            .put("t", System.currentTimeMillis())
            .put("fim", false)
            .put("meta", metaProgresso(nome, icone, ehSerie))
        colocar(ctx, Pend("p|${perfilId(perfilNome)}|$k", true, perfilNome, item))
    }

    /** Terminou de assistir (95% ou mais): os outros aparelhos também limpam a posição. */
    fun concluir(ctx: Context, perfilNome: String, ehSerie: Boolean, id: Int, durMs: Long) {
        if (credenciais(ctx) == null) return
        val dur = maxOf(1L, durMs / 1000L)
        val k = (if (ehSerie) "e" else "f") + id
        val item = JSONObject()
            .put("k", k)
            .put("pos", dur)
            .put("dur", dur)
            .put("t", System.currentTimeMillis())
            .put("fim", true)
        colocar(ctx, Pend("p|${perfilId(perfilNome)}|$k", true, perfilNome, item))
    }

    /**
     * Favoritou ou desfavoritou. k = "f<id filme>", "s<id série>" ou "c<id canal>".
     * Para canais, mande o meta (metaCanal) para a outra ponta conseguir mostrar o canal.
     */
    fun favorito(ctx: Context, perfilNome: String, k: String, ativo: Boolean, meta: JSONObject? = null) {
        val t = System.currentTimeMillis()
        // Lembra quando ESTE aparelho mudou, para uma mudança mais antiga vinda da nuvem não desfazer.
        ctx.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE).edit()
            .putLong("${perfilId(perfilNome)}_favt_$k", t).apply()
        if (credenciais(ctx) == null) return
        val item = JSONObject().put("k", k).put("t", t).put("removido", !ativo)
        if (meta != null) item.put("meta", meta)
        colocar(ctx, Pend("f|${perfilId(perfilNome)}|$k", false, perfilNome, item))
    }

    // ───────────────────────── Busca (nuvem -> aparelho) ─────────────────────────

    /**
     * Manda o que está pendente, baixa o que mudou nos outros aparelhos e aplica aqui.
     * [aoMudar] roda na thread principal só se algo mudou (ex.: recarregar o "Continuar assistindo").
     */
    fun sincronizar(ctx: Context, perfilNome: String, forcar: Boolean = false, aoMudar: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        if (credenciais(app) == null) return
        val agora = System.currentTimeMillis()
        val trocouPerfil = perfilNome != ultimoPerfilSync
        if (!forcar && !trocouPerfil && agora - ultimaSync < SYNC_INTERVALO_MIN_MS) return
        if (!sincronizando.compareAndSet(false, true)) return
        ultimaSync = agora
        ultimoPerfilSync = perfilNome

        scope.launch {
            var mudou = false
            try {
                enviarPendentes(app)
                if (sincronizarProgresso(app, perfilNome)) mudou = true
                if (sincronizarFavoritos(app, perfilNome)) mudou = true
            } catch (e: Exception) {
                Log.w(TAG, "sincronizar falhou: ${e.message}")
            } finally {
                sincronizando.set(false)
            }
            val cb = aoMudar
            if (mudou && cb != null) withContext(Dispatchers.Main) { cb() }
        }
    }

    private fun chaveHistorico(h: WatchHistoryEntity) = (if (h.is_series) "e" else "f") + h.stream_id

    private suspend fun sincronizarProgresso(ctx: Context, perfilNome: String): Boolean {
        val cred = credenciais(ctx) ?: return false
        val pid = perfilId(perfilNome)
        val sp = ctx.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
        val desde = sp.getLong("${pid}_desde_prog", 0L)
        val dao = AppDatabase.getDatabase(ctx).streamDao()
        val locais = HashMap<String, WatchHistoryEntity>()
        try { dao.getWatchHistory(perfilNome, 500).forEach { locais[chaveHistorico(it)] = it } } catch (e: Exception) { /* sem histórico local */ }

        val corpo = corpoBase(cred.first, cred.second, pid).put("desde", desde)
        if (desde == 0L) {
            // Primeira vez deste perfil neste aparelho: sobe o histórico que já existe aqui.
            val semente = JSONArray()
            locais.values.take(SEMENTE_MAX).forEach { h ->
                if (h.duration >= 1000L) {
                    semente.put(
                        JSONObject()
                            .put("k", chaveHistorico(h))
                            .put("pos", h.last_position / 1000L)
                            .put("dur", h.duration / 1000L)
                            .put("t", h.timestamp)
                            .put("fim", false)
                            .put("meta", metaProgresso(h.name, h.icon ?: "", h.is_series))
                    )
                }
            }
            corpo.put("itens", semente)
        }

        val resp = post("/conta/progresso", corpo) ?: return false
        val itens = resp.optJSONArray("itens") ?: JSONArray()
        var mudou = false
        for (i in 0 until itens.length()) {
            val it = itens.optJSONObject(i) ?: continue
            if (aplicarProgresso(ctx, perfilNome, it, locais, dao)) mudou = true
        }
        val agoraServidor = resp.optLong("agora", 0L)
        if (agoraServidor > 0L) sp.edit().putLong("${pid}_desde_prog", agoraServidor).apply()
        return mudou
    }

    private suspend fun aplicarProgresso(
        ctx: Context, perfil: String, it: JSONObject,
        locais: Map<String, WatchHistoryEntity>, dao: StreamDao
    ): Boolean {
        val k = it.optString("k", "")
        if (k.length < 2 || (k[0] != 'f' && k[0] != 'e')) return false
        val ehSerie = k[0] == 'e'
        val id = k.substring(1).toIntOrNull() ?: return false
        val t = it.optLong("t", 0L)
        val local = locais[k]
        if (local != null && local.timestamp >= t) return false   // o daqui é igual ou mais novo

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = "${perfil}_${if (ehSerie) "series" else "movie"}_resume_$id"

        val terminou = it.optBoolean("fim", false) || it.optBoolean("removido", false)
        if (terminou) {
            val tinha = prefs.contains("${base}_pos")
            if (tinha) prefs.edit().remove("${base}_pos").remove("${base}_dur").apply()
            return tinha
        }

        val posMs = it.optLong("pos", 0L) * 1000L
        val durMs = it.optLong("dur", 0L) * 1000L
        if (durMs <= 0L) return false
        val meta = it.optJSONObject("meta")
        val nome = (meta?.optString("n", "") ?: "").ifBlank { local?.name ?: "" }
        val icone = (meta?.optString("i", "") ?: "").ifBlank { local?.icon ?: "" }

        // Mesmas chaves que o PlayerActivity grava (a barra "Assistido" e o "Retomar" leem daqui).
        val keyIds = "${perfil}_local_history_ids"
        val ids = (prefs.getStringSet(keyIds, emptySet()) ?: emptySet()).toMutableSet()
        ids.add(id.toString())
        prefs.edit()
            .putLong("${base}_pos", posMs)
            .putLong("${base}_dur", durMs)
            .putStringSet(keyIds, ids)
            .putString("${perfil}_history_name_$id", nome)
            .putString("${perfil}_history_icon_$id", icone)
            .apply()

        dao.saveWatchHistory(
            WatchHistoryEntity(
                stream_id = id,
                profile_name = perfil,
                name = nome,
                icon = icone,
                last_position = posMs,
                duration = durMs,
                is_series = ehSerie,
                timestamp = t
            )
        )
        return true
    }

    // ───────────────────────── Favoritos ─────────────────────────

    private fun lerConjunto(ctx: Context, arquivo: String, chave: String): MutableSet<String> =
        (ctx.getSharedPreferences(arquivo, Context.MODE_PRIVATE).getStringSet(chave, emptySet()) ?: emptySet()).toMutableSet()

    private fun sincronizarFavoritos(ctx: Context, perfilNome: String): Boolean {
        val cred = credenciais(ctx) ?: return false
        val pid = perfilId(perfilNome)
        val sp = ctx.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
        val desde = sp.getLong("${pid}_desde_fav", 0L)

        val corpo = corpoBase(cred.first, cred.second, pid).put("desde", desde)
        if (desde == 0L) {
            // Primeira vez: sobe os favoritos que já existem neste aparelho (t antigo: remoção feita em outro aparelho vence).
            val semente = JSONArray()
            fun tDe(k: String) = sp.getLong("${pid}_favt_$k", 1L).coerceAtLeast(1L)
            lerConjunto(ctx, PREFS_FAV_FILMES, "${perfilNome}_favoritos").forEach { id ->
                semente.put(JSONObject().put("k", "f$id").put("t", tDe("f$id")))
            }
            lerConjunto(ctx, PREFS, "${perfilNome}_fav_series").forEach { id ->
                semente.put(JSONObject().put("k", "s$id").put("t", tDe("s$id")))
            }
            FavoritesManager.getFavoritesDe(ctx, perfilNome).forEach { c ->
                semente.put(
                    JSONObject().put("k", "c${c.id}").put("t", tDe("c${c.id}"))
                        .put("meta", metaCanal(c.id, c.name, c.icon, c.epg_channel_id, c.category_id))
                )
            }
            // O gateway aceita 400 por envio; o resto sobe nas próximas sincronizações.
            val limitada = JSONArray()
            for (i in 0 until minOf(semente.length(), SEMENTE_MAX)) limitada.put(semente.get(i))
            corpo.put("itens", limitada)
        }

        val resp = post("/conta/favoritos", corpo) ?: return false
        val itens = resp.optJSONArray("itens") ?: JSONArray()
        var mudou = false
        val ed = sp.edit()
        for (i in 0 until itens.length()) {
            val it = itens.optJSONObject(i) ?: continue
            val k = it.optString("k", "")
            if (k.length < 2) continue
            val id = k.substring(1).toIntOrNull() ?: continue
            val t = it.optLong("t", 0L)
            val localT = sp.getLong("${pid}_favt_$k", 0L)
            if (t <= localT) continue   // a mudança daqui é igual ou mais nova
            val ativo = !it.optBoolean("removido", false)

            val alterou = when (k[0]) {
                'f' -> aplicarEmConjunto(ctx, PREFS_FAV_FILMES, "${perfilNome}_favoritos", id, ativo)
                's' -> aplicarEmConjunto(ctx, PREFS, "${perfilNome}_fav_series", id, ativo)
                'c' -> {
                    val m = it.optJSONObject("meta")
                    FavoritesManager.aplicarRemoto(
                        ctx, perfilNome, id,
                        m?.optString("n", "") ?: "", m?.optString("i", ""),
                        m?.optString("e", ""), m?.optString("c", ""), ativo
                    )
                }
                else -> false
            }
            ed.putLong("${pid}_favt_$k", t)
            if (alterou) mudou = true
        }
        val agoraServidor = resp.optLong("agora", 0L)
        if (agoraServidor > 0L) ed.putLong("${pid}_desde_fav", agoraServidor)
        ed.apply()
        return mudou
    }

    private fun aplicarEmConjunto(ctx: Context, arquivo: String, chave: String, id: Int, ativo: Boolean): Boolean {
        val set = lerConjunto(ctx, arquivo, chave)
        val mudou = if (ativo) set.add(id.toString()) else set.remove(id.toString())
        if (mudou) ctx.getSharedPreferences(arquivo, Context.MODE_PRIVATE).edit().putStringSet(chave, set).apply()
        return mudou
    }
}
