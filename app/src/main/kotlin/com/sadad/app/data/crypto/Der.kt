package com.sadad.app.data.crypto

/**
 * Between the two signature forms the app and the `api` use. `java.security.Signature` produces and
 * verifies DER; `proof.ts` expects the raw 64-byte `r‖s` that WebCrypto verifies.
 */
object Der {
  private const val COORDINATE = 32

  /** `r‖s`, 64 bytes: each coordinate right-aligned in 32 bytes with any DER sign byte dropped. */
  fun toRaw(der: ByteArray): ByteArray {
    var p = 2
    if (der[1].toInt() and 0xff > 127) p = 2 + (der[1].toInt() and 0x7f)
    check(der[p++].toInt() == 0x02) { "DER: expected r" }
    val rLength = der[p++].toInt() and 0xff
    val r = der.copyOfRange(p, p + rLength)
    p += rLength
    check(der[p++].toInt() == 0x02) { "DER: expected s" }
    val sLength = der[p++].toInt() and 0xff
    val s = der.copyOfRange(p, p + sLength)
    val raw = ByteArray(2 * COORDINATE)
    fixed32(r).copyInto(raw, 0)
    fixed32(s).copyInto(raw, COORDINATE)
    return raw
  }

  /** The DER form `java.security.Signature.verify` takes, from the raw 64 bytes. */
  fun toDer(raw: ByteArray): ByteArray {
    require(raw.size == 2 * COORDINATE) { "raw signature must be 64 bytes, was ${raw.size}" }
    val r = unsigned(raw.copyOfRange(0, COORDINATE))
    val s = unsigned(raw.copyOfRange(COORDINATE, 2 * COORDINATE))
    val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
    return byteArrayOf(0x30, body.size.toByte()) + body
  }

  /** Shortest big-endian form, with one `0x00` prefix when the top bit would make it negative. */
  private fun unsigned(bytes: ByteArray): ByteArray {
    var start = 0
    while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
    val trimmed = bytes.copyOfRange(start, bytes.size)
    return if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
  }
}

/**
 * An unsigned big-endian coordinate right-aligned in 32 bytes: a DER sign byte is dropped and a
 * short value is left-padded.
 */
internal fun fixed32(bytes: ByteArray): ByteArray {
  val out = ByteArray(32)
  val size = minOf(bytes.size, 32)
  bytes.copyInto(out, 32 - size, bytes.size - size)
  return out
}
