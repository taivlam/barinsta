package awais.instagrabber.repositories.responses.stories

import java.io.Serializable
import awais.instagrabber.repositories.responses.User

data class Broadcast(
    val id: Long?,
    val dashPlaybackUrl: String?,
    val dashAbrPlaybackUrl: String?, // adaptive quality
    val viewerCount: Double?, // always .0
    val muted: Boolean?,
    val coverFrameUrl: String?,
    val broadcastOwner: User?,
    val publishedTime: Long?
) : Serializable