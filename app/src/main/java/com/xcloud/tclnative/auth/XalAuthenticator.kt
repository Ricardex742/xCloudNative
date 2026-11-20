package com.xcloud.tclnative.auth

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.net.readResponse
import com.xcloud.tclnative.net.checkedBody
import com.xcloud.tclnative.catalog.XboxProfile
import com.xcloud.tclnative.settings.RegionBypass
import com.xcloud.tclnative.settings.RegionBypassStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CloudRegion(val name: String,val baseUri: String,val isDefault: Boolean=false)

sealed interface StreamingAuthorization {
    val gssvToken: String
    val baseUri: String
}

class RemotePlaySession internal constructor(
    override val gssvToken: String,
    override val baseUri: String,
    internal val credentialGeneration: Long
) : StreamingAuthorization

data class XCloudSession(
    override val gssvToken: String, override val baseUri: String, val userHash: String,
    val expiresAtMs: Long = System.currentTimeMillis() + 3600_000L, val transferToken: String = "",
    val xuid: String? = null,val regions: List<CloudRegion> = emptyList(),
    val regionBypass: RegionBypass = RegionBypass.OFF, val credentialGeneration: Long? = null
) : StreamingAuthorization {
    val libraryAccountId: String get() = xuid?.let { "xuid:$it" } ?: userHash
}
data class AuthEndpoints(
    val deviceCodeUrl: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
    val tokenUrl: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
    val userAuthUrl: String = "https://user.auth.xboxlive.com/user/authenticate",
    val xstsAuthorizeUrl: String = "https://xsts.auth.xboxlive.com/xsts/authorize",
    val xcloudLoginUrl: String = "https://xgpuweb.gssv-play-prod.xboxlive.com/v2/login/user",
    val transferTokenUrl: String = "https://login.live.com/oauth20_token.srf",
    val profileUrl: String = "https://profile.xboxlive.com/users/me/profile/settings?settings=GameDisplayName,GameDisplayPicRaw,Gamertag",
    val authorizeUrl: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
    val remotePlayLoginUrl: String = "https://xhome.gssv-play-prod.xboxlive.com/v2/login/user"
)
data class DeviceCodeInfo(
    val userCode: String, val deviceCode: String, val verificationUri: String,
    val verificationUriComplete: String?, val intervalSeconds: Long, val expiresAtMs: Long,
    val qrBitmap: Bitmap? = null, val credentialGeneration: Long? = null
)
class AuthenticationRequiredException(message: String = UiText.text("Autenticação necessária. Entre novamente na sua conta.")) : Exception(message)

