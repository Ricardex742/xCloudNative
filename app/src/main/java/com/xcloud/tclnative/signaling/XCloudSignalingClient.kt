package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.i18n.UiText

import com.xcloud.tclnative.auth.AuthenticationRequiredException
import com.xcloud.tclnative.auth.RemotePlaySession
import com.xcloud.tclnative.auth.StreamingAuthorization
import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.diagnostics.PlaybackStage
import com.xcloud.tclnative.settings.SessionOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.xcloud.tclnative.net.readResponse
import com.xcloud.tclnative.net.checkedBody
import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.net.CloudRequestException
import com.xcloud.tclnative.touch.OfficialTouchBundle
import com.xcloud.tclnative.webrtc.StreamingPeer
import com.xcloud.tclnative.webrtc.StreamResolution
import com.xcloud.tclnative.catalog.XboxProfile
import com.xcloud.tclnative.catalog.CloudPlayHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class GameTitle(
    val titleId: String, val name: String, val productId: String? = null,
    val posterUrl: String? = null, val tileUrl: String? = null, val gamePass: Boolean = false,
    val userSubscriptions: List<String> = emptyList(), val heroUrl: String? = null,
    val seriesOptimized: Boolean = false, val xboxTitleId: String? = null,
    val programs: List<String> = emptyList(), val userPrograms: List<String> = emptyList(),
    val freeToPlay: Boolean = false, val supportedInputTypes: Set<String> = emptySet(),
    val englishName: String? = null,
    val genres: Set<com.xcloud.tclnative.catalog.CatalogGenre> = emptySet(),
    val touchBundleIds: List<String> = emptyList()
) {
    internal fun offersOfficialTouch() = touchBundleIds.isNotEmpty() ||
        supportedInputTypes.any { it.equals("customTouchOverlay",true) || it.equals("nativeTouchOrCustomTouchOverlay",true) }
}

