@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.max.core.auth

import com.max.core.protocol.CmdType
import com.max.core.protocol.DefaultMessagePackCodec
import com.max.core.protocol.Opcode
import com.max.core.protocol.PROTOCOL_VERSION
import com.max.core.protocol.PacketHeader
import com.max.core.protocol.decodePayloadPacket
import com.max.core.session.DeviceInfo
import com.max.core.session.HandshakeInfo
import com.max.core.session.SessionConfig
import com.max.core.session.SessionMachine
import com.max.core.session.SessionState
import com.max.core.session.UserAgentInfo
import com.max.core.transport.FakeRawConnection
import com.max.core.transport.ScriptedConnectionFactory
import com.max.core.transport.ServerErrorException
import com.max.core.transport.TransportConfig
import com.max.core.transport.TransportPacket
import com.max.core.transport.errorReply
import com.max.core.transport.ok
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Expected bytes below were produced by running the reference code (PyMax
 * `pymax.fingerprint` and its payload models, serialized with msgpack-python) with fixed inputs.
 */
class AuthApiTest {

    private val device = DeviceInfo(deviceId = "d1e9c0de00000001", instanceId = "a1b2c3d4e5f60718", clientSessionId = 17)
    private val webDevice = DeviceInfo(
        deviceId = "w",
        userAgent = UserAgentInfo(deviceType = "WEB", appVersion = "26.8.4", osVersion = "Linux", screen = "1080x1920 1.0x", deviceName = "Chrome"),
    )
    private val seed = 1234567890123L
    private val handshake = HandshakeInfo(callsSeed = seed, deviceName = null, appUpdateType = null, payload = mapOf("callsSeed" to seed))

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun encode(v: Any?) = DefaultMessagePackCodec.encode(v)

    private val f1 = "4808e303d2fcd05aeae7525dce6dbc79b6362ab44bc6be1073289206840f6b903e71df52c88491bcf24f13b7fc81ab66caf40b272cbda4fded4a4852944569865d53cdccff3d66aad8903c46d02a72594a137eff2ab7bf97537d331d12930e42"