class XalAuthenticator(
    store: OAuthTokenStore,
    private val httpClient: OkHttpClient,
    private val endpoints: AuthEndpoints = AuthEndpoints(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val regionBypass: () -> RegionBypass = { RegionBypass.OFF }
) {
    private val directLoginClient = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private data class ProfileGrant(val token: String, val hash: String, val generation: Long)

    private val credentials = AccountCredentials(store)
    @Volatile private var profileGrant: ProfileGrant? = null

    private fun currentProfile(): ProfileGrant {
        val profile = profileGrant ?: throw AuthenticationRequiredException()
        credentials.requireCurrent(profile.generation)
        return profile
    }

    private fun requireCurrent(profile: ProfileGrant) {
        credentials.requireCurrent(profile.generation)
        if (profileGrant !== profile) throw AuthenticationRequiredException()
    }
    constructor(context: Context) : this(PreferencesTokenStore(context),
        OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build(), regionBypass = RegionBypassStore(context)::read)

    companion object {
        const val BROWSER_CLIENT_ID = "000000004c12ae6f"
        const val DEVICE_CLIENT_ID = "0000000048183522"
        const val XCLOUD_CLIENT_ID = "1f907974-e22b-4810-a9de-d9647380c97e"
        const val LIVE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        const val MSAL_SCOPE = "xboxlive.signin openid profile offline_access"
        const val TRANSFER_TOKEN_SCOPE = "service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN"
        fun normalizeRpsTicket(raw: String): String = raw.trim().let {
            if (it.startsWith("t=") || it.startsWith("d=")) it else "t=$it"
        }
    }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private fun json(value: JSONObject) = value.toString().toRequestBody(jsonType)
    private suspend fun post(url: String, stage: String, body: RequestBody, forwardedFor: String? = null,
                             direct: Boolean = false): String =
        (if (forwardedFor == null && !direct) httpClient else directLoginClient).newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("x-xbl-contract-version", "1").header("X-Gssv-Client", "XboxComBrowser")
            .apply { forwardedFor?.let { header("X-Forwarded-For", it) } }
            .post(body).build()).readResponse { it.checkedBody(stage) }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    fun saveTokens(accessToken: String, refreshToken: String? = null, expiresInSeconds: Long? = null,
                   clientId: String? = null) {
        saveCredentials(accessToken, refreshToken, expiresInSeconds, clientId)
    }

    private fun saveCredentials(accessToken: String, refreshToken: String? = null, expiresInSeconds: Long? = null,
                                clientId: String? = null, expectedGeneration: Long? = null): AccountCredentials.Snapshot {
        val ticket = normalizeRpsTicket(accessToken)
        return credentials.replace(expectedGeneration) { previous ->
            val old = previous?.takeIf { normalizeRpsTicket(it.ticket) == ticket }
            OAuthToken(ticket, refreshToken ?: old?.refreshToken,
                expiresInSeconds?.let { clockMs() + it * 1000 } ?: old?.expiresAtMs,
                clientId ?: old?.clientId ?: BROWSER_CLIENT_ID)
        }
    }

    fun saveRpsTicket(ticket: String) = saveTokens(ticket)
    fun getSavedRpsTicket(): String? = credentials.snapshot().token?.ticket
    fun clearSession() {
        credentials.clear()
        profileGrant = null
    }
    fun supportsConsoleTransfer(): Boolean = credentials.snapshot().token?.let {
        it.clientId == XCLOUD_CLIENT_ID && !it.refreshToken.isNullOrBlank()
    } == true

    fun beginBrowserLogin() = BrowserAuthorization.create(clockMs(), endpoints.authorizeUrl, credentials.snapshot().generation)
    suspend fun completeBrowserLogin(request: BrowserAuthorization,code: String): Result<String> = result {
        val generation = request.credentialGeneration ?: credentials.snapshot().generation
        credentials.requireCurrent(generation)
        val data=JSONObject(post(endpoints.tokenUrl,"AUTH_BROWSER",request.exchangeForm(code,clockMs())))
        val access=data.getString("access_token").takeIf { it.isNotBlank() }
            ?: throw AuthenticationRequiredException("AUTH_BROWSER: access token missing")
        val refresh=data.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: throw AuthenticationRequiredException("AUTH_BROWSER: refresh token missing")
        val expires=data.getLong("expires_in").takeIf { it in 1..86_400 }
            ?: throw AuthenticationRequiredException("AUTH_BROWSER: invalid token lifetime")
        checkNotNull(saveCredentials(access, refresh, expires, XCLOUD_CLIENT_ID, generation).token).ticket
    }

    suspend fun requestDeviceCodeQr(): Result<DeviceCodeInfo> = result {
        val generation = credentials.snapshot().generation
        val data = JSONObject(post(endpoints.deviceCodeUrl, "AUTH_DEVICE_CODE", FormBody.Builder()
            .add("client_id", XCLOUD_CLIENT_ID).add("scope", MSAL_SCOPE).build()))
        val uri = data.getString("verification_uri")
        val complete = data.optString("verification_uri_complete").takeIf { it.isNotBlank() }
        // The provider's URI is authoritative; display the separate user code when no complete URI exists.
        val bitmap = runCatching { generateQrCodeBitmap(complete ?: uri) }.getOrNull()
        DeviceCodeInfo(data.getString("user_code"), data.getString("device_code"), uri, complete,
            data.optLong("interval", 5).coerceAtLeast(1), clockMs() + data.getLong("expires_in") * 1000, bitmap, generation)
    }

    suspend fun pollForDeviceCodeLogin(info: DeviceCodeInfo): Result<String> = result {
        val generation = info.credentialGeneration ?: credentials.snapshot().generation
        var interval = info.intervalSeconds.coerceAtLeast(1) * 1000
        while (clockMs() < info.expiresAtMs) {
            pause(minOf(interval, info.expiresAtMs - clockMs()))
            if (clockMs() >= info.expiresAtMs) break
            credentials.requireCurrent(generation)
            try {
                val data = JSONObject(post(endpoints.tokenUrl, "AUTH_TOKEN", FormBody.Builder()
                    .add("client_id", XCLOUD_CLIENT_ID).add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("device_code", info.deviceCode).build()))
                val refresh = data.optString("refresh_token").takeIf { it.isNotBlank() }
                    ?: throw AuthenticationRequiredException("AUTH_TOKEN: login sem renovação. Solicite outro QR Code.")
                return@result checkNotNull(saveCredentials(data.getString("access_token"), refresh,
                    data.getLong("expires_in"), XCLOUD_CLIENT_ID, generation).token).ticket
            } catch (error: CloudHttpException) {
                when (error.serviceCode) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5000
                    "authorization_declined", "access_denied", "expired_token" -> throw AuthenticationRequiredException("AUTH_TOKEN: aprovação negada ou expirada.")
                    else -> throw error
                }
            }
        }
        throw AuthenticationRequiredException("AUTH_TOKEN: o código expirou; solicite outro QR Code.")
    }

    private suspend fun refresh(generation: Long, previous: OAuthToken): OAuthToken =
        credentials.rotate(generation) { snapshot ->
            val token = snapshot.token ?: throw AuthenticationRequiredException()
            if (token.ticket != previous.ticket || token.expiresAtMs != previous.expiresAtMs) return@rotate token
            val refreshToken = token.refreshToken ?: throw AuthenticationRequiredException()
            val data = try {
                val msal = token.clientId == XCLOUD_CLIENT_ID
                JSONObject(post(if (msal) endpoints.tokenUrl else endpoints.transferTokenUrl, "AUTH_REFRESH",
                    FormBody.Builder().add("grant_type", "refresh_token").add("client_id", token.clientId)
                        .add("refresh_token", refreshToken).add("scope", if (msal) MSAL_SCOPE else LIVE_SCOPE).build()))
            } catch (error: CloudHttpException) {
                if (error.serviceCode == "invalid_grant") throw AuthenticationRequiredException(UiText.text("Sua sessão precisa ser renovada. Entre novamente na sua conta."))
                throw error
            }
            OAuthToken(normalizeRpsTicket(data.getString("access_token")),
                data.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken,
                clockMs() + data.getLong("expires_in") * 1000, token.clientId).also { credentials.saveRotation(snapshot, it) }
        }

    suspend fun authenticateToXCloud(rpsTicketInput: String? = null, onProgress: (String) -> Unit = {}): Result<XCloudSession> = result {
        val bypass = regionBypass()
        profileGrant = null
        var snapshot = credentials.snapshot()
        if (!rpsTicketInput.isNullOrBlank() && snapshot.token?.let { normalizeRpsTicket(it.ticket) } != normalizeRpsTicket(rpsTicketInput)) {
            snapshot = saveCredentials(rpsTicketInput, expectedGeneration = snapshot.generation)
        }
        val grant = streamingGrant(snapshot,onProgress)
        val generation = grant.generation
        val user = grant.user
        val xsts = grant.xsts
        val hash = user.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")
        val xuid = listOf(xsts, user).firstNotNullOfOrNull { response ->
            response.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0)
                ?.optString("xid")?.takeIf { it.matches(Regex("[0-9]{1,20}")) }
        }
        onProgress("AUTH_GSSV: Conectando ao xCloud...")
        val cloud = JSONObject(post(endpoints.xcloudLoginUrl, "AUTH_GSSV", json(JSONObject()
            .put("token", xsts.getString("Token")).put("offeringId", "xgpuweb")), forwardedFor = bypass.forwardedFor))
        val regions = cloud.getJSONObject("offeringSettings").getJSONArray("regions")
        val region = (0 until regions.length()).map { regions.getJSONObject(it) }.firstOrNull { it.optBoolean("isDefault") }
            ?: throw java.io.IOException("AUTH_GSSV: o serviço não informou uma região padrão")
        credentials.requireCurrent(generation)
        profileGrant = ProfileGrant(user.getString("Token"), hash, generation)
        XCloudSession(cloud.getString("gsToken"), region.getString("baseUri").trimEnd('/'), hash,
            clockMs() + cloud.optLong("durationInSeconds", 3600) * 1000, xuid = xuid, regionBypass = bypass, credentialGeneration = generation,
            regions=(0 until regions.length()).mapNotNull { index ->
                val item=regions.getJSONObject(index)
                val uri=item.optString("baseUri").trimEnd('/')
                val parsed=runCatching { java.net.URI(uri) }.getOrNull()
                if(parsed==null || parsed.host.isNullOrBlank() || parsed.userInfo!=null || parsed.query!=null || parsed.fragment!=null ||
                    (parsed.scheme!="https" && !(parsed.scheme=="http" && parsed.host in listOf("localhost","127.0.0.1","::1")))) null
                else CloudRegion(item.optString("name").ifBlank { parsed.host }.take(100),uri,item.optBoolean("isDefault"))
            }.distinctBy { it.name })
    }

    suspend fun authenticateToRemotePlay(): Result<RemotePlaySession> = result {
        val grant = streamingGrant(credentials.snapshot())
        val home = JSONObject(post(endpoints.remotePlayLoginUrl, "REMOTE_PLAY_AUTH", json(JSONObject()
            .put("token", grant.xsts.getString("Token")).put("offeringId", "xhome")), direct = true))
        val regions = home.getJSONObject("offeringSettings").getJSONArray("regions")
        val region = (0 until regions.length()).map { regions.getJSONObject(it) }.firstOrNull { it.optBoolean("isDefault") }
            ?: throw java.io.IOException("REMOTE_PLAY_AUTH: região padrão ausente")
        val uri = java.net.URI(region.getString("baseUri"))
        val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        val localTest = java.net.URI(endpoints.remotePlayLoginUrl).let {
            it.scheme == "http" && it.host in listOf("localhost", "127.0.0.1", "[::1]") &&
                uri.scheme == it.scheme && uri.host == it.host && uri.port == it.port
        }
        require((localTest || uri.scheme == "https" && uri.port in listOf(-1, 443) &&
            host.endsWith(".gssv-play-prodxhome.xboxlive.com")) && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path in listOf("", "/")) {
            "REMOTE_PLAY_AUTH: região inválida"
        }
        val token = home.optString("gsToken").takeIf { it.isNotBlank() && it != "null" }
            ?: throw java.io.IOException("REMOTE_PLAY_AUTH: credencial ausente")
        credentials.requireCurrent(grant.generation)
        RemotePlaySession(token, uri.toString().trimEnd('/'), grant.generation)
    }

    private class StreamingGrant(val user: JSONObject, val xsts: JSONObject, val generation: Long)

    private suspend fun streamingGrant(snapshot: AccountCredentials.Snapshot,
                                       onProgress: (String) -> Unit = {}): StreamingGrant {
        val generation = snapshot.generation
        var current = snapshot.token ?: throw AuthenticationRequiredException()
        if (current.expiresAtMs?.let { it <= clockMs() } == true) current = refresh(generation, current)
        onProgress("AUTH_XBL: Autenticando na Xbox Live...")
        suspend fun userTicket(token: OAuthToken): JSONObject {
            val ticket = if (token.clientId == DEVICE_CLIENT_ID || token.clientId == XCLOUD_CLIENT_ID)
                "d=" + token.ticket.removePrefix("t=").removePrefix("d=")
                else normalizeRpsTicket(token.ticket)
            return JSONObject(post(endpoints.userAuthUrl, "AUTH_XBL", json(JSONObject()
                .put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT").put("Properties", JSONObject()
                    .put("AuthMethod", "RPS").put("SiteName", "user.auth.xboxlive.com").put("RpsTicket", ticket)))))
        }
        val user = try { userTicket(current) } catch (error: CloudHttpException) {
            if (error.status != 401) throw error
            current = refresh(generation, current)
            userTicket(current)
        }
        onProgress("AUTH_XSTS: Autorizando o acesso à nuvem...")
        val xsts = JSONObject(post(endpoints.xstsAuthorizeUrl, "AUTH_XSTS", json(JSONObject()
            .put("RelyingParty", "http://gssv.xboxlive.com/").put("TokenType", "JWT").put("Properties", JSONObject()
                .put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(user.getString("Token")))))))
        credentials.requireCurrent(generation)
        return StreamingGrant(user, xsts, generation)
    }

    /** Store rights use the Microsoft commerce audience, never the cloud bearer. Optional and read-only. */
    suspend fun loadStoreAuthorization(): Result<String> = result {
        val profile = currentProfile()
        val (token, hash) = profile
        val response=JSONObject(post(endpoints.xstsAuthorizeUrl,"STORE_XSTS",json(JSONObject()
            .put("RelyingParty","http://mp.microsoft.com/").put("TokenType","JWT").put("Properties",JSONObject()
                .put("SandboxId","RETAIL").put("UserTokens",JSONArray().put(token))))))
        requireCurrent(profile)
        val user=response.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0)
            ?.optString("uhs")?.takeIf { it.isNotBlank() } ?: hash
        "XBL3.0 x=$user;${response.getString("Token")}"
    }

    /** Read-only social services require the Xbox Live audience, never the streaming bearer. */
    suspend fun loadXboxSocialAuthorization(): Result<String> = result {
        val profile = currentProfile()
        val (token, hash) = profile
        val response=JSONObject(post(endpoints.xstsAuthorizeUrl,"SOCIAL_XSTS",json(JSONObject()
            .put("RelyingParty","http://xboxlive.com").put("TokenType","JWT").put("Properties",JSONObject()
                .put("SandboxId","RETAIL").put("UserTokens",JSONArray().put(token))))))
        requireCurrent(profile)
        val user=response.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0)
            ?.optString("uhs")?.takeIf { it.isNotBlank() } ?: hash
        "XBL3.0 x=$user;${response.getString("Token")}"
    }

    /** Optional Xbox profile grant is separate from GSSV/console transfer and never blocks playing. */
    suspend fun loadXboxProfile(): Result<XboxProfile> = result {
        val profile = currentProfile()
        val (token, hash) = profile
        val web = JSONObject(post(endpoints.xstsAuthorizeUrl, "PROFILE_XSTS", json(JSONObject()
            .put("RelyingParty", "http://xboxlive.com").put("TokenType", "JWT").put("Properties", JSONObject()
                .put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(token))))))
        val webHash = web.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0)
            ?.optString("uhs")?.takeIf { it.isNotBlank() } ?: hash
        val request = Request.Builder().url(endpoints.profileUrl)
            .header("Authorization", "XBL3.0 x=$webHash;${web.getString("Token")}")
            .header("x-xbl-contract-version", "2").header("Accept-Language", "pt-BR")
            .header("Accept", "application/json").build()
        httpClient.newCall(request).readResponse { XboxProfile.parse(JSONObject(it.checkedBody("PROFILE"))) }
            .also { requireCurrent(profile) }
    }

    /** Requested only when a ReadyToConnect session needs /connect; never use the GSSV token here. */
    suspend fun getConsoleTransferToken(generation: Long? = null): String = withContext(Dispatchers.IO) {
        credentials.rotate(generation) { snapshot ->
            val token = snapshot.token ?: throw AuthenticationRequiredException()
            if (token.clientId != XCLOUD_CLIENT_ID) throw AuthenticationRequiredException(
                "CONSOLE_TOKEN: atualize o login pelo QR Code desta versão para autorizar a conexão Xbox.")
            val refresh = token.refreshToken ?: throw AuthenticationRequiredException(
                "CONSOLE_CONNECT: este login não possui refresh token. Entre novamente pelo QR Code.")
            val data = try {
                JSONObject(post(endpoints.transferTokenUrl, "CONSOLE_TOKEN", FormBody.Builder()
                    .add("grant_type", "refresh_token").add("client_id", token.clientId)
                    .add("refresh_token", refresh).add("scope", TRANSFER_TOKEN_SCOPE).build()))
            } catch (error: CloudHttpException) {
                if (error.serviceCode in setOf("invalid_grant", "invalid_scope", "consent_required")) throw AuthenticationRequiredException(
                    "CONSOLE_TOKEN: login sem permissão de conexão (${error.serviceCode}). Entre novamente pelo QR Code desta versão.")
                throw error
            }
            val rotatedRefresh = data.optString("refresh_token").takeIf { it.isNotBlank() } ?: refresh
            credentials.saveRotation(snapshot, token.copy(refreshToken = rotatedRefresh))
            data.getString("access_token").takeIf { it.isNotBlank() }
                ?: throw AuthenticationRequiredException("CONSOLE_TOKEN: o serviço não retornou credencial de transferência.")
        }
    }

    fun generateQrCodeBitmap(content: String, size: Int = 420): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap ->
            for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
}
