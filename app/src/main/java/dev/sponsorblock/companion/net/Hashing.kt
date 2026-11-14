package dev.sponsorblock.companion.net

import java.security.MessageDigest

/**
 * SponsorBlock's privacy-preserving hash prefix.
 * The extension computes sha256(videoID) ONCE, hex-encodes it, and sends the first 5 chars
 * (maze-utils getHash(videoID, 1).slice(0, 5)). The server returns every video sharing that
 * prefix; we filter to the exact videoID client-side.
 */
object Hashing {
  /** Lowercase hex SHA-256 of the input. */
  fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  /** The first [len] hex characters of the video id's hash. */
  fun prefix(
    videoId: String,
    len: Int = 5
  ): String = sha256Hex(videoId).substring(0, len)
}
