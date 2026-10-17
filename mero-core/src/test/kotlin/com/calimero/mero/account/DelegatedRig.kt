package com.calimero.mero.account

import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import java.net.URLEncoder

/**
 * A [FakeNode] (cloud manager + hosted relay on one server) and a [CloudAccount] enrolled
 * against it, for the delegated-account tests. [extra] starts more relays on demand.
 */
internal class DelegatedRig(
    accountHasRelay: Boolean = true,
) : AutoCloseable {
    val node = FakeNode()
    val server: MockWebServer = node.start()
    val base: String = server.url("/").toString().trimEnd('/')
    val store = MemorySecureStore()
    val account = CloudAccount(store, cloudBaseUrl = base, walletUrl = "https://wallet.example/account-enroll")
    private val others = mutableListOf<MockWebServer>()
    val session: DelegatedSession

    init {
        node.cloud.accountHasRelay = accountHasRelay
        session = runBlocking { account.completeEnrolment(walletApproves(account.beginEnrolment("mero-sample://enrol")))!! }
    }

    /** Another relay (its own [FakeNode]) and its base URL. */
    fun extra(): Pair<FakeNode, String> {
        val other = FakeNode()
        val s = other.start()
        others += s
        return other to s.url("/").toString().trimEnd('/')
    }

    override fun close() {
        server.shutdown()
        others.forEach { it.shutdown() }
    }

    private fun walletApproves(walletUrl: String): String {
        val root = SeedSigner("5c".repeat(32))
        val url = walletUrl.toHttpUrl()
        val acc = DeviceCert.accountForRootPublicKey(root.publicKey)
        val device = DeviceCert.mintDeviceId(acc, ByteArray(16) { 7 })
        val credential = DeviceCert.sign(root, device, url.queryParameter("enrol-device")!!, url.queryParameter("enrol-kem")!!)
        val state = URLEncoder.encode(url.queryParameter("state")!!, "UTF-8")
        return "${url.queryParameter("callback-url")}#credential=$credential&account=$acc&device=$device&state=$state"
    }

    companion object {
        val NS = "77".repeat(32)

        /** A context listing entry, as `GET /admin-api/contexts` carries it. */
        fun contextEntry(id: String): JsonObject =
            buildJsonObject {
                put("id", id)
                put("applicationId", "88".repeat(32))
                put("contextStateHash", "00".repeat(32))
                put("dagHeads", buildJsonArray { })
            }

        /** A namespace listing entry, as `GET /admin-api/namespaces` carries it. */
        fun namespaceEntry(
            id: String,
            app: String = "88".repeat(32),
        ): JsonObject =
            buildJsonObject {
                put("namespaceId", id)
                put("appKey", "99".repeat(32))
                put("targetApplicationId", app)
                put("createdAt", 1)
                put("memberCount", 1)
                put("contextCount", 0)
                put("subgroupCount", 0)
            }

        /** An invitation to [namespaceByte]-filled group naming [admitter]; [addrs] ride unsigned. */
        fun invitation(
            admitter: String,
            addrs: List<String> = emptyList(),
        ): SignedGroupOpenInvitation {
            fun bytes(v: Int) = buildJsonArray { repeat(32) { add(JsonPrimitive(v)) } }
            return SignedGroupOpenInvitation(
                buildJsonObject {
                    put(
                        "invitation",
                        buildJsonObject {
                            put("inviter_identity", bytes(1))
                            put("group_id", bytes(0x77))
                            put("expiration_timestamp", 1_900_000_000)
                            put("secret_salt", bytes(3))
                            put("invited_role", 1)
                            put("admitters", buildJsonArray { add(JsonPrimitive(admitter)) })
                        },
                    )
                    put("inviter_signature", "ab".repeat(64))
                    put("inviter_account", "22".repeat(32))
                    if (addrs.isNotEmpty()) put("admitter_addrs", buildJsonArray { addrs.forEach { add(JsonPrimitive(it)) } })
                },
            )
        }
    }
}
