package com.arbot.game.data

import com.arbot.core.api.Actions
import com.arbot.core.api.ApiJson
import com.arbot.core.api.GameResult
import com.arbot.core.api.GameSessionInfo
import com.arbot.core.api.Leaderboard
import com.arbot.core.api.ProfileBundle
import com.arbot.core.api.PurchaseResult
import com.arbot.core.api.RegisterResult
import com.arbot.core.api.SecureApiCore
import com.arbot.core.api.WalletUpdate
import com.arbot.core.crypto.B64
import com.arbot.core.crypto.Hashes
import com.arbot.game.security.DeviceKeyManager
import com.arbot.game.security.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OfflineException : Exception("Online features are unavailable")

/**
 * Typed facade over the encrypted API. Every call is signed with the device
 * key and sealed in an envelope by [SecureApiCore]; sensitive actions also
 * carry a Play Integrity token.
 */
class GameRepository(
    private val api: SecureApiCore?,
    private val store: SecureStore,
    private val deviceKey: DeviceKeyManager,
    private val compromised: () -> Boolean,
) {
    private val registerLock = Mutex()
    private val _profile = MutableStateFlow<ProfileBundle?>(cachedProfile())
    val profile: StateFlow<ProfileBundle?> = _profile.asStateFlow()

    val isOnlineCapable: Boolean get() = api != null && !compromised()

    private fun core(): SecureApiCore = api?.takeIf { !compromised() } ?: throw OfflineException()

    private suspend fun playerId(): String = registerLock.withLock {
        store.getString(KEY_PLAYER)?.let { return it }
        val spki = B64.encode(deviceKey.publicKeySpki())
        val data = core().call(
            Actions.REGISTER,
            buildJsonObject { put("devicePublicKey", spki) },
            subject = null,
            requireIntegrity = true,
        )
        val result = ApiJson.decodeFromJsonElement(RegisterResult.serializer(), data)
        store.putString(KEY_PLAYER, result.playerId)
        result.playerId
    }

    private suspend fun <T> call(
        action: String,
        payload: JsonElement,
        serializer: KSerializer<T>,
        integrity: Boolean = false,
    ): T {
        // The identity is never reset automatically on errors: a misconfigured
        // server must not be able to make players lose their accounts.
        val id = playerId()
        return ApiJson.decodeFromJsonElement(serializer, core().call(action, payload, id, integrity))
    }

    suspend fun refreshProfile(): ProfileBundle =
        call(Actions.PROFILE, buildJsonObject { }, ProfileBundle.serializer()).also(::setProfile)

    suspend fun rename(name: String): ProfileBundle =
        call(Actions.RENAME, buildJsonObject { put("name", name) }, ProfileBundle.serializer()).also(::setProfile)

    suspend fun startGame(useExtraLife: Boolean, usePowerStart: Boolean): GameSessionInfo =
        call(
            Actions.GAME_START,
            buildJsonObject {
                put("useExtraLife", useExtraLife)
                put("usePowerStart", usePowerStart)
            },
            GameSessionInfo.serializer(),
        ).also {
            // Boosters were consumed server-side; refresh lazily.
            if (useExtraLife || usePowerStart) runCatching { refreshProfile() }
        }

    suspend fun finishGame(sessionId: String, inputs: List<Int>, finalTick: Int, score: Int): GameResult =
        call(
            Actions.GAME_FINISH,
            buildJsonObject {
                put("sessionId", sessionId)
                put("inputs", JsonArray(inputs.map { JsonPrimitive(it) }))
                put("finalTick", finalTick)
                put("claimedScore", score)
            },
            GameResult.serializer(),
            integrity = true,
        ).also { r ->
            _profile.value?.let { p ->
                setProfile(p.copy(player = p.player.copy(gems = r.gems, bestScore = r.bestScore, gamesPlayed = p.player.gamesPlayed + 1)))
            }
        }

    suspend fun leaderboard(): Leaderboard =
        call(Actions.LEADERBOARD, buildJsonObject { put("limit", 50) }, Leaderboard.serializer())

    suspend fun buy(itemId: String): WalletUpdate =
        call(Actions.STORE_BUY, buildJsonObject { put("itemId", itemId) }, WalletUpdate.serializer()).also(::applyWallet)

    suspend fun equip(skinId: String): WalletUpdate =
        call(Actions.SKIN_EQUIP, buildJsonObject { put("skinId", skinId) }, WalletUpdate.serializer()).also(::applyWallet)

    suspend fun verifyPurchase(productId: String, purchaseToken: String): PurchaseResult =
        call(
            Actions.PURCHASE_VERIFY,
            buildJsonObject {
                put("productId", productId)
                put("purchaseToken", purchaseToken)
            },
            PurchaseResult.serializer(),
            integrity = true,
        ).also { r ->
            _profile.value?.let { p -> setProfile(p.copy(player = p.player.copy(gems = r.gems, premium = r.premium))) }
        }

    /**
     * Passed to Play Billing as obfuscatedAccountId. The server only credits a
     * purchase whose obfuscatedExternalAccountId equals sha256(its player id).
     */
    suspend fun obfuscatedAccountId(): String = Hashes.sha256Hex(playerId().toByteArray())

    private fun applyWallet(w: WalletUpdate) {
        _profile.value?.let { p ->
            setProfile(
                p.copy(
                    player = p.player.copy(gems = w.gems, premium = w.premium, equippedSkin = w.equippedSkin),
                    inventory = w.inventory,
                ),
            )
        }
    }

    private fun setProfile(p: ProfileBundle) {
        _profile.value = p
        store.putString(KEY_PROFILE, ApiJson.encodeToString(ProfileBundle.serializer(), p))
    }

    private fun cachedProfile(): ProfileBundle? = store.getString(KEY_PROFILE)?.let {
        runCatching { ApiJson.decodeFromString(ProfileBundle.serializer(), it) }.getOrNull()
    }

    private companion object {
        const val KEY_PLAYER = "player_id"
        const val KEY_PROFILE = "profile_cache"
    }
}
