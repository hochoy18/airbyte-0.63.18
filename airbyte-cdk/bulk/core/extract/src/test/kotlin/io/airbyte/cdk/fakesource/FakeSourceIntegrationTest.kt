/* Copyright (c) 2024 Airbyte, Inc., all rights reserved. */
package io.airbyte.cdk.fakesource

import io.airbyte.cdk.command.SyncsTestFixture
import io.airbyte.cdk.h2.H2TestFixture
import io.airbyte.cdk.ssh.SshBastionContainer
import io.airbyte.cdk.testcontainers.DOCKER_HOST_FROM_WITHIN_CONTAINER
import java.sql.Connection
import java.sql.Statement
import org.junit.jupiter.api.Test
import org.testcontainers.Testcontainers

class FakeSourceIntegrationTest {
    @Test
    fun testSpec() {
        SyncsTestFixture.testSpec("fakesource/expected-spec.json")
    }

    @Test
    fun testCheckFailBadConfig() {
        SyncsTestFixture.testCheck(
            FakeSourceConfigurationJsonObject().apply {
                port = -1
                database = ""
            },
            "Could not connect with provided configuration",
        )
    }

    @Test
    fun testCheckFailNoDatabase() {
        H2TestFixture().use { h2: H2TestFixture ->
            val configPojo =
                FakeSourceConfigurationJsonObject().apply {
                    port = h2.port
                    database = h2.database + "_garbage"
                }
            SyncsTestFixture.testCheck(configPojo, "Error code: 90149")
        }
    }

    @Test
    fun testCheckFailNoTables() {
        H2TestFixture().use { h2: H2TestFixture ->
            val configPojo =
                FakeSourceConfigurationJsonObject().apply {
                    port = h2.port
                    database = h2.database
                }
            SyncsTestFixture.testCheck(configPojo, "Discovered zero tables")
        }
    }

    @Test
    fun testCheckSuccess() {
        H2TestFixture().use { h2: H2TestFixture ->
            h2.createConnection().use(Companion::prelude)
            val configPojo =
                FakeSourceConfigurationJsonObject().apply {
                    port = h2.port
                    database = h2.database
                }
            SyncsTestFixture.testCheck(configPojo)
        }
    }

    @Test
    fun testCheckSshTunnel() {
        H2TestFixture().use { h2: H2TestFixture ->
            h2.createConnection().use(Companion::prelude)
            Testcontainers.exposeHostPorts(h2.port)
            SshBastionContainer(tunnelingToHostPort = h2.port).use { ssh: SshBastionContainer ->
                val configPojo =
                    FakeSourceConfigurationJsonObject().apply {
                        host =
                            DOCKER_HOST_FROM_WITHIN_CONTAINER // required only because of container
                        port = h2.port
                        database = h2.database
                    }
                configPojo.setTunnelMethodValue(ssh.outerKeyAuthTunnelMethod)
                SyncsTestFixture.testCheck(configPojo)
                configPojo.setTunnelMethodValue(ssh.outerPasswordAuthTunnelMethod)
                SyncsTestFixture.testCheck(configPojo)
            }
        }
    }

    @Test
    fun testDiscover() {
        H2TestFixture().use { h2: H2TestFixture ->
            h2.createConnection().use(Companion::prelude)
            val configPojo =
                FakeSourceConfigurationJsonObject().apply {
                    port = h2.port
                    database = h2.database
                }
            SyncsTestFixture.testDiscover(configPojo, "fakesource/expected-cursor-catalog.json")
        }
    }

    @Test
    fun testReadGlobal() {
        H2TestFixture().use { h2: H2TestFixture ->
            val configPojo =
                FakeSourceConfigurationJsonObject().apply {
                    port = h2.port
                    database = h2.database
                    setCursorMethodValue(CdcCursor)
                    resumablePreferred = false
                }
            SyncsTestFixture.testSyncs(
                configPojo,
                h2::createConnection,
                Companion::prelude,
                "fakesource/expected-cdc-catalog.json",
                "fakesource/cdc-catalog.json",
                SyncsTestFixture.AfterRead.Companion.fromExpectedMessages(
                    "fakesource/expected-messages-global-cold-start.json",
                ),
            )
        }
    }

    @Test
    fun testReadStreams() {
        H2TestFixture().use { h2: H2TestFixture ->
            val configPojo =
                FakeSourceConfigurationJsonObject().apply {
                    port = h2.port
                    database = h2.database
                    resumablePreferred = true
                }
            SyncsTestFixture.testSyncs(
                configPojo,
                h2::createConnection,
                Companion::prelude,
                "fakesource/expected-cursor-catalog.json",
                "fakesource/cursor-catalog.json",
                SyncsTestFixture.AfterRead.Companion.fromExpectedMessages(
                    "fakesource/expected-messages-stream-cold-start.json",
                ),
                SyncsTestFixture.AfterRead.Companion.fromExpectedMessages(
                    "fakesource/expected-messages-stream-warm-start.json",
                ),
            )
        }
    }

    companion object {
        @JvmStatic
        fun prelude(connection: Connection) {
            for (sql in listOf(CREATE_KV, INSERT_KV, CREATE_EVENTS, INSERT_EVENTS)) {
                connection.createStatement().use { stmt: Statement -> stmt.execute(sql) }
            }
        }

        const val CREATE_KV = "CREATE TABLE kv (k INT PRIMARY KEY, v VARCHAR(60));"
        const val INSERT_KV = "INSERT INTO kv (k, v) VALUES (1, 'foo'), (2, 'bar'), (3, NULL);"
        const val CREATE_EVENTS =
            "CREATE TABLE events (" +
                "id UUID PRIMARY KEY," +
                "ts TIMESTAMP WITH TIME ZONE NOT NULL," +
                "msg VARCHAR(60));"
        const val INSERT_EVENTS =
            "INSERT INTO events (id, ts, msg) VALUES " +
                "('cc449902-30da-5ea8-c4d3-02732e5bfce9', '2024-04-29T00:00:00-04:00', 'bar')," +
                "('dd55aa13-41eb-6fb4-d5e4-13843f6c0dfa', '2024-04-30T00:00:00-04:00', NULL);"
    }
}
