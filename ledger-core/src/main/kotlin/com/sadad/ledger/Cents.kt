package com.sadad.ledger

import kotlinx.serialization.Serializable

/**
 * An amount of money in integer cents (agorot). It is the only money type: there is no conversion
 * from floating point or text, and no formatting (the UI formats). Arithmetic throws on overflow
 * instead of wrapping. On the wire it is a bare JSON integer.
 */
@JvmInline
@Serializable
value class Cents(val value: Long) : Comparable<Cents> {
  operator fun plus(other: Cents): Cents = Cents(Math.addExact(value, other.value))

  operator fun minus(other: Cents): Cents = Cents(Math.subtractExact(value, other.value))

  override fun compareTo(other: Cents): Int = value.compareTo(other.value)

  companion object {
    val ZERO = Cents(0)

    /** The largest amount `24_apply_one.sql` accepts (2^53 - 1, the largest safe JSON integer). */
    val MAX_WIRE = Cents(9_007_199_254_740_991)
  }
}
