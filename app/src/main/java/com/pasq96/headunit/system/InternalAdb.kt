package com.pasq96.headunit.system

import android.content.Context
import android.util.Base64
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

/**
 * Minimal ADB client talking to the unit's own adbd over loopback (classic TCP protocol, RSA auth).
 * Needs USB debugging + adb over network (port 5555); the first connection shows the usual "allow debugging" prompt.
 * Wireless-debugging (TLS pairing, Android 11+) is not supported.
 */
object InternalAdb {
    enum class Fail { CONNECT, AUTH }
    class AdbException(val fail: Fail, msg: String? = null) : Exception(msg)

    private const val PORT = 5555
    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f
    private const val CLSE = 0x45534c43
    private const val WRTE = 0x45545257
    private const val VERSION = 0x01000000
    private const val MAX_DATA = 4096
    private val LE = ByteOrder.LITTLE_ENDIAN
    // DER DigestInfo prefix for SHA-1: adbd treats the 20-byte token as an already computed digest
    private val SHA1_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    // ---- session ----

    class Session internal constructor(private val sock: Socket, private val out: OutputStream, private val inp: DataInputStream) : Closeable {
        private var nextId = 1

        /** Runs [cmd] in the adb shell; returns (exit code or -1, output). */
        fun shell(cmd: String): Pair<Int, String> {
            val local = nextId++
            send(out, OPEN, local, 0, "shell:$cmd; echo __rc=\$?\u0000".toByteArray())
            val sb = StringBuilder()
            loop@ while (true) {
                val m = recv(inp)
                if (m.a1 != local && m.cmd != OKAY) continue
                when (m.cmd) {
                    WRTE -> { sb.append(String(m.data)); send(out, OKAY, local, m.a0) }
                    CLSE -> { runCatching { send(out, CLSE, local, m.a0) }; break@loop }
                }
            }
            val text = sb.toString()
            val rc = Regex("__rc=(\\d+)").findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: -1
            return rc to text.replace(Regex("__rc=\\d+\\s*$"), "").trim()
        }

        override fun close() { runCatching { sock.close() } }
    }

    /** Connects and authenticates; throws [AdbException]. Blocking, call off the main thread. */
    fun open(ctx: Context): Session {
        val s = Socket()
        try { s.connect(InetSocketAddress("127.0.0.1", PORT), 2000) } catch (e: IOException) { throw AdbException(Fail.CONNECT, e.message) }
        s.tcpNoDelay = true; s.soTimeout = 10_000
        try {
            val out = s.getOutputStream(); val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            send(out, CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            val kp = keyPair(ctx)
            var stage = 0
            while (true) {
                val m = recv(inp)
                when (m.cmd) {
                    CNXN -> { s.soTimeout = 20_000; return Session(s, out, inp) }
                    AUTH -> when (stage++) {
                        0 -> send(out, AUTH, 2, 0, sign(kp, m.data))
                        1 -> { send(out, AUTH, 3, 0, publicKeyLine(kp.public as RSAPublicKey)); s.soTimeout = 90_000 } // waits for the user prompt
                        else -> throw AdbException(Fail.AUTH)
                    }
                    else -> throw AdbException(Fail.CONNECT, "unexpected message")
                }
            }
        } catch (e: AdbException) { runCatching { s.close() }; throw e }
        catch (e: SocketTimeoutException) { runCatching { s.close() }; throw AdbException(Fail.AUTH, e.message) }
        catch (e: IOException) { runCatching { s.close() }; throw AdbException(Fail.CONNECT, e.message) }
    }

    /** One-shot privileged command; true when it exited 0 without a Java exception in the output. */
    fun run(ctx: Context, cmd: String): Boolean = runCatching { open(ctx).use { ok(it.shell(cmd)) } }.getOrDefault(false)

    fun ok(r: Pair<Int, String>): Boolean = r.first == 0 && !r.second.contains("Exception")

    // ---- wire format ----

    private fun send(out: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(24).order(LE)
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd xor -1)
        out.write(h.array()); if (data.isNotEmpty()) out.write(data); out.flush()
    }

    private fun recv(inp: DataInputStream): Msg {
        val raw = ByteArray(24); inp.readFully(raw)
        val h = ByteBuffer.wrap(raw).order(LE)
        val cmd = h.int; val a0 = h.int; val a1 = h.int; val len = h.int
        val d = ByteArray(len); if (len > 0) inp.readFully(d)
        return Msg(cmd, a0, a1, d)
    }

    // ---- keys ----

    private fun keyPair(ctx: Context): KeyPair {
        val f = File(ctx.filesDir, "adbkey")
        if (f.exists()) runCatching {
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(f.readBytes())) as RSAPrivateCrtKey
            return KeyPair(kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent)), priv)
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        f.writeBytes(kp.private.encoded)
        return kp
    }

    private fun sign(kp: KeyPair, token: ByteArray): ByteArray =
        Signature.getInstance("NONEwithRSA").run { initSign(kp.private); update(SHA1_INFO + token); sign() }

    /** Android mincrypt layout: words, -1/n0 mod 2^32, n (LE), R^2 mod n (LE), exponent; base64 + comment + NUL. */
    private fun publicKeyLine(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus; val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = two32.subtract(n.mod(two32).modInverse(two32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val b = ByteBuffer.allocate(524).order(LE)
        b.putInt(64).putInt(n0inv).put(le(n, 256)).put(le(rr, 256)).putInt(pub.publicExponent.toInt())
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " headunit@launcher\u0000").toByteArray()
    }

    private fun le(v: BigInteger, len: Int): ByteArray {
        val be = v.toByteArray(); val out = ByteArray(len)
        for (i in 0 until len) { val j = be.size - 1 - i; if (j >= 0) out[i] = be[j] }
        return out
    }
}
