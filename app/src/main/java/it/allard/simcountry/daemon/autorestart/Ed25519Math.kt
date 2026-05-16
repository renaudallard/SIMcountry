/*
 * Copyright (c) 2026 Renaud Allard <renaud@allard.it>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGES.
 */

package it.allard.simcountry.daemon.autorestart

import java.math.BigInteger

/**
 * Edwards25519 group arithmetic. Used to support SPAKE2 over Ed25519 in
 * the Wireless-ADB pairing flow.
 *
 * Twisted Edwards curve: -x^2 + y^2 = 1 + d * x^2 * y^2
 *   d = -121665 / 121666 (mod p)
 *   p = 2^255 - 19
 *   subgroup order l = 2^252 + 27742317777372353535851937790883648493
 *   cofactor h = 8
 *
 * Points are kept in extended coordinates (X, Y, Z, T) where x = X/Z,
 * y = Y/Z, and T = XY/Z. Encoding follows RFC 8032: 32 bytes little
 * endian holding y, with the sign of x packed into the top bit.
 *
 * The arithmetic uses java.math.BigInteger throughout; not constant time
 * but correct, and only invoked during pairing.
 */
object Ed25519Math {

    /** Field prime p = 2^255 - 19. */
    val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    /** Subgroup order l = 2^252 + 27742317777372353535851937790883648493. */
    val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(
        BigInteger("27742317777372353535851937790883648493"),
    )

    /** Curve constant d = -121665 / 121666 (mod p). */
    val D: BigInteger = BigInteger.valueOf(-121665).mod(P)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)

    /** Constant 2 * d, precomputed for the addition formula. */
    val D2: BigInteger = D.multiply(BigInteger.TWO).mod(P)

    /** Square root of -1 (mod p); used in point decoding. */
    val I: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    data class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger) {
        fun isIdentity(): Boolean = x == BigInteger.ZERO && y == z
    }

    /** Group identity (0, 1, 1, 0). */
    val IDENTITY: Point = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** Standard Ed25519 base point. */
    val B: Point = run {
        // y = 4/5 (mod p); x = recover_x(y, sign=0)
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = recoverX(y, 0) ?: error("base point recovery failed")
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** Twisted Edwards extended-coordinate addition: returns p1 + p2. */
    fun add(p1: Point, p2: Point): Point {
        val a = p1.y.subtract(p1.x).multiply(p2.y.subtract(p2.x)).mod(P)
        val b = p1.y.add(p1.x).multiply(p2.y.add(p2.x)).mod(P)
        val c = p1.t.multiply(D2).multiply(p2.t).mod(P)
        val d = p1.z.multiply(BigInteger.TWO).multiply(p2.z).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return Point(
            x = e.multiply(f).mod(P),
            y = g.multiply(h).mod(P),
            z = f.multiply(g).mod(P),
            t = e.multiply(h).mod(P),
        )
    }

    /** -p. Negation in twisted Edwards: (-x, y, z, -t). */
    fun negate(p: Point): Point = Point(P.subtract(p.x).mod(P), p.y, p.z, P.subtract(p.t).mod(P))

    /**
     * Variable-base scalar multiplication: returns k * p.
     *
     * The scalar is treated as an exact non-negative integer — no mod-L
     * reduction. Reducing mod L would be a sound optimisation only when
     * `p` is in the prime-order subgroup, but SPAKE2 multiplies points
     * that carry small-order contamination, and the cofactor cancellation
     * requires the scalar's low bits to survive intact.
     */
    fun scalarMult(k: BigInteger, p: Point): Point {
        require(k.signum() >= 0) { "scalar must be non-negative" }
        var result = IDENTITY
        var addend = p
        var scalar = k
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    /** k * B (the standard base point). */
    fun scalarMultBase(k: BigInteger): Point = scalarMult(k, B)

    /**
     * Encode a point to its 32-byte compressed form per RFC 8032: little
     * endian encoding of the y coordinate, with the sign of x packed into
     * the most significant bit.
     */
    fun encode(p: Point): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        val out = toLittleEndian(y, 32)
        if (x.testBit(0)) {
            out[31] = (out[31].toInt() or 0x80).toByte()
        }
        return out
    }

    /**
     * Decode a 32-byte compressed point. Returns null if the bytes do not
     * encode a valid curve point.
     */
    fun decode(bytes: ByteArray): Point? {
        require(bytes.size == 32) { "compressed point must be 32 bytes" }
        val xSign = (bytes[31].toInt() ushr 7) and 1
        val yBytes = bytes.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = fromLittleEndian(yBytes)
        if (y >= P) return null
        val x = recoverX(y, xSign) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /**
     * Given y and the LSB of x, recover x such that the point lies on the
     * curve. Returns null when no valid x exists.
     */
    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val vInv = v.modInverse(P)
        val x2 = u.multiply(vInv).mod(P)
        if (x2 == BigInteger.ZERO) return if (sign == 0) BigInteger.ZERO else null
        // First candidate: x2^((p+3)/8)
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).mod(P) != x2) {
            x = x.multiply(I).mod(P)
            if (x.multiply(x).mod(P) != x2) return null
        }
        if ((x.testBit(0).let { if (it) 1 else 0 }) != sign) {
            x = P.subtract(x).mod(P)
        }
        return x
    }

    private fun toLittleEndian(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray()
        val trimmed = if (be.size > size && be[0] == 0.toByte()) be.copyOfRange(1, be.size) else be
        require(trimmed.size <= size) { "value exceeds $size bytes" }
        val out = ByteArray(size)
        for (i in trimmed.indices) {
            out[i] = trimmed[trimmed.size - 1 - i]
        }
        return out
    }

    fun fromLittleEndian(bytes: ByteArray): BigInteger {
        val be = ByteArray(bytes.size + 1)
        for (i in bytes.indices) {
            be[bytes.size - i] = bytes[i]
        }
        return BigInteger(be)
    }
}
