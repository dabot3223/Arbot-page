package com.arbot.core.api

import kotlinx.serialization.Serializable

/** Action names understood by supabase/functions/api. */
object Actions {
    const val REGISTER = "auth.register"
    const val PROFILE = "profile.get"
    const val RENAME = "profile.rename"
    const val GAME_START = "game.start"
    const val GAME_FINISH = "game.finish"
    const val LEADERBOARD = "leaderboard.top"
    const val STORE_BUY = "store.buy"
    const val SKIN_EQUIP = "skin.equip"
    const val PURCHASE_VERIFY = "purchase.verify"
}

@Serializable
data class RegisterResult(val playerId: String, val displayName: String)

@Serializable
data class Player(
    val id: String,
    val displayName: String,
    val gems: Int,
    val premium: Boolean,
    val equippedSkin: String,
    val bestScore: Int,
    val gamesPlayed: Int,
)

@Serializable
data class InventoryItem(val itemId: String, val quantity: Int)

@Serializable
data class CatalogItem(val itemId: String, val kind: String, val priceGems: Int, val displayName: String)

@Serializable
data class IapProduct(val productId: String, val kind: String, val gems: Int, val grantsPremium: Boolean)

@Serializable
data class ProfileBundle(
    val player: Player,
    val inventory: List<InventoryItem>,
    val catalog: List<CatalogItem>,
    val iapProducts: List<IapProduct>,
)

@Serializable
data class GameSessionInfo(
    val sessionId: String,
    val seed: Long,
    val startLives: Int,
    val powerStart: Boolean,
)

@Serializable
data class GameResult(
    val score: Int,
    val gemsEarned: Int,
    val gems: Int,
    val bestScore: Int,
    val newBest: Boolean,
)

@Serializable
data class LeaderboardEntry(val rank: Int, val displayName: String, val bestScore: Int, val isMe: Boolean)

@Serializable
data class Leaderboard(val entries: List<LeaderboardEntry>)

@Serializable
data class WalletUpdate(
    val gems: Int,
    val premium: Boolean,
    val equippedSkin: String,
    val inventory: List<InventoryItem>,
)

@Serializable
data class PurchaseResult(
    /** "granted", "already_granted" or "pending". */
    val status: String,
    val gemsGranted: Int,
    val gems: Int,
    val premium: Boolean,
)