class XCloudSignalingClient(
    private val session: StreamingAuthorization,
    private val httpClient: OkHttpClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build(),
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val queueTimeoutMs: Long = 60 * 60_000,
    private val transferTokenProvider: suspend () -> String = { (session as? XCloudSession)?.transferToken.orEmpty() },
    private val renewCloudAuthorization: (suspend (XCloudSession) -> XCloudSession)? = null,
    private val renewRemotePlayAuthorization: (suspend (RemotePlaySession) -> RemotePlaySession)? = null
) {
    private data class SessionProfile(
        val baseUri: String, val region: String, val locale: String, val resolution: StreamResolution
    )

    private inner class Attempt(val profile: SessionProfile) {
        val startupTrace = SessionStartupTrace(clockMs)
        val keepAlive = SessionKeepAlive(pause, clockMs)
    }

    private class Allocation(val attempt: Attempt, val path: String) {
        val transportLock = Mutex()
        var sdpRequest = 1
        @Volatile var timeLimitSeconds: Long? = null
    }

    private val cloudSession = session as? XCloudSession
    private val remotePlay = session is RemotePlaySession
    private val sessionRoot = if (remotePlay) "/v5/sessions/home" else "/v5/sessions/cloud"
    private val requestClient = if (remotePlay) httpClient.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build() else httpClient
    private val defaultProfile = SessionProfile(session.baseUri, UiText.text("Automático"),
        "pt-BR", StreamResolution.HD_1080_HQ)
    @Volatile private var lastAttempt = Attempt(defaultProfile)
    private val allocationLock = Mutex()
    private val authorizationLock = Mutex()
    @Volatile private var authorization = session
    private val active = AtomicReference<Allocation?>(null)
    private val waitTimeClient = requestClient.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    private val keepAliveClient = requestClient.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    private val remoteStartClient = requestClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val touchBundleClient by lazy { requestClient.newBuilder().callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build() }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun resetStartupDiagnostics() = lastAttempt.startupTrace.reset()
    fun startupDiagnostics() = lastAttempt.startupTrace.diagnostics()
    internal fun sessionTimeLimitSeconds(): Long? = active.get()?.timeLimitSeconds
    fun profileDiagnostics(): String {
        val profile = lastAttempt.profile
        val resolution = profile.resolution
        val cloud = cloudSession ?: return "Remote Play • ${resolution.width}×${resolution.height} • ${profile.locale}"
        return UiText.text("Perfil de vídeo solicitado: {0}×{1} • {2}\nRegião solicitada: {3} | Idioma solicitado: {4}",
            UiText.nested(resolution.width), UiText.nested(resolution.height), UiText.nested(resolution.osName),
            UiText.nested(profile.region), UiText.nested(profile.locale)) + "\n" +
            UiText.text("Bypass de região solicitado no login: {0}", UiText.nested(cloud.regionBypass.label))
    }

    private fun deviceInfo(resolution: StreamResolution) = JSONObject().put("appInfo", JSONObject().put("env", JSONObject()
        .put("clientAppId", "www.xbox.com").put("clientAppType", "browser").put("clientAppVersion", "26.1.0")))
        // Input capability is negotiated by the transport, independently of this video profile.
        .put("dev", JSONObject().put("hw", JSONObject().put("make", "Microsoft").put("model", "Surface Pro"))
            .put("os", JSONObject().put("name", resolution.osName).put("ver", "10.0"))
            .put("displayInfo", JSONObject().put("dimensions", JSONObject().put("widthInPixels", resolution.width).put("heightInPixels", resolution.height))
                .put("pixelDensity", JSONObject().put("dpiX", 1).put("dpiY", 1)))).toString()

    private suspend fun request(path: String, stage: String, method: String = "GET", body: JSONObject? = null,
                                client: OkHttpClient = requestClient, query: Map<String,String> = emptyMap(),
                                base: String = session.baseUri, resolution: StreamResolution = defaultProfile.resolution,
                                bearer: String = authorization.gssvToken): String {
        if (remotePlay) check(path == "/v6/servers/home" || path.startsWith("$sessionRoot/"))
        val url=CloudProtocol.sessionUrl(base,path).newBuilder().apply {
            query.forEach { (key,value) -> addQueryParameter(key,value) }
        }.build()
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json").header("X-Gssv-Client", "XboxComBrowser")
            .header("X-MS-Device-Info", deviceInfo(resolution))
        when (method) {
            "POST" -> builder.post((body?.toString() ?: "").toRequestBody(jsonType))
            "DELETE" -> builder.delete()
        }
        return try {
            client.newCall(builder.build()).readResponse {
                if (stage == "CATALOG") it.checkedBody(stage, maxBytes = 16L * 1024 * 1024)
                else it.checkedBody(stage)
            }
        } catch(error: CloudHttpException) {
            throw error
        } catch(error: IOException) {
            throw CloudRequestException(stage,error)
        }
    }

    internal suspend fun fetchRemoteConsoles(): List<RemoteConsole> {
        check(remotePlay)
        return RemoteConsole.parse(request("/v6/servers/home", "REMOTE_PLAY_CONSOLES", query = mapOf("mr" to "50")))
    }

    suspend fun fetchCatalogGames(): List<GameTitle> = withContext(Dispatchers.IO) {
        val results = JSONObject(request("/v2/titles", "CATALOG")).getJSONArray("results")
        (0 until results.length()).mapNotNull { parseCatalogTitle(results.getJSONObject(it)) }
            .distinctBy { it.titleId }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }

    private fun parseCatalogTitle(game: JSONObject): GameTitle? {
        val id=game.optString("titleId").trim().takeIf { it.isNotEmpty() && it!="null" } ?: return null
        val details = game.optJSONObject("details")
        return GameTitle(id, details?.optString("productTitle")?.takeIf { it.isNotBlank() } ?: id,
            productId = details?.optString("productId")?.takeIf { it.isNotBlank() && it != "null" }
                ?: game.optString("productId").takeIf { it.isNotBlank() && it != "null" },
            xboxTitleId = details?.optString("xboxTitleId")?.takeIf { it.isNotBlank() && it != "null" },
            userSubscriptions = XboxProfile.subscriptionClaims(details?.opt("userSubscriptions")),
            userPrograms = XboxProfile.subscriptionClaims(details?.opt("userPrograms")),
            programs = XboxProfile.subscriptionClaims(details?.opt("programs")),
            freeToPlay = details?.optBoolean("isFreeInStore",false) == true,
            supportedInputTypes = XboxProfile.subscriptionClaims(details?.opt("supportedInputTypes")).toSet(),
            touchBundleIds = touchBundleIds(details))
    }

    internal suspend fun officialTouchBundle(game: GameTitle, locale: String): OfficialTouchBundle? = withContext(Dispatchers.IO) {
        val allocation = active.get() ?: return@withContext null
        if (remotePlay || !game.offersOfficialTouch()) return@withContext null
        val base = allocation.attempt.profile.baseUri
        val ids = game.touchBundleIds.ifEmpty {
            val titleId = game.xboxTitleId?.takeIf { it.matches(Regex("[0-9]{1,10}")) } ?: return@withContext null
            val payload = JSONObject().put("titleIds",org.json.JSONArray().put(titleId)).put("titleIdType","xboxTitleId")
            val configs = org.json.JSONArray(request("/v2/titles/inputconfigs","TOUCH_CONFIG","POST",payload,
                client=touchBundleClient,base=base))
            if (configs.length()!=1) return@withContext null
            touchBundleIds(configs.optJSONObject(0))
        }
        for (id in ids) {
            if (active.get() !== allocation) return@withContext null
            try {
                val signedLocation = request("/v1/sessions/tabs/$id/download","TOUCH_BUNDLE",
                    client=touchBundleClient,query=mapOf("noRedirect" to "true"),base=base).trim()
                val url = signedLocation.takeIf { it.length <= 8192 }?.toHttpUrlOrNull()
                    ?: continue
                if (!url.isHttps || url.port!=443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                    url.fragment!=null || !officialBundleHost(url.host)) continue
                val bytes = touchBundleClient.newCall(Request.Builder().url(url).get().build()).readResponse { response ->
                    check(response.isSuccessful) { "TOUCH_BUNDLE: download unavailable" }
                    val body=response.body ?: error("TOUCH_BUNDLE: empty download")
                    val maximum=8L*1024*1024
                    check(body.contentLength()<=maximum && !body.source().request(maximum+1)) { "TOUCH_BUNDLE: too large" }
                    body.bytes()
                }
                return@withContext OfficialTouchBundle.parse(bytes,locale)
            } catch(error: CancellationException) {
                throw error
            } catch(_: Exception) {
                // A title may advertise more than one bundle version.
            }
        }
        null
    }

    private fun officialBundleHost(host: String): Boolean =
        listOf("xbox.com","xboxlive.com","xboxservices.com","blob.core.windows.net","azureedge.net",
            "microsoft.com","msedge.net").any { host==it || host.endsWith(".$it") }

    private fun touchBundleIds(details: JSONObject?): List<String> {
        val tabs=details?.optJSONArray("supportedTabs") ?: return emptyList()
        return (0 until minOf(12,tabs.length())).mapNotNull { tabs.optJSONObject(it)?.optString("id") }
            .filter { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")) }
            .distinct().take(4)
    }

    /** A public gallery product must resolve through the authenticated service before launching. */
    suspend fun resolveGalleryProduct(productId: String): GameTitle? = withContext(Dispatchers.IO) {
        require(productId.matches(Regex("[A-Za-z0-9]{12}")))
        val body=JSONObject().put("alternateIds",org.json.JSONArray(listOf(productId))).put("alternateIdType","productId")
        val response=JSONObject(request("/v2/titles","GALLERY_LOOKUP","POST",body,
            httpClient.newBuilder().callTimeout(6,TimeUnit.SECONDS).build()))
        val results=response.getJSONArray("results")
        if(results.length()!=1 || response.optInt("totalItems",results.length())!=1) return@withContext null
        parseCatalogTitle(results.getJSONObject(0))
    }

    suspend fun fetchRecentGames(catalog: List<GameTitle>): List<String> = withContext(Dispatchers.IO) {
        val client=httpClient.newBuilder().callTimeout(6,TimeUnit.SECONDS).build()
        val response=JSONObject(request("/v2/titles/mru","HISTORY",client=client,query=mapOf("mr" to "25")))
        CloudPlayHistory.resolve(catalog,response)
    }

    /** Resolves official Game Pass product/edition IDs to cloud launch IDs, never using access programs. */
    suspend fun resolveCatalogProducts(products: Set<String>, beforeRequest: suspend () -> Unit = {}): Set<String> = coroutineScope {
        val slots=Semaphore(3)
        val client=httpClient.newBuilder().callTimeout(6,TimeUnit.SECONDS).build()
        products.map { product -> async {
            slots.withPermit {
                beforeRequest()
                try {
                    val body=JSONObject().put("alternateIds",org.json.JSONArray(listOf(product))).put("alternateIdType","productId")
                    val response=JSONObject(request("/v2/titles","CATALOG_LOOKUP","POST",body,client))
                    val results=response.getJSONArray("results")
                    // The web client's single-product lookup must return one unambiguous title.
                    if(results.length()!=1 || response.optInt("totalItems",1)>1) return@withPermit emptySet<String>()
                    (0 until results.length()).mapNotNull { results.optJSONObject(it)?.optString("titleId")
                        ?.takeIf { id -> id.isNotBlank() && id != "null" } }.toSet()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptySet<String>() }
            }
        } }.awaitAll().flatten().toSet()
    }

    private fun requireActive(allocation: Allocation) {
        if (active.get() !== allocation) throw IOException("TRANSPORT: sessão encerrada ou substituída")
    }

    private suspend fun sessionRequest(
        allocation: Allocation, path: String, stage: String, method: String = "GET",
        body: JSONObject? = null, client: OkHttpClient = requestClient, bearer: String = authorization.gssvToken
    ): String {
        requireActive(allocation)
        val profile = allocation.attempt.profile
        val response = request(path, stage, method, body, client, base = profile.baseUri, resolution = profile.resolution, bearer = bearer)
        requireActive(allocation)
        return response
    }

    private suspend fun awaitProvisioned(allocation: Allocation, titleId: String, onQueueStatus: (QueueStatus?) -> Unit,
                                         onProgress: (String) -> Unit, onStage: (PlaybackStage) -> Unit) = coroutineScope {
        val started = clockMs()
        val path = allocation.path
        val startupTrace = allocation.attempt.startupTrace
        var connectSentAt: Long? = null
        var queue: QueueStatus? = null
        var estimateAttemptAt: Long? = null
        var pendingEstimate: Deferred<Pair<QueueEstimate?, Long>>? = null
        try {
            while (true) {
                val state = JSONObject(sessionRequest(allocation, "$path/state", "SESSION_STATE"))
                if (!remotePlay) {
                    SessionTimeLimit.durationSeconds(state)?.let { allocation.timeLimitSeconds = it }
                }
                val name = state.optString("state")
                startupTrace.observe(name)
                val elapsed = (clockMs() - started) / 1000
                if (name != "WaitingForResources") {
                    pendingEstimate?.cancel()
                    pendingEstimate = null
                    onQueueStatus(null)
                }
                when (name) {
                    "Provisioned" -> {
                        onStage(PlaybackStage.PROVISIONED)
                        return@coroutineScope
                    }
                    "ReadyToConnect" -> {
                        if (connectSentAt == null) {
                            onProgress("CONSOLE_CONNECT: Autenticando a sessão Xbox...")
                            val token = transferTokenProvider()
                            if (token.isBlank()) throw AuthenticationRequiredException(
                                "CONSOLE_CONNECT: login sem credencial de transferência. Entre novamente pelo QR Code.")
                            sessionRequest(allocation, "$path/connect", "CONSOLE_CONNECT", "POST", JSONObject().put("userToken", token))
                            connectSentAt = clockMs()
                        }
                        onProgress("PROVISIONING: Aguardando confirmação da sessão Xbox (${elapsed}s). Voltar cancela.")
                    }
                    "WaitingForResources" -> if (remotePlay) {
                        onProgress("PROVISIONING: Aguardando o console Xbox.")
                    } else {
                        val confirmedAt = clockMs()
                        if (queue == null) onStage(PlaybackStage.QUEUE_CONFIRMED)
                        queue = (queue ?: QueueStatus(confirmedAt, confirmedAt, limitMs = queueTimeoutMs))
                            .copy(confirmedAtMs = confirmedAt)
                        pendingEstimate?.takeIf { it.isCompleted }?.let {
                            val (estimate, estimatedAt) = it.await()
                            val status = checkNotNull(queue)
                            queue = if (estimate != null) status.copy(estimate = estimate, estimateAtMs = estimatedAt, estimateFailed = false)
                                else status.copy(estimateFailed = true)
                            startupTrace.estimate(checkNotNull(queue))
                            pendingEstimate = null
                        }
                        onQueueStatus(queue)
                        onProgress("QUEUE: Aguardando vaga no Xbox Cloud (${QueueStatus.duration(elapsed)}). Voltar cancela.")
                        if (pendingEstimate == null && (estimateAttemptAt == null || confirmedAt - estimateAttemptAt >= 30_000)) {
                            estimateAttemptAt = confirmedAt
                            val url = CloudProtocol.sessionUrl(allocation.attempt.profile.baseUri, "/v1/waittime/")
                                .newBuilder().addPathSegment(titleId).build().encodedPath
                            pendingEstimate = async(start = CoroutineStart.UNDISPATCHED) {
                                val estimate = try {
                                    QueueEstimate.parse(sessionRequest(allocation, url, "QUEUE_ESTIMATE", client = waitTimeClient))
                                } catch (_: IOException) {
                                    null
                                } catch (_: org.json.JSONException) {
                                    null
                                }
                                estimate to clockMs()
                            }
                        }
                    }
                    "WaitingForServerToRegister" -> {
                        if (!remotePlay) throw IOException("SESSION_STATE: estado não reconhecido")
                        onProgress("PROVISIONING: Aguardando o console Xbox.")
                    }
                    "Provisioning", "New" -> onProgress(if (remotePlay) "PROVISIONING: Conectando ao console Xbox."
                        else "PROVISIONING: Preparando o jogo (${elapsed}s). Voltar cancela.")
                    "Failed", "Error", "Ended", "Cancelled", "Closed" -> {
                        val code = state.optJSONObject("errorDetails")?.optString("code")
                            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }
                        throw IOException(UiText.join("SESSION_STATE: $name",(code?.let { UiText.text(" — código {0}",UiText.nested(it)) } ?: "")))
                    }
                    else -> throw IOException("SESSION_STATE: estado não reconhecido")
                }
                if (connectSentAt != null && clockMs() - connectSentAt >= 60_000) {
                    throw IOException("CONSOLE_CONNECT: o serviço não confirmou Provisioned após autenticar a sessão.")
                }
                if (clockMs() - started >= if (remotePlay) minOf(queueTimeoutMs, 90_000) else queueTimeoutMs) {
                    if (remotePlay) throw IOException("REMOTE_PLAY_TIMEOUT: $name (${elapsed}s)")
                    throw IOException("QUEUE_TIMEOUT: o serviço continua em $name após ${elapsed}s. Nenhum vídeo foi iniciado; tente novamente mais tarde.")
                }
                pause(1500)
            }
        } finally {
            pendingEstimate?.cancel()
            if (active.get() === allocation) onQueueStatus(null)
        }
    }

    private suspend fun exchange(allocation: Allocation, path: String, stage: String,expectedRequestId: String?=null): Any {
        val started = clockMs()
        while (clockMs() - started < 30_000) {
            val value = CloudProtocol.exchange(sessionRequest(allocation, path, stage))
            if (value != null && (expectedRequestId==null || value !is JSONObject || !value.has("requestId") || value.optString("requestId")==expectedRequestId)) return value
            pause(500)
        }
        throw IOException(UiText.join("$stage: ",UiText.text("tempo limite aguardando resposta do Xbox")))
    }

    suspend fun startAndConnectWebRtcSession(
        titleId: String, peer: StreamingPeer, onQueueStatus: (QueueStatus?) -> Unit = {},
        resolution: StreamResolution = StreamResolution.HD_1080_HQ, nativeTouch: Boolean=false,
        options: SessionOptions=SessionOptions(),microphone: Boolean=false,
        onStage: (PlaybackStage) -> Unit = {}, onProgress: (String) -> Unit
    ): Result<String> {
        check(!remotePlay)
        return startSession(titleId, "", peer, onQueueStatus, resolution, options, microphone, onStage, onProgress)
    }

    internal suspend fun startRemotePlay(console: RemoteConsole, peer: StreamingPeer, resolution: StreamResolution,
                                         options: SessionOptions, onStage: (PlaybackStage) -> Unit,
                                         onProgress: (String) -> Unit): Result<String> {
        check(remotePlay && console.canConnect)
        return startSession("", console.serverId, peer, {}, resolution, options, false, onStage, onProgress)
    }

    private suspend fun startSession(
        titleId: String, serverId: String, peer: StreamingPeer, onQueueStatus: (QueueStatus?) -> Unit,
        resolution: StreamResolution, options: SessionOptions, microphone: Boolean,
        onStage: (PlaybackStage) -> Unit, onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val selected = cloudSession?.regions?.firstOrNull { it.name == options.region && options.region.isNotEmpty() }
        val region = selected?.name ?: if (options.region.isBlank()) UiText.text("Automático")
            else UiText.text("Automático — região salva não oferecida à conta")
        val profile = SessionProfile(selected?.baseUri ?: session.baseUri, region, options.normalized().locale, resolution)
        val attempt = Attempt(profile)
        var owned: Allocation? = null
        var connected = false
        try {
            val allocation = allocationLock.withLock {
                active.get()?.let { deleteAllocation(it) }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                lastAttempt = attempt
                val play = JSONObject().put("clientSessionId", if (remotePlay) "" else java.util.UUID.randomUUID().toString())
                    .put("titleId", titleId).put("systemUpdateGroup", "").put("serverId", serverId)
                    .put("fallbackRegionNames", org.json.JSONArray()).put("settings", JSONObject()
                        .put("nanoVersion", "V3;WebrtcTransport.dll").put("enableOptionalDataCollection", false)
                        .put("enableTextToSpeech", false).put("highContrast", 0).put("locale", profile.locale)
                        .put("useIceConnection", false).put("timezoneOffsetMinutes", -180).put("sdkType", "web")
                        .put("osName", profile.resolution.osName))
                onStage(PlaybackStage.PLAY_REQUEST)
                suspend fun allocate(): Allocation {
                    val response = JSONObject(request("$sessionRoot/play", "PLAY", "POST", play,
                        client = if (remotePlay) remoteStartClient else requestClient,
                        base = profile.baseUri, resolution = profile.resolution))
                    val path = CloudProtocol.sessionUrl(profile.baseUri, response.getString("sessionPath")).encodedPath.trimEnd('/')
                    val allocatedPath = if (remotePlay && path.startsWith("/sessions/home/")) "/v5$path" else path
                    if (remotePlay) require(allocatedPath.startsWith("$sessionRoot/") &&
                        allocatedPath.removePrefix("$sessionRoot/").matches(Regex("[A-Za-z0-9_-]{1,100}")) &&
                        allocatedPath != "$sessionRoot/play") { "REMOTE_PLAY: caminho de sessão inválido" }
                    return Allocation(attempt, allocatedPath).also {
                        owned = it
                        active.set(it)
                    }
                }
                // The console can allocate before PLAY returns. Capture its path even on cancel, within the HTTP timeout.
                if (remotePlay) withContext(NonCancellable) { allocate() } else allocate()
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            awaitProvisioned(allocation, titleId, onQueueStatus, onProgress, onStage)

            onProgress("SDP: Sessão provisionada. Negociando vídeo nativo...")
            val ready = allocation.transportLock.withLock {
                negotiateTransport(allocation, peer, microphone, allocation.sdpRequest.toString(), onProgress, onStage)
            }
            connected = true
            Result.success(ready)
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            Result.failure(IOException("TRANSPORT: tempo limite na negociação WebRTC/canais de controle."))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            if (!connected) owned?.let { allocation ->
                withContext(NonCancellable) {
                    allocationLock.withLock { deleteAllocation(allocation) }
                }
            }
        }
    }

    /** Reuses the allocation. Failure/cancellation must never DELETE it or start another PLAY. */
    suspend fun reconnectExistingSession(peer: StreamingPeer,microphone: Boolean=false,
                                        onStage: (PlaybackStage) -> Unit = {},
                                         onProgress: (String) -> Unit={}): Result<String> {
        val allocation = active.get() ?: return Result.failure(IOException("RECONNECT: sessão Xbox indisponível"))
        return withContext(Dispatchers.IO) {
            allocation.transportLock.withLock {
                try {
                    sendKeepAlive(allocation, "RECONNECT_KEEPALIVE")
                    val ready = negotiateTransport(allocation, peer, microphone, (++allocation.sdpRequest).toString(), onProgress, onStage)
                    Result.success(ready)
                } catch(timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    Result.failure(IOException("RECONNECT: tempo limite ao reconectar a transmissão"))
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) { Result.failure(error) }
            }
        }
    }

    private suspend fun negotiateTransport(allocation: Allocation,peer: StreamingPeer,microphone: Boolean,requestId: String,
                                          onProgress: (String) -> Unit, onStage: (PlaybackStage) -> Unit): String {
            requireActive(allocation)
            val path = allocation.path
            val (offer, candidates) = peer.createOfferSdp()
            requireActive(allocation)
            val configuration = JSONObject().put("chatConfiguration", JSONObject().put("bytesPerSample", 2)
                .put("expectedClipDurationMs", 20).put("format", JSONObject().put("codec", "opus").put("container", "webm"))
                .put("numChannels", 1).put("sampleFrequencyHz", 24000))
            listOf("chat" to 1, "control" to 3, "input" to 8, "message" to 1).forEach { (channel, version) ->
                configuration.put(channel, JSONObject().put("minVersion", 1).put("maxVersion", version))
            }
            if(microphone) configuration.put("chatStream",JSONObject().put("minVersion",1).put("maxVersion",1))
            sessionRequest(allocation, "$path/sdp", "SDP_OFFER", "POST", JSONObject().put("messageType", "offer")
                .put("requestId", requestId).put("sdp", offer).put("configuration", configuration))
            peer.onOfferPosted()
            onStage(PlaybackStage.OFFER_POSTED)
            val answerObject=exchange(allocation, "$path/sdp", "SDP_ANSWER",requestId) as? JSONObject
            peer.configureInput(answerObject?.optInt("input",0) ?: 0)
            peer.configureChat(microphone && (answerObject?.optInt("chatStream",0) ?: 0)>0)
            val answer = answerObject?.optString("sdp")
                ?.takeIf { it.isNotBlank() } ?: throw IOException("SDP_ANSWER: resposta sem SDP")
            requireActive(allocation)
            peer.setRemoteAnswer(answer)
            requireActive(allocation)
            onStage(PlaybackStage.ANSWER_APPLIED)
            onProgress("ICE: Negociando conectividade com o Xbox...")
            sessionRequest(allocation, "$path/ice", "ICE_LOCAL", "POST", CloudProtocol.icePayload(candidates))
            val remote = CloudProtocol.remoteCandidates(exchange(allocation, "$path/ice", "ICE_REMOTE"))
            if (remote.isEmpty()) throw IOException("ICE_REMOTE: o serviço não retornou candidatos")
            requireActive(allocation)
            peer.addRemoteCandidates(remote)
            onProgress("TRANSPORT: Aguardando ICE e canais de controle...")
            peer.awaitReady()
            requireActive(allocation)
            onStage(PlaybackStage.TRANSPORT_READY)
            return UiText.text("Transporte conectado. Aguardando o primeiro quadro de vídeo...")
    }

    suspend fun renegotiateChat(offer: String): String {
        val allocation = active.get() ?: throw IOException("CHAT: sessão encerrada")
        return withContext(Dispatchers.IO) {
            allocation.transportLock.withLock {
                val path = allocation.path
                val requestId = (++allocation.sdpRequest).toString()
                sessionRequest(allocation, "$path/sdp", "CHAT_OFFER", "POST", JSONObject().put("messageType","offer")
                    .put("requestId",requestId).put("sdp",offer)
                    .put("configuration",JSONObject().put("isMediaStreamsChatRenegotiation",true)))
                val answer = exchange(allocation, "$path/sdp", "CHAT_ANSWER", requestId) as? JSONObject
                    ?: throw IOException("CHAT: resposta inválida")
                answer.optString("sdp").takeIf { it.isNotBlank() } ?: throw IOException("CHAT: resposta sem SDP")
            }
        }
    }

    suspend fun keepAlive() {
        val allocation = active.get() ?: return
        withContext(Dispatchers.IO) { sendKeepAlive(allocation, "KEEPALIVE") }
    }

    fun keepAliveDiagnostics() = lastAttempt.keepAlive.diagnostics()

    suspend fun maintainSession() {
        val allocation = active.get() ?: return
        withContext(Dispatchers.IO) {
            allocation.attempt.keepAlive.run {
                if (active.get() !== allocation) throw CancellationException(UiText.text("Manutenção da sessão substituída"))
                sendKeepAlive(allocation, "KEEPALIVE", allocation.attempt.keepAlive::recordFailure)
            }
        }
    }

    private suspend fun sendKeepAlive(allocation: Allocation, stage: String,
                                      onAuthorizationRejected: (CloudHttpException) -> Unit = {}) {
        val current = authorization
        val path = "${allocation.path}/keepalive"
        try {
            sessionRequest(allocation, path, stage, "POST", client = keepAliveClient, bearer = current.gssvToken)
        } catch (rejected: CloudHttpException) {
            val canRenew = when (current) {
                is XCloudSession -> renewCloudAuthorization != null
                is RemotePlaySession -> renewRemotePlayAuthorization != null
            }
            if (rejected.status != 401 || !canRenew) throw rejected
            onAuthorizationRejected(rejected)
            val replacement = authorizationLock.withLock {
                requireActive(allocation)
                val latest = authorization
                if (latest !== current) return@withLock latest
                val renewed = withTimeoutOrNull(20_000) {
                    when (current) {
                        is XCloudSession -> renewCloudAuthorization?.invoke(current)
                        is RemotePlaySession -> renewRemotePlayAuthorization?.invoke(current)
                    }
                } ?: throw rejected
                requireActive(allocation)
                val base = allocation.attempt.profile.baseUri.trimEnd('/')
                if (renewed.gssvToken.isBlank() || !sameAuthorizationScope(current, renewed, base)) throw rejected
                authorization = renewed
                renewed
            }
            sessionRequest(allocation, path, stage, "POST", client = keepAliveClient, bearer = replacement.gssvToken)
        }
    }

    private fun sameAuthorizationScope(current: StreamingAuthorization, renewed: StreamingAuthorization, base: String): Boolean =
        when (current) {
            is XCloudSession -> renewed is XCloudSession && renewed.credentialGeneration == current.credentialGeneration &&
                renewed.libraryAccountId == current.libraryAccountId &&
                (renewed.baseUri.trimEnd('/') == base || renewed.regions.any { it.baseUri.trimEnd('/') == base })
            is RemotePlaySession -> renewed is RemotePlaySession && renewed.credentialGeneration == current.credentialGeneration &&
                renewed.baseUri.trimEnd('/') == base
        }

    suspend fun stop() {
        val attempt = lastAttempt
        withContext(NonCancellable + Dispatchers.IO) {
            allocationLock.withLock {
                active.get()?.takeIf { it.attempt === attempt }?.let { deleteAllocation(it) }
            }
        }
    }

    /** Called with the allocation lock, so replacement PLAY waits for DELETE to finish. */
    private suspend fun deleteAllocation(allocation: Allocation) {
        if (!active.compareAndSet(allocation, null)) return
        val profile = allocation.attempt.profile
        withContext(NonCancellable) {
            withTimeoutOrNull(5000) {
                try {
                    request(allocation.path, "STOP", "DELETE", base = profile.baseUri, resolution = profile.resolution)
                } catch (_: IOException) { /* Server TTL is the fallback. */ }
            }
        }
    }
}
