package mods.eamedia.web

import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Raw HTTP/HTTPS TCP forwarding; TLS, cookies and rendering stay in Chromium. */
data class WebMessage(val kind: Int,val id: Int=0,val host: String="",val port: Int=0,val bytes: ByteArray=byteArrayOf(),val credit: Int=0) {
    // Packet diagnostics must never print HTTP bodies, cookies or opaque TLS bytes.
    override fun toString()="WebMessage(kind=$kind,id=$id,host=$host,port=$port,bytes=${bytes.size},credit=$credit)"
    companion object {
        const val OPEN=0;const val READY=1;const val DATA=2;const val CREDIT=3;const val END=4;const val CLOSE=5;const val STATUS=6
        const val CHUNK=16384;const val WINDOW=65536
    }
}

internal object WebIo {
    val executor=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("EA Media web-",0).factory())
}

/** Each direction has only 64 KiB in flight. A credit is returned after socket write,
 * not after packet receipt, so a slow browser/website applies real backpressure. */
internal class WebPipe(val id: Int,private val socket: Socket,private val send: (WebMessage)->Unit,private val removed: ()->Unit,private val beforeSend: (Int)->Unit={}): AutoCloseable {
    private val ended=AtomicBoolean();private val started=AtomicBoolean()
    private val lock=ReentrantLock();private val changed=lock.newCondition();private var credit=WebMessage.WINDOW;private var receiveCredit=WebMessage.WINDOW
    private val buffer=ByteArray(WebMessage.WINDOW);private var head=0;private var buffered=0
    private val remoteEnded=AtomicBoolean();private var readEnded=false;private var writeEnded=false
    @Volatile private var lastActivity=System.nanoTime()
    val closed get()=ended.get()
    fun start() {
        if(!started.compareAndSet(false,true) || closed)return
        socket.soTimeout=1000
        WebIo.executor.execute {
            try {
                val input=socket.getInputStream()
                while(!closed) {
                    val allowed=lock.withLock {
                        while(credit==0 && !closed){changed.await(1,TimeUnit.SECONDS);idleCheck()}
                        minOf(credit,WebMessage.CHUNK)
                    }
                    if(closed)break
                    val data=ByteArray(allowed)
                    val count=try { input.read(data) }catch(_: SocketTimeoutException){idleCheck();continue}
                    if(count<0){send(WebMessage(WebMessage.END,id));lock.withLock{readEnded=true;finish()};break}
                    if(count==0)continue
                    lock.withLock{credit-=count}
                    beforeSend(count);lastActivity=System.nanoTime();send(WebMessage(WebMessage.DATA,id,bytes=if(count==data.size)data else data.copyOf(count)))
                }
            }catch(_: Exception){close()}
        }
        WebIo.executor.execute {
            try {
                val output=socket.getOutputStream()
                while(!closed) {
                    val bytes=lock.withLock {
                        while(buffered==0 && !remoteEnded.get() && !closed){changed.await(1,TimeUnit.SECONDS);idleCheck()}
                        if(buffered==0)null else {
                            val count=minOf(buffered,WebMessage.CHUNK);val data=ByteArray(count)
                            val first=minOf(count,buffer.size-head)
                            buffer.copyInto(data,0,head,head+first)
                            if(first<count)buffer.copyInto(data,first,0,count-first)
                            head=(head+count)%buffer.size;buffered-=count;data
                        }
                    }
                    if(closed)break
                    if(bytes==null){socket.shutdownOutput();lock.withLock{writeEnded=true;finish()};break}
                    output.write(bytes);output.flush();lastActivity=System.nanoTime()
                    lock.withLock{receiveCredit+=bytes.size}
                    send(WebMessage(WebMessage.CREDIT,id,credit=bytes.size))
                }
            }catch(_: Exception){close()}
        }
    }
    fun receive(message: WebMessage) {
        if(closed)return
        when(message.kind) {
            WebMessage.DATA->{
                lock.withLock {
                    if(remoteEnded.get() || message.bytes.isEmpty() || message.bytes.size>WebMessage.CHUNK || message.bytes.size>receiveCredit)close()
                    else {
                        receiveCredit-=message.bytes.size
                        val tail=(head+buffered)%buffer.size;val first=minOf(message.bytes.size,buffer.size-tail)
                        message.bytes.copyInto(buffer,tail,0,first)
                        if(first<message.bytes.size)message.bytes.copyInto(buffer,0,first)
                        buffered+=message.bytes.size;changed.signalAll()
                    }
                }
            }
            WebMessage.CREDIT->lock.withLock {
                if(message.credit !in 1..WebMessage.WINDOW || message.credit>WebMessage.WINDOW-credit)close()
                else {credit+=message.credit;lastActivity=System.nanoTime();changed.signalAll()}
            }
            WebMessage.END->if(remoteEnded.compareAndSet(false,true)){lock.withLock{changed.signalAll()}}else close()
            WebMessage.CLOSE->close(false)
            else->close()
        }
    }
    private fun idleCheck(){if(System.nanoTime()-lastActivity>90_000_000_000L)throw SocketTimeoutException("Idle browser route")}
    private fun finish(){if(readEnded && writeEnded)close(false)}
    override fun close()=close(true)
    fun close(notify: Boolean) {
        if(!ended.compareAndSet(false,true))return
        try {socket.close()}catch(_: IOException){}
        lock.withLock{buffered=0;changed.signalAll()};removed()
        if(notify)try {send(WebMessage(WebMessage.CLOSE,id))}catch(_: Exception){}
    }
}

internal class WebDomainDenied(val domain: String): IllegalArgumentException("Website is not approved by the server")

internal class WebPortDenied(val host: String,val port: Int): IllegalArgumentException("Website port is not approved by the server")

internal class WebDestination(private val domains: WebDomains=WebDomains(WebDomains.defaults),private val allowTestLoopback: Boolean=false,private val ports: Set<Int> = webPorts(),private val resolver: (String)->Array<InetAddress> = {InetAddress.getAllByName(it)}) {
    data class Address(val host: String,val port: Int,val addresses: List<InetAddress>)
    fun resolve(host: String,port: Int): Address {
        require(host.length in 1..253 && host.all{it.isLetterOrDigit() && it.code<128 || it in ".-:"}){"Invalid website address"}
        if(!domains.allows(host))throw WebDomainDenied(host)
        val addresses=resolver(host).toList();require(addresses.isNotEmpty())
        require(addresses.all { publicAddress(it) || allowTestLoopback && it.isLoopbackAddress }){"Website address is not public"}
        if(port !in ports && !(allowTestLoopback && port in 1..65535 && addresses.all{it.isLoopbackAddress}))throw WebPortDenied(host,port)
        return Address(host,port,addresses)
    }
    companion object {
        internal fun webPorts(value: String=System.getProperty("eamedia.webPorts","80,443,8080,8443")): Set<Int> {
            val ports=value.split(',').map { it.trim().toInt() }.toSet()
            require(ports.isNotEmpty() && ports.size<=16 && ports.all { it in 1..65535 }) { "Invalid TV web ports" }
            return ports
        }
        internal fun publicAddress(address: InetAddress): Boolean {
            if(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress)return false
            val b=address.address.map{it.toInt() and 255}
            if(b.size==4)return when {
                b[0]==0 || b[0]>=224 || b[0]==127 || b[0]==10->false
                b[0]==100 && b[1] in 64..127->false
                b[0]==169 && b[1]==254 || b[0]==172 && b[1] in 16..31 || b[0]==192 && b[1]==168->false
                b[0]==192 && b[1]==0 && b[2] in listOf(0,2) || b[0]==198 && b[1] in 18..19->false
                b[0]==198 && b[1]==51 && b[2]==100 || b[0]==203 && b[1]==0 && b[2]==113->false
                else->true
            }
            return b.size==16 && b[0] in 0x20..0x3f && !(b[0]==0x20 && b[1]==1 && b[2]==0x0d && b[3]==0xb8) && !(b[0]==0x20 && (b[1]==2 || b[1]==1 && b[2]==0 && b[3]==0))
        }
    }
}

/** Honor an explicitly configured server upstream proxy. Otherwise connect to a
 * validated numeric address, avoiding a second DNS lookup/rebinding at connect. */
internal object WebDialer {
    fun connect(destination: WebDestination.Address,upstream: String?=System.getenv("HTTPS_PROXY") ?: System.getenv("HTTP_PROXY"),testLoopback: Boolean=false): Socket {
        if(upstream.isNullOrBlank() || testLoopback && destination.addresses.all{it.isLoopbackAddress}) {
            var failure: IOException?=null
            for(address in destination.addresses) {
                val socket=Socket()
                try {socket.connect(InetSocketAddress(address,destination.port),7000);socket.tcpNoDelay=true;return socket}
                catch(e: IOException){failure=e;socket.close()}
            }
            throw failure ?: IOException("Website unavailable")
        }
        val proxy=URI(upstream);require(proxy.scheme in listOf("http","https") && proxy.host!=null)
        val socket=Socket();var connected: Socket=socket
        try {
            socket.connect(InetSocketAddress(proxy.host,if(proxy.port>0)proxy.port else if(proxy.scheme=="https")443 else 80),7000)
            if(proxy.scheme=="https") {
                connected=(SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket,proxy.host,if(proxy.port>0)proxy.port else 443,true)
                (connected as SSLSocket).apply{sslParameters=sslParameters.apply{endpointIdentificationAlgorithm="HTTPS"};startHandshake()}
            }
            connected.soTimeout=10000
            val host=if(destination.host.contains(':'))"[${destination.host}]" else destination.host
            val authority="$host:${destination.port}"
            val auth=proxy.userInfo?.let{"Proxy-Authorization: Basic "+java.util.Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8))+"\r\n"} ?: ""
            connected.getOutputStream().write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n${auth}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            val response=ByteArrayOutputStream();val input=connected.getInputStream();var ending=0
            while(response.size()<8192) {
                val value=input.read();if(value<0)throw IOException("Upstream proxy closed")
                response.write(value)
                ending=(ending shl 8) or value
                if(response.size()>=4 && ending==0x0d0a0d0a)break
            }
            require(response.size()<8192 && response.toString(StandardCharsets.ISO_8859_1).lineSequence().first().split(' ').getOrNull(1)=="200"){"Upstream proxy rejected website"}
            connected.tcpNoDelay=true;return connected
        }catch(e: Exception){connected.close();throw e}
    }
}