    /** Records requests and answers from a script: a payload map for OK, a [ServerErrorException] to throw. */
    private class FakeSink(vararg replies: Any?) : RequestSink {
        val script = ArrayDeque(replies.toList())
        val sent = ArrayList<Pair<Opcode, Any?>>()
        override suspend fun request(opcode: Opcode, payload: Any?): TransportPacket {
            sent += opcode to payload
            val next = script.removeFirst()
            if (next is Throwable) throw next
            return TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.OK.value, sent.size, opcode.value.toShort(), 0, false), next)
        }
    }

    private fun serverError(opcode: Opcode, error: String?, message: String?): ServerErrorException {
        val payload = buildMap { error?.let { put("error", it) }; message?.let { put("message", it) } }
        return ServerErrorException.from(TransportPacket(PacketHeader(PROTOCOL_VERSION, CmdType.ERROR.value, 1, opcode.value.toShort(), 0, false), payload))
    }

    private fun api(sink: RequestSink, d: DeviceInfo = device) = AuthApi(sink, d, { handshake })

    @Test
    fun sha256KnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.digest().hex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.digest("abc".encodeToByteArray()).hex())
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).hex(),
        )
        // split input == joined input; > 1 block
        val long = ByteArray(1000) { it.toByte() }
        assertContentEquals(Sha256.digest(long), Sha256.digest(long.copyOfRange(0, 63), long.copyOfRange(63, 1000)))
    }

    @Test
    fun fingerprintMatchesReference() {
        val fp = ApkFingerprint.forVersion("26.25.0")!!
        assertEquals(96, fp.compute(seed, "d1e9c0de00000001").size)
        assertEquals(f1, fp.compute(seed, "d1e9c0de00000001").hex())
        assertEquals(
            "5144ff11e59d4f4e7066f32d3cf660f06b3d5989a085576a013cc8912da1ce82b7aaa2a1e62eb462f1e9eba940e6225b5841133e62d53d275e52bf3db34de4a4539bd2757b7769b296446d25bdf9ac928ab1755ee6f2b574e801c7c3c8489e10",
            fp.compute(-42, "d1e9c0de00000001", "x86_64").hex(),
        )
        assertNull(ApkFingerprint.forVersion("1.0.0"))
        assertTrue(ApkFingerprint.forVersion("26.20.2") != null)
    }

    @Test
    fun requestCodePayloadAndReply() = runTest {
        val sink = FakeSink(
            mapOf("token" to "verify-1", "codeLength" to 6, "requestMaxDuration" to 60000, "requestCountLeft" to 4, "altActionDuration" to 30000, "extra" to 1),
            mapOf("token" to "verify-2"),
        )
        val r = api(sink).requestCode("+79990001122")
        assertEquals(CodeRequest("verify-1", 6, 60000, 4, 30000, r.raw), r)
        assertEquals(1, r.raw["extra"])
        val (op, payload) = sink.sent[0]
        assertEquals(Opcode.AUTH_REQUEST, op)
        assertEquals(17, op.value)
        // kolibri order: phone, type, language, mode
        assertEquals(listOf("phone", "type", "language", "mode"), (payload as Map<*, *>).keys.toList())
        assertEquals("START_AUTH", payload["type"])
        assertEquals("ru", payload["language"])
        assertEquals(f1, (payload["mode"] as ByteArray).hex())

        // resend without language == PyMax RequestCodePayload bytes (with RESEND instead of START_AUTH)
        val resend = api(sink).requestCode("+79990001122", CodeRequestType.RESEND, language = null)
        assertEquals(CodeRequest("verify-2", null, null, null, null, resend.raw), resend)
        assertEquals("RESEND", (sink.sent[1].second as Map<*, *>)["type"])
        assertEquals(
            "83a570686f6e65ac2b3739393930303031313232a474797065aa53544152545f41555448a46d6f6465c4604808e303d2fcd05aeae7525dce6dbc79b6362ab44bc6be1073289206840f6b903e71df52c88491bcf24f13b7fc81ab66caf40b272cbda4fded4a4852944569865d53cdccff3d66aad8903c46d02a72594a137eff2ab7bf97537d331d12930e42",
            encode(api(sink).requestCodePayload("+79990001122", CodeRequestType.START_AUTH, null, handshake)).hex(),
        )
        // web: no mode (PyMax), so no callsSeed needed
        assertEquals(listOf("phone", "type", "language"), api(sink, webDevice).requestCodePayload("+7", CodeRequestType.START_AUTH, "ru", null).keys.toList())
    }

    @Test
    fun requestCodeErrors() = runTest {
        val err = serverError(Opcode.AUTH_REQUEST, "phone.invalid", "Invalid phone")
        assertSame(err, assertFailsWith<ServerErrorException> { api(FakeSink(err)).requestCode("x") })
        assertFailsWith<AuthException> { api(FakeSink(mapOf("codeLength" to 6))).requestCode("x") }
        // mobile without callsSeed / without digests for the version
        val noSeed = AuthApi(FakeSink(), device, { null })
        assertTrue(assertFailsWith<AuthException> { noSeed.requestCode("x") }.message!!.contains("callsSeed"))
        val unknown = AuthApi(FakeSink(), device.copy(userAgent = UserAgentInfo(appVersion = "9.9.9")), { handshake })
        assertTrue(assertFailsWith<AuthException> { unknown.requestCode("x") }.message!!.contains("9.9.9"))
    }

    @Test
    fun unknownArchIsAuthException() = runTest {
        val mips = device.copy(userAgent = device.userAgent.copy(arch = "mips"))
        assertTrue(assertFailsWith<AuthException> { api(FakeSink(), mips).requestCode("x") }.message!!.contains("mips"))
        assertTrue(assertFailsWith<AuthException> { api(FakeSink(), mips).login("tok") }.message!!.contains("mips"))
        // an empty arch still falls back to arm64-v8a
        val empty = device.copy(userAgent = device.userAgent.copy(arch = ""))
        assertEquals(96, (api(FakeSink(), empty).requestCodePayload("+7", CodeRequestType.START_AUTH, "ru", handshake)["mode"] as ByteArray).size)
    }

    @Test
    fun verifyCodeLoggedIn() = runTest {
        val profile = mapOf("contact" to mapOf("id" to 123456789L, "names" to listOf(mapOf("name" to "Ivan"))))
        val sink = FakeSink(mapOf("tokenAttrs" to mapOf("LOGIN" to mapOf("token" to "login-token")), "profile" to profile))
        val r = api(sink).verifyCode("tmp", "123456")
        assertIs<VerifyResult.LoggedIn>(r)
        assertEquals("login-token", r.loginToken)
        assertEquals(123456789L, r.userId)
        assertEquals(profile, r.profile)
        assertEquals(Opcode.AUTH, sink.sent[0].first)
        assertEquals(18, Opcode.AUTH.value)
        assertEquals(
            "83a5746f6b656ea3746d70aa766572696679436f6465a6313233343536ad61757468546f6b656e54797065aa434845434b5f434f4445",
            encode(sink.sent[0].second).hex(),
        )
        // no profile in the reply
        val bare = api(FakeSink(mapOf("tokenAttrs" to mapOf("LOGIN" to mapOf("token" to "t"))))).verifyCode("tmp", "1")
        assertEquals(VerifyResult.LoggedIn("t", null, null, (bare as VerifyResult.LoggedIn).raw), bare)
    }

    @Test
    fun verifyCodePasswordAndRegistration() = runTest {
        val pw = api(FakeSink(mapOf("passwordChallenge" to mapOf("trackId" to "track-1", "hint" to "cat"), "tokenAttrs" to emptyMap<String, Any>()))).verifyCode("tmp", "1")
        assertIs<VerifyResult.PasswordRequired>(pw)
        assertEquals("track-1", pw.trackId)
        assertEquals("cat", pw.hint)
        val pwNoHint = api(FakeSink(mapOf("passwordChallenge" to mapOf("trackId" to "t2")))).verifyCode("tmp", "1")
        assertEquals(VerifyResult.PasswordRequired("t2", null, pwNoHint.raw), pwNoHint)

        val reg = api(FakeSink(mapOf("tokenAttrs" to mapOf("REGISTER" to mapOf("token" to "reg-token"))))).verifyCode("tmp", "1")
        assertIs<VerifyResult.RegistrationRequired>(reg)
        assertEquals("reg-token", reg.registerToken)

        assertFailsWith<AuthException> { api(FakeSink(mapOf("tokenAttrs" to emptyMap<String, Any>()))).verifyCode("tmp", "1") }
        assertFailsWith<AuthException> { api(FakeSink(null)).verifyCode("tmp", "1") }
        val wrong = serverError(Opcode.AUTH, "verify.code.wrong", "Wrong code")
        val thrown = assertFailsWith<ServerErrorException> { api(FakeSink(wrong)).verifyCode("tmp", "000000") }
        assertEquals("verify.code.wrong", thrown.errorKey)
    }

    @Test
    fun tokenLoginPayloadMatchesReference() {
        val mobile = api(FakeSink()).loginPayload("stored-token", SyncState(), true, null, handshake)
        assertEquals(
            "8aa9757365724167656e748baa64657669636554797065a7414e44524f4944aa61707056657273696f6ea732362e32352e30a96f7356657273696f6eaa416e64726f6964203134a874696d657a6f6e65ad4575726f70652f4d6f73636f77a673637265656eb73432386470692034323864706920313038307832343030ae7075736844657669636554797065a347434da461726368a961726d36342d763861a66c6f63616c65a27275ab6275696c644e756d626572cd1a86aa6465766963654e616d65a7506978656c2038ac6465766963654c6f63616c65a27275a5746f6b656eac73746f7265642d746f6b656eb463686174436163686546696e6765727072696e74c4604808e303d2fcd05aeae7525dce6dbc79b6362ab44bc6be1073289206840f6b903e71df52c88491bcf24f13b7fc81ab66caf40b272cbda4fded4a4852944569865d53cdccff3d66aad8903c46d02a72594a137eff2ab7bf97537d331d12930e42a9636861747353796e63ffac636f6e746163747353796e63ffaa64726166747353796e63ffab696e746572616374697665c3ac70726573656e636553796e63ffa365787081b06368617473436f756e7447726f757073c4020a32aa636f6e66696748617368d96030303030303030302d303030303030303030303030303030302d30303030303030302d303030303030303030303030303030302d303030303030303030303030303030302d302d303030303030303030303030303030302d3030303030303030",
            encode(mobile).hex(),
        )
        val web = AuthApi(FakeSink(), webDevice, { null }).loginPayload("stored-token", SyncState(), true, null, null)
        assertEquals(
            "87a5746f6b656eac73746f7265642d746f6b656eaa6368617473436f756e7428ab696e746572616374697665c3a9636861747353796e63ffac636f6e746163747353796e63ffac70726573656e636553796e63ffaa64726166747353796e63ff",
            encode(web).hex(),
        )
        // chatsCount only when set, right after chatCacheFingerprint
        val withCount = api(FakeSink()).loginPayload("t", SyncState(chatsSync = 5, configHash = 7L), false, 20, handshake)
        assertEquals(
            listOf("userAgent", "token", "chatCacheFingerprint", "chatsCount", "chatsSync", "contactsSync", "draftsSync", "interactive", "presenceSync", "exp", "configHash"),
            withCount.keys.toList(),
        )
        assertEquals(5L, withCount["chatsSync"])
        assertEquals(false, withCount["interactive"])
        assertEquals(7L, withCount["configHash"])
    }

    @Test
    fun loginParsesReplyAndMapsInvalidToken() = runTest {
        val reply = mapOf(
            "profile" to mapOf("contact" to mapOf("id" to 77)),
            "chats" to listOf(mapOf("id" to 1), mapOf("id" to 2)),
            "token" to "refreshed",
            "time" to 1_700_000_000_000L,
            "config" to mapOf("hash" to "cfg-hash"),
            "login2Flags" to mapOf("x" to 1),
            "messages" to emptyMap<String, Any>(),
        )
        val sink = FakeSink(reply)
        val r = api(sink).login("stored-token")
        assertEquals(Opcode.LOGIN, sink.sent[0].first)
        assertEquals(19, Opcode.LOGIN.value)
        assertEquals(77L, r.userId)
        assertEquals(2, r.chats.size)
        assertEquals("refreshed", r.token)
        assertEquals(1_700_000_000_000L, r.time)
        assertEquals("cfg-hash", r.configHash)
        assertEquals(mapOf("x" to 1), r.login2Flags)
        assertEquals(SyncState(1_700_000_000_000L, 1_700_000_000_000L, 1_700_000_000_000L, 1_700_000_000_000L, "cfg-hash"), SyncState().updatedBy(r))
        assertEquals(SyncState(), SyncState().updatedBy(LoginResult.from(emptyMap<String, Any>())))

        for ((error, message) in listOf("login.token" to "FAIL_LOGIN_TOKEN", "FAIL_LOGIN_TOKEN" to null, "x" to "FAIL_LOGOUT_ALL")) {
            val err = serverError(Opcode.LOGIN, error, message)
            val e = assertFailsWith<InvalidTokenException> { api(FakeSink(err)).login("old") }
            assertSame(err, e.serverError)
            assertSame(err, e.cause)
        }
        // other errors pass through
        val other = serverError(Opcode.LOGIN, "service.unavailable", "later")
        assertSame(other, assertFailsWith<ServerErrorException> { api(FakeSink(other)).login("t") })
    }

    // --- SessionMachine integration ---

    private val quiet = TransportConfig(host = "api.test", pingInterval = Duration.INFINITE, autoReconnect = false)

    private suspend fun FakeRawConnection.answer(opcode: Opcode, reply: Any?): Any? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue)
        feed(ok(header.seq, opcode.value, reply))
        return payload
    }

    private suspend fun FakeRawConnection.fail(opcode: Opcode, reply: Any?): Any? {
        val (header, payload) = decodePayloadPacket(takeWritten()!!)
        assertEquals(opcode.value, header.opcodeValue)
        feed(errorReply(header.seq, opcode.value, reply))
        return payload
    }

    @Test
    fun sessionReachesOnlineThroughTokenLogin() = runTest {
        val factory = ScriptedConnectionFactory()
        val login = AuthApi.tokenLoginHook("stored-token", device)
        val m = SessionMachine(SessionConfig(quiet, device), factory, scope = backgroundScope, afterHandshake = login.hook)
        val connecting = async { m.connect() }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to seed))
        runCurrent()
        assertEquals(SessionState.Handshaking, m.state.value)
        assertNull(login.result.value)
        val payload = conn.answer(Opcode.LOGIN, mapOf("profile" to mapOf("contact" to mapOf("id" to 5)), "chats" to listOf(mapOf("id" to 1)), "time" to 99))
        // the wire payload is exactly the PyMax reference LOGIN (same UA, token, fingerprint of callsSeed)
        assertContentEquals(encode(api(FakeSink()).loginPayload("stored-token", SyncState(), true, null, handshake)), encode(payload))
        assertEquals(f1, ((payload as Map<*, *>)["chatCacheFingerprint"] as ByteArray).hex())
        val info = connecting.await()
        assertEquals(seed, info.callsSeed)
        assertEquals(SessionState.Online(info), m.state.value)
        assertEquals(5L, login.result.value!!.userId)
        assertEquals(listOf(mapOf("id" to 1)), login.result.value!!.chats)
        assertEquals(99L, login.sync.chatsSync)
        assertEquals("stored-token", login.token)
        // requests after login go through the session (AuthApi over SessionMachine)
        val sessionApi = AuthApi(m)
        val code = async { sessionApi.requestCode("+7") }
        runCurrent()
        val req = conn.answer(Opcode.AUTH_REQUEST, mapOf("token" to "v"))
        assertEquals(f1, ((req as Map<*, *>)["mode"] as ByteArray).hex())
        assertEquals("v", code.await().token)
        m.disconnect()
    }

    @Test
    fun invalidTokenFailsSession() = runTest {
        val factory = ScriptedConnectionFactory()
        val login = TokenLogin("revoked", device)
        val m = SessionMachine(SessionConfig(quiet, device), factory, scope = backgroundScope, afterHandshake = login.hook)
        val connecting = async { runCatching { m.connect() } }
        runCurrent()
        val conn = factory.lastConnection!!
        conn.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1))
        runCurrent()
        conn.fail(Opcode.LOGIN, mapOf("error" to "login.token", "message" to "FAIL_LOGIN_TOKEN", "localizedMessage" to "Session expired"))
        val error = connecting.await().exceptionOrNull()
        assertIs<InvalidTokenException>(error)
        assertEquals("login.token", error.serverError.errorKey)
        assertSame(error, (m.state.value as SessionState.Failed).cause)
        assertNull(login.result.value)
    }

    @Test
    fun reconnectReLogsInWithUpdatedStateAndStopsOnInvalidToken() = runTest {
        val factory = ScriptedConnectionFactory()
        val login = TokenLogin("stored-token", device)
        val m = SessionMachine(SessionConfig(quiet.copy(autoReconnect = true), device), factory, scope = backgroundScope, afterHandshake = login.hook)
        val connecting = async { m.connect() }
        runCurrent()
        factory.lastConnection!!.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 1))
        runCurrent()
        factory.lastConnection!!.answer(Opcode.LOGIN, mapOf("token" to "refreshed", "time" to 1000, "config" to mapOf("hash" to "h1")))
        connecting.await()
        assertEquals("refreshed", login.token)

        factory.lastConnection!!.close() // drop -> reconnect after 2 s
        advanceTimeBy(2_001)
        runCurrent()
        val second = factory.lastConnection!!
        second.answer(Opcode.SESSION_INIT, mapOf("callsSeed" to 2))
        runCurrent()
        val relogin = second.fail(Opcode.LOGIN, mapOf("error" to "FAIL_LOGIN_TOKEN")) as Map<*, *>
        assertEquals("refreshed", relogin["token"])
        assertEquals(1000L, (relogin["chatsSync"] as Number).toLong())
        assertEquals("h1", relogin["configHash"])
        runCurrent()
        assertIs<InvalidTokenException>((m.state.value as SessionState.Failed).cause)
        // fatal: no further reconnect attempts
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, factory.openCount)
        assertIs<SessionState.Failed>(m.state.value)
    }
}
