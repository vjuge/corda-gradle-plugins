package com.r3corda.node.services

import com.google.common.net.HostAndPort
import com.r3corda.core.contracts.ClientToServiceCommand
import com.r3corda.core.contracts.ContractState
import com.r3corda.core.contracts.StateAndRef
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.crypto.generateKeyPair
import com.r3corda.core.messaging.Message
import com.r3corda.core.messaging.createMessage
import com.r3corda.core.node.NodeInfo
import com.r3corda.core.node.services.DEFAULT_SESSION_ID
import com.r3corda.core.node.services.NetworkMapCache
import com.r3corda.core.node.services.StateMachineTransactionMapping
import com.r3corda.core.node.services.Vault
import com.r3corda.core.transactions.SignedTransaction
import com.r3corda.core.utilities.LogHelper
import com.r3corda.node.services.config.NodeConfiguration
import com.r3corda.node.services.messaging.*
import com.r3corda.node.services.network.InMemoryNetworkMapCache
import com.r3corda.node.services.transactions.PersistentUniquenessProvider
import com.r3corda.node.utilities.AffinityExecutor
import com.r3corda.node.utilities.configureDatabase
import com.r3corda.node.utilities.databaseTransaction
import com.r3corda.testing.freeLocalHostAndPort
import com.r3corda.testing.node.makeTestDataSourceProperties
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.Database
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rx.Observable
import java.io.Closeable
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtemisMessagingTests {
    @Rule @JvmField val temporaryFolder = TemporaryFolder()

    val hostAndPort = freeLocalHostAndPort()
    val topic = "platform.self"
    val identity = generateKeyPair()

    lateinit var config: NodeConfiguration
    lateinit var dataSource: Closeable
    lateinit var database: Database

    var messagingClient: NodeMessagingClient? = null
    var messagingServer: ArtemisMessagingServer? = null

    val networkMapCache = InMemoryNetworkMapCache()

    val rpcOps = object : CordaRPCOps {
        override val protocolVersion: Int
            get() = throw UnsupportedOperationException()

        override fun stateMachinesAndUpdates(): Pair<List<StateMachineInfo>, Observable<StateMachineUpdate>> {
            throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun vaultAndUpdates(): Pair<List<StateAndRef<ContractState>>, Observable<Vault.Update>> {
            throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun verifiedTransactions(): Pair<List<SignedTransaction>, Observable<SignedTransaction>> {
            throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun stateMachineRecordedTransactionMapping(): Pair<List<StateMachineTransactionMapping>, Observable<StateMachineTransactionMapping>> {
            throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun networkMapUpdates(): Pair<List<NodeInfo>, Observable<NetworkMapCache.MapChange>> {
            throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun executeCommand(command: ClientToServiceCommand): TransactionBuildResult {
            throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun addVaultTransactionNote(txnId: SecureHash, txnNote: String) {
            throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun getVaultTransactionNotes(txnId: SecureHash): Iterable<String> {
            throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
        }
    }

    @Before
    fun setUp() {
        // TODO: create a base class that provides a default implementation
        config = object : NodeConfiguration {
            override val basedir: Path = temporaryFolder.newFolder().toPath()
            override val myLegalName: String = "me"
            override val nearestCity: String = "London"
            override val emailAddress: String = ""
            override val devMode: Boolean = true
            override val exportJMXto: String = ""
            override val keyStorePassword: String = "testpass"
            override val trustStorePassword: String = "trustpass"
        }
        LogHelper.setLevel(PersistentUniquenessProvider::class)
        val dataSourceAndDatabase = configureDatabase(makeTestDataSourceProperties())
        dataSource = dataSourceAndDatabase.first
        database = dataSourceAndDatabase.second
    }

    @After
    fun cleanUp() {
        messagingClient?.stop()
        messagingServer?.stop()
        dataSource.close()
        LogHelper.reset(PersistentUniquenessProvider::class)
    }

    @Test
    fun `server starting with the port already bound should throw`() {
        ServerSocket(hostAndPort.port).use {
            val messagingServer = createMessagingServer()
            assertThatThrownBy { messagingServer.start() }
        }
    }

    @Test
    fun `client should connect to remote server`() {
        val remoteServerAddress = freeLocalHostAndPort()

        createMessagingServer(remoteServerAddress).start()
        createMessagingClient(server = remoteServerAddress).start(rpcOps)
    }

    @Test
    fun `client should throw if remote server not found`() {
        val serverAddress = freeLocalHostAndPort()
        val invalidServerAddress = freeLocalHostAndPort()

        createMessagingServer(serverAddress).start()

        messagingClient = createMessagingClient(server = invalidServerAddress)
        assertThatThrownBy { messagingClient!!.start(rpcOps) }
        messagingClient = null
    }

    @Test
    fun `client should connect to local server`() {
        createMessagingServer().start()
        createMessagingClient().start(rpcOps)
    }

    @Test
    fun `client should be able to send message to itself`() {
        val receivedMessages = LinkedBlockingQueue<Message>()

        createMessagingServer().start()

        val messagingClient = createMessagingClient()
        messagingClient.start(rpcOps)
        thread { messagingClient.run() }

        messagingClient.addMessageHandler(topic) { message, r ->
            receivedMessages.add(message)
        }

        val message = messagingClient.createMessage(topic, DEFAULT_SESSION_ID, "first msg".toByteArray())
        messagingClient.send(message, messagingClient.myAddress)

        val actual: Message = receivedMessages.take()
        assertEquals("first msg", String(actual.data))
        assertNull(receivedMessages.poll(200, MILLISECONDS))
    }

    private fun createMessagingClient(server: HostAndPort = hostAndPort): NodeMessagingClient {
        return databaseTransaction(database) {
            NodeMessagingClient(config, server, identity.public, AffinityExecutor.ServiceAffinityExecutor("ArtemisMessagingTests", 1), database).apply {
                configureWithDevSSLCertificate()
                messagingClient = this
            }
        }
    }

    private fun createMessagingServer(local: HostAndPort = hostAndPort): ArtemisMessagingServer {
        return ArtemisMessagingServer(config, local, networkMapCache).apply {
            configureWithDevSSLCertificate()
            messagingServer = this
        }
    }
}