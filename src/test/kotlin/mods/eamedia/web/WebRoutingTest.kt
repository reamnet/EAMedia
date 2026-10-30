package mods.eamedia.web

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.rules.TemporaryFolder
import java.io.*
import java.net.*
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*
import kotlin.test.*

class WebRoutingTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun packetDiagnosticsDoNotIncludeWebContentOrCookies() {
        val text=WebMessage(WebMessage.DATA,1,bytes="Cookie: private-value".toByteArray()).toString()
        assertFalse(text.contains("Cookie"));assertFalse(text.contains("private-value"));assertTrue(text.contains("bytes=21"))
    }
    @Test fun operatorAllowlistHasDomainBoundariesAndRejectsMalformedEntries() {
        val domains=WebDomains(WebDomains.defaults)
        assertTrue(domains.allows("www.youtube.com"));assertTrue(domains.allows("rr1.googlevideo.com"))
        assertFalse(domains.allows("youtube.com.evil.example"));assertFalse(domains.allows("evilyoutube.com"))
        assertFalse(domains.allows("example.com"))
        for(entry in listOf("*.com","com","https://youtube.com/","youtube.com\r\nevil.example"))assertThrows(IllegalArgumentException::class.java){WebDomains(listOf(entry))}
        val file=temp.root.toPath().resolve("config/domains.txt")
        assertEquals(domains.entries,WebDomains.load(file).entries)
        Files.writeString(file,"# Operator-approved additional site\nexample.com\n")
        assertTrue(WebDomains.load(file).allows("video.example.com"));assertFalse(WebDomains.load(file).allows("youtube.com"))
    }
    @Test fun operatorWildcardAllowsPublicWebsitesButStillRejectsPrivateMixedDnsAndNonWebPorts() {
        val file=temp.root.toPath().resolve("config/domains.txt")
        WebDomains.load(file)
        assertFalse(WebDomains.load(file).allPublicWebsites)
        Files.writeString(file,"# Explicit server-owner opt-in\n*\n")
        val all=WebDomains.load(file)
        assertTrue(all.allPublicWebsites);assertTrue(all.allows("example.org"));assertTrue(all.allows("cdn.another-site.example"))
        val public=WebDestination(all,resolver={arrayOf(InetAddress.getByName("8.8.8.8"))})
        assertEquals(443,public.resolve("example.org",443).port)
        assertEquals(80,public.resolve("another.example",80).port)
        for(port in listOf(0,22,25565,65535))assertThrows(IllegalArgumentException::class.java){public.resolve("example.org",port)}
        for(ip in listOf("0.0.0.0","127.0.0.1","10.0.0.1","172.16.0.1","192.168.0.1","169.254.169.254","100.64.0.1","::1","fc00::1","fe80::1","2001:db8::1")) {
            val blocked=WebDestination(all,resolver={arrayOf(InetAddress.getByName(ip))})
            assertThrows(IllegalArgumentException::class.java){blocked.resolve("example.org",443)}
        }
        val mixed=WebDestination(all,resolver={arrayOf(InetAddress.getByName("8.8.8.8"),InetAddress.getByName("10.0.0.1"))})
        assertThrows(IllegalArgumentException::class.java){mixed.resolve("example.org",443)}
        // The server tells clients which policy is active using the existing bounded status packet.
        val status=wire(WebMessage(WebMessage.STATUS,host=all.entries.joinToString(","),credit=1))
        assertTrue(WebDomains(status.host.split(',')).allPublicWebsites)
        Files.writeString(file,"youtube.com\n")
        assertFalse(WebDomains.load(file).allPublicWebsites);assertFalse(WebDomains.load(file).allows("example.org"))
    }
    @Test fun speedtestWebsocketPortsUseTheSamePublicAddressChecksAndCanBeRestrictedByTheOperator() {
        val all=WebDomains(listOf("*"))
        val defaults=WebDestination.webPorts("80,443,8080,8443")
        assertEquals(setOf(80,443,8080,8443),defaults)
        val policy=WebDestination(all,ports=defaults,resolver={arrayOf(InetAddress.getByName("8.8.8.8"))})
        for(port in defaults)assertEquals(port,policy.resolve("speed.example.org",port).port)
        val restricted=WebDestination(all,ports=setOf(443),resolver={arrayOf(InetAddress.getByName("8.8.8.8"))})
        assertThrows(WebPortDenied::class.java){restricted.resolve("speed.example.org",8080)}
        val local=WebDestination(all,ports=defaults,resolver={arrayOf(InetAddress.getByName("127.0.0.1"))})
        assertThrows(IllegalArgumentException::class.java){local.resolve("speed.example.org",8080)}
        for(bad in listOf("","0","65536","443,nope"))assertThrows(IllegalArgumentException::class.java){WebDestination.webPorts(bad)}
    }
    @Test fun destinationApprovalRunsBeforeDnsAndRejectsPrivateOrReboundAddresses() {
        val count=AtomicInteger();val domains=WebDomains(listOf("example.com"))
        val policy=WebDestination(domains,resolver={count.incrementAndGet();arrayOf(InetAddress.getByName("8.8.8.8"))})
        assertThrows(IllegalArgumentException::class.java){policy.resolve("other.example",443)};assertEquals(0,count.get())
        assertEquals("8.8.8.8",policy.resolve("example.com",443).addresses.single().hostAddress);assertEquals(1,count.get())
        assertThrows(IllegalArgumentException::class.java){policy.resolve("example.com",22)}
        for(ip in listOf("127.0.0.1","10.0.0.1","169.254.169.254","100.64.0.1","192.168.0.1","172.16.0.1","::1","fc00::1","fe80::1","2001:db8::1")) {
            val private=WebDestination(domains,resolver={arrayOf(InetAddress.getByName(ip))})
            assertThrows(IllegalArgumentException::class.java){private.resolve("example.com",443)}
        }
        val mixed=WebDestination(domains,resolver={arrayOf(InetAddress.getByName("8.8.8.8"),InetAddress.getByName("127.0.0.1"))})
        assertThrows(IllegalArgumentException::class.java){mixed.resolve("example.com",443)}
    }
    @Test fun onlyAnActiveHostConnectionHasPermissionAndRevocationIsImmediate() {
        var now=0L;val grants=WebHostGrants{now};val host=Any();val viewer=Any();val session=UUID.randomUUID()
        assertFalse(grants.allows(host));assertFalse(grants.allows(viewer))
        assertNull(grants.touch(session,host));assertTrue(grants.allows(host));assertFalse(grants.allows(viewer))
        assertSame(host,grants.touch(session,viewer));assertFalse(grants.allows(host));assertTrue(grants.allows(viewer))
        assertSame(viewer,grants.revoke(session));assertFalse(grants.allows(viewer))
        grants.touch(session,host);now=15_000_000_001;grants.purge();assertFalse(grants.allows(host))
        grants.touch(session,host);grants.logout(host);assertFalse(grants.allows(host))
    }
    private fun wire(message: WebMessage): WebMessage {
        val b=RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY)
        try {
            WebPacket.CODEC.encode(b,WebPacket(message));assertTrue(b.readableBytes()<32767)
            return WebPacket.CODEC.decode(b).message
        }finally{b.release()}
    }
    @Test fun tunnelPacketSizesAndCreditsAreBoundedBeforeAllocation() {
        val data=ByteArray(WebMessage.CHUNK){it.toByte()};assertContentEquals(data,wire(WebMessage(WebMessage.DATA,7,bytes=data)).bytes)
        val open=wire(WebMessage(WebMessage.OPEN,9,"www.youtube.com",443));assertEquals("www.youtube.com",open.host);assertEquals(443,open.port)
        assertThrows(IllegalArgumentException::class.java){wire(WebMessage(WebMessage.DATA,1,bytes=ByteArray(WebMessage.CHUNK+1)))}
        assertThrows(IllegalArgumentException::class.java){wire(WebMessage(WebMessage.CREDIT,1,credit=WebMessage.WINDOW+1))}
    }
    private inner class Harness(val domains: WebDomains=WebDomains(listOf("127.0.0.1"))): AutoCloseable {
        val client=BrowserWebClient();private val key=Any()
        private val up=Executors.newSingleThreadExecutor();private val down=Executors.newSingleThreadExecutor()
        val peer=WebPeer(WebDestination(domains,true),{message->down.execute{client.receive(key,wire(message))}},{WebDialer.connect(it,upstream=null,testLoopback=true)})
        val port=client.start()
        init {client.attach(key,{message->up.execute{peer.receive(wire(message))}},true,domains)}
        fun socket(targetPort: Int)=Socket(Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",port))).apply{soTimeout=5000;connect(InetSocketAddress.createUnresolved("127.0.0.1",targetPort),5000)}
        override fun close(){client.close();peer.close();up.shutdownNow();down.shutdownNow()}
    }
    @Test fun wildcardPolicyRoundTripsHttpThroughTheSameSocksAndPacketTransport() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { origin->
            val served=CompletableFuture.runAsync {
                origin.accept().use { socket->
                    val reader=socket.getInputStream().bufferedReader();while(reader.readLine().isNotEmpty()){}
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\nwildcard".toByteArray())
                }
            }
            // Loopback is allowed only by the harness's explicit development-only policy.
            // The production wildcard's private-address rejection is checked separately above.
            Harness(WebDomains(listOf("*"))).use { route->
                route.socket(origin.localPort).use { browser->
                    browser.getOutputStream().write("GET /fixture HTTP/1.1\r\nHost: fixture\r\n\r\n".toByteArray())
                    assertTrue(browser.getInputStream().readAllBytes().toString(Charsets.UTF_8).endsWith("wildcard"))
                    assertTrue(route.peer.opened.get()>0)
                }
            }
            served.get(10,TimeUnit.SECONDS)
        }
    }
    @Test fun realHttpBodyLargerThanWindowSurvivesFlowControlAndHalfClose() {
        val bytes=ByteArray(250_000){(it%251).toByte()}
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { origin->
            val served=CompletableFuture.runAsync {
                origin.accept().use { socket->
                    val reader=socket.getInputStream().bufferedReader();while(reader.readLine().isNotEmpty()){}
                    val out=socket.getOutputStream();out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(bytes);out.flush()
                }
            }
            Harness().use { route->
                route.socket(origin.localPort).use { browser->
                    browser.getOutputStream().write("GET /fixture HTTP/1.1\r\nHost: fixture\r\n\r\n".toByteArray())
                    val all=browser.getInputStream().readAllBytes();val header=all.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")+4
                    assertTrue(header>=4);assertContentEquals(bytes,all.copyOfRange(header,all.size))
                    assertTrue(route.peer.downloaded.get()>WebMessage.WINDOW);assertTrue(route.client.downloaded.get()>WebMessage.WINDOW)
                }
            }
            served.get(10,TimeUnit.SECONDS)
        }
    }
    @Test fun tlsStaysBetweenBrowserAndOriginWithoutProxyCertificateSubstitution() {
        val store=temp.root.toPath().resolve("fixture.p12");val pass="local-fixture-only"
        val keytool=java.nio.file.Path.of(System.getProperty("java.home"),"bin","keytool").toString()
        val command=ProcessBuilder(keytool,"-genkeypair","-alias","fixture","-keyalg","EC","-dname","CN=localhost","-ext","SAN=DNS:localhost,IP:127.0.0.1","-validity","2","-storetype","PKCS12","-keystore",store.toString(),"-storepass",pass,"-keypass",pass).redirectErrorStream(true).start()
        assertTrue(command.waitFor(20,TimeUnit.SECONDS));assertEquals(0,command.exitValue(),command.inputStream.bufferedReader().readText())
        val keys=KeyStore.getInstance("PKCS12").apply{Files.newInputStream(store).use{load(it,pass.toCharArray())}}
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(keys,pass.toCharArray())}
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(keys)}
        val context=SSLContext.getInstance("TLS").apply{init(km.keyManagers,tm.trustManagers,null)}
        (context.serverSocketFactory.createServerSocket(0,1,InetAddress.getByName("127.0.0.1")) as SSLServerSocket).use { origin->
            val served=CompletableFuture.runAsync {origin.accept().use { socket->
                val reader=socket.getInputStream().bufferedReader();while(reader.readLine().isNotEmpty()){}
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nsecure".toByteArray())
            }}
            Harness().use { route->
                val raw=route.socket(origin.localPort)
                (context.socketFactory.createSocket(raw,"localhost",origin.localPort,true) as SSLSocket).use { browser->
                    browser.sslParameters=browser.sslParameters.apply{endpointIdentificationAlgorithm="HTTPS"};browser.startHandshake()
                    assertEquals(keys.getCertificate("fixture"),browser.session.peerCertificates.single())
                    browser.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    assertTrue(browser.getInputStream().readAllBytes().toString(Charsets.UTF_8).endsWith("secure"))
                }
            };served.get(10,TimeUnit.SECONDS)
        }
    }
    @Test fun statusBeforeClientPlayerCreationDoesNotAccessThePlayer() {
        val connection=net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.CLIENTBOUND)
        val context=java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,arrayOf(net.neoforged.neoforge.network.handling.IPayloadContext::class.java)){_,method,_->
            when(method.name) {
                "flow"->net.minecraft.network.protocol.PacketFlow.CLIENTBOUND
                "connection"->connection
                "player"->throw AssertionError("The player is not available during the login transition")
                else->throw UnsupportedOperationException(method.name)
            }
        } as net.neoforged.neoforge.network.handling.IPayloadContext
        try {
            WebRoutingNetwork.handle(WebPacket(WebMessage(WebMessage.STATUS,host="youtube.com",credit=1)),context)
            assertTrue(BrowserWebProxy.client.enabled);assertTrue(BrowserWebProxy.client.domains.allows("www.youtube.com"))
        }finally{BrowserWebProxy.client.detach()}
    }
    @Test fun configuredUpstreamIsUsedAndFailureClosesItsSocket() {
        for(success in listOf(true,false)) {
            ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { proxy->
                val served=CompletableFuture.supplyAsync {
                    proxy.accept().use { socket->
                        socket.soTimeout=5000
                        val reader=socket.getInputStream().bufferedReader();val request=reader.readLine()
                        while(reader.readLine().isNotEmpty()){}
                        val response=if(success)"HTTP/1.1 200 Connection established\r\n\r\nabc" else "HTTP/1.1 403 Forbidden\r\n\r\n"
                        socket.getOutputStream().write(response.toByteArray());socket.getOutputStream().flush()
                        assertEquals(-1,socket.getInputStream().read());request
                    }
                }
                val address=WebDestination.Address("www.youtube.com",443,listOf(InetAddress.getByName("8.8.8.8")))
                val upstream="http://127.0.0.1:${proxy.localPort}"
                if(success)WebDialer.connect(address,upstream).use{assertEquals("abc",it.getInputStream().readNBytes(3).toString(Charsets.UTF_8))}
                else assertThrows(IllegalArgumentException::class.java){WebDialer.connect(address,upstream)}
                assertEquals("CONNECT www.youtube.com:443 HTTP/1.1",served.get(10,TimeUnit.SECONDS))
            }
        }
    }
    @Test fun absentServerRouteFailsInsteadOfConnectingDirectly() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { origin->
            origin.soTimeout=200
            BrowserWebClient().use { client->
                val proxy=Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",client.start()))
                Socket(proxy).use { browser->assertThrows(IOException::class.java){browser.connect(InetSocketAddress("127.0.0.1",origin.localPort),2000)} }
                assertThrows(SocketTimeoutException::class.java){origin.accept()}
            }
        }
    }
    @Test fun receiveWindowCannotBeOverflowedEvenWithTinyFragmentsOrInvalidCredits() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { listener->
            Socket("127.0.0.1",listener.localPort).use { remote->
                listener.accept().use { local->
                    val pipe=WebPipe(1,local,{},{});repeat(300){pipe.receive(WebMessage(WebMessage.DATA,1,bytes=byteArrayOf(1)))}
                    assertFalse(pipe.closed)
                    var left=WebMessage.WINDOW-300
                    while(left>0){val n=minOf(left,WebMessage.CHUNK);pipe.receive(WebMessage(WebMessage.DATA,1,bytes=ByteArray(n)));left-=n}
                    assertFalse(pipe.closed);pipe.receive(WebMessage(WebMessage.DATA,1,bytes=byteArrayOf(2)));assertTrue(pipe.closed)
                }
            }
        }
    }
    @Test fun chromiumHasOnlyLoopbackProxyAndNoDirectDnsOrUdpFallback() {
        try {
            val args=BrowserWebProxy.arguments(arrayOf("--proxy-server=direct://","--proxy-bypass-list=*","--disable-web-security","--ignore-certificate-errors"))
            assertEquals(1,args.count{it.startsWith("--proxy-server=")});assertTrue(args.any{it.startsWith("--proxy-server=socks5://127.0.0.1:")})
            assertFalse("--disable-web-security" in args);assertFalse("--ignore-certificate-errors" in args)
            assertFalse(args.any{it.contains("direct://")});assertTrue("--proxy-bypass-list=<-loopback>" in args)
            assertTrue(args.any{it.startsWith("--host-resolver-rules=MAP * ~NOTFOUND")});assertTrue("--disable-quic" in args)
            assertTrue("--force-webrtc-ip-handling-policy=disable_non_proxied_udp" in args)
        }finally{BrowserWebProxy.client.close()}
    }
}
