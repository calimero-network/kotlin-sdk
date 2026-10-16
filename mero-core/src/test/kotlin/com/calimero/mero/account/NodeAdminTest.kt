package com.calimero.mero.account

import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.admin.Application
import com.calimero.mero.admin.ApplicationBlob
import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.admin.CreateNamespaceResponseData
import com.calimero.mero.admin.InstallApplicationRequest
import com.calimero.mero.admin.InstallApplicationResponseData
import com.calimero.mero.admin.ListApplicationsResponseData
import com.calimero.mero.admin.NodeIdentity
import com.calimero.mero.http.MeroStateException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** mero-js `node-admin.test.ts`. */
class NodeAdminTest {
    private val app = "88".repeat(32)

    private inner class Node(
        var installed: List<String> = emptyList(),
        val installsAs: String = app,
    ) : AdminSurface by NoRelayAdmin {
        val installs = mutableListOf<InstallApplicationRequest>()
        var created = 0

        override suspend fun listApplications() = ListApplicationsResponseData(installed.map { application(it) })

        override suspend fun installApplication(request: InstallApplicationRequest): InstallApplicationResponseData {
            installs += request
            return InstallApplicationResponseData(installsAs)
        }

        override suspend fun createNamespace(request: CreateNamespaceRequest): CreateNamespaceResponseData {
            created++
            return CreateNamespaceResponseData("55".repeat(32))
        }

        override suspend fun getNodeIdentity() = NodeIdentity("aa".repeat(32), publicKey = "p", accountRootPublicKey = "r")
    }

    private fun application(id: String) =
        Application(id, ApplicationBlob("b", "c"), 1, "s", emptyList(), "signer", "com.calimero.chat", "1.0.0")

    private val pkg = AccountApp(packageName = "com.calimero.chat")

    @Test
    fun `creates the namespace straight away when the app is installed`() =
        runBlocking {
            val node = Node(installed = listOf(app))
            createNodeAdmin(node, pkg) { _, _ -> error("not asked") }.createNamespace(CreateNamespaceRequest(app))
            assertTrue(node.installs.isEmpty())
            assertEquals(1, node.created)
        }

    @Test
    fun `installs the app from the registry first when the node lacks it`() =
        runBlocking {
            val node = Node()
            createNodeAdmin(node, pkg) { _, _ -> "2.0.0" }.createNamespace(CreateNamespaceRequest(app))
            assertEquals(listOf(InstallApplicationRequest("com.calimero.chat", "2.0.0")), node.installs)
            assertEquals(1, node.created)
        }

    @Test
    fun `refuses when the registry bundle is not the app asked for`() =
        runBlocking {
            val node = Node(installsAs = "99".repeat(32))
            val e = runCatching { createNodeAdmin(node, pkg.copy(packageVersion = "1.0.0")).createNamespace(CreateNamespaceRequest(app)) }
            assertTrue(e.exceptionOrNull() is MeroStateException)
            assertEquals(0, node.created)
        }

    @Test
    fun `passes every other call through unchanged`() =
        runBlocking {
            assertEquals("aa".repeat(32), createNodeAdmin(Node()).getNodeIdentity().accountId)
        }
}
