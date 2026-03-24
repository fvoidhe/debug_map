package com.intellij.debugmap

import java.security.SecureRandom

private val NANO_ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray()
private val RANDOM = SecureRandom()

/** Generates a NanoID-compatible random string (URL-safe alphabet, default 8 characters). */
fun generateNanoId(size: Int = 8): String {
  val mask = (2 shl (31 - Integer.numberOfLeadingZeros(NANO_ID_ALPHABET.size - 1))) - 1
  val step = (1.6 * mask * size / NANO_ID_ALPHABET.size).toInt() + 1
  val result = CharArray(size)
  var filled = 0
  val bytes = ByteArray(step)
  while (filled < size) {
    RANDOM.nextBytes(bytes)
    for (b in bytes) {
      val idx = b.toInt() and mask
      if (idx < NANO_ID_ALPHABET.size) {
        result[filled++] = NANO_ID_ALPHABET[idx]
        if (filled == size) break
      }
    }
  }
  return String(result)
}
