package mods.eamedia.web

import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Loopback-only SOCKS5 endpoint for the bundled CEF engine. No direct fallback. */
class BrowserWebClient: AutoCloseable {
    private class Stream(val socket: Socket) {
        val ready=CompletableFuture<Unit>();@Volatile var pipe: WebPipe?=null
    }
    private val streams=ConcurrentHashMap<Int,Stream>();private val next=AtomicInteger()
    private val slots=Semaphore(64);private val throttle=WebThrottle()
    @Volatile private var channel: Any?=null
    @Volatile private var sender: ((WebMessage)->Unit)?=null
    @Volatile var enabled=false;private set
    @Volatile var domains=WebDomains(emptyList());private set
    @Volatile private var listener: ServerSocket?=null
    val connected get()=channel!=null
    val opened=AtomicLong();val downloaded=AtomicLong();val uploaded=AtomicLong()
    @Synchronized fun start(): Int {
        listener?.let{return it.localPort}
        val socket=ServerSocket(0,32,InetAddress.getByName("127.0.0.1"));listener=socket
        WebIo.executor.execute {
            while(!socket.isClosed) {
                try {
                    val local=socket.accept();local.soTimeout=10000;local.tcpNoDelay=true
                    if(!slots.tryAcquire()){local.close();continue}
                    WebIo.executor.execute{handshake(local)}
                }catch(_: IOException){if(!socket.isClosed)close()}
            }
        }
        return socket.localPort
    }
    fun attach(key: Any,send: (WebMessage)->Unit,allowed: Boolean,approved: WebDomains) {
        if(channel!==key){detach();channel=key;sender=send}
        if(!allowed)closeStreams()
        domains=approved;enabled=allowed
    }
    fun receive(key: Any,message: WebMessage) {
        if(key!==channel)return
        val stream=streams[message.id] ?: return
        when(message.kind) {
            WebMessage.READY->stream.ready.complete(Unit)
            WebMessage.DATA->{downloaded.addAndGet(message.bytes.size.toLong());stream.pipe?.receive(message)}
            WebMessage.CREDIT,WebMessage.END->stream.pipe?.receive(message)
            WebMessage.CLOSE->{stream.ready.completeExceptionally(IOException("Server web route unavailable"));stream.pipe?.close(false);streams.remove(message.id,stream)}
        }
    }
    private fun handshake(socket: Socket) {
        var id=0;var stream: Stream?=null
        val released=AtomicBoolean();fun release(){if(released.compareAndSet(false,true))slots.release()}
        try {
            val input=DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
            require(input.readUnsignedByte()==5)
            val methods=ByteArray(input.readUnsignedByte());input.readFully(methods)
            if(0.toByte() !in methods){output.write(byteArrayOf(5,0xff.toByte()));throw IOException("SOCKS authentication unavailable")}
            output.write(byteArrayOf(5,0))
            require(input.readUnsignedByte()==5 && input.readUnsignedByte()==1 && input.readUnsignedByte()==0)
            val host=when(input.readUnsignedByte()) {
                1->InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
                4->InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
                3->ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
                else->throw IOException("Unsupported SOCKS address")
            }
            val port=input.readUnsignedShort();val key=channel;val send=sender
            if(!enabled || key==null || send==null)throw IOException("No server web route")
            id=next.incrementAndGet();require(id>0)
            val active=Stream(socket);stream=active
            val pipe=WebPipe(id,socket,{message->
                if(channel!==key || !enabled)throw IOException("Server changed")
                if(message.kind==WebMessage.DATA)uploaded.addAndGet(message.bytes.size.toLong())
                send(message)
            },{streams.remove(id,active);release()},throttle::acquire)
            active.pipe=pipe;streams[id]=active
            send(WebMessage(WebMessage.OPEN,id,host,port));active.ready.get(12,TimeUnit.SECONDS)
            if(channel!==key || !enabled)throw IOException("Server changed")
            output.write(byteArrayOf(5,0,0,1,0,0,0,0,0,0));output.flush();opened.incrementAndGet();pipe.start()
            return
        }catch(_: Exception) {
            try {socket.getOutputStream().write(byteArrayOf(5,2,0,1,0,0,0,0,0,0))}catch(_: IOException){}
            if(id>0)stream?.pipe?.close()
        }
        try{socket.close()}catch(_: IOException){};release()
    }
    private fun closeStreams(){streams.values.toList().forEach { it.ready.completeExceptionally(IOException("Server disconnected"));it.pipe?.close() };streams.clear()}
    fun detach(){closeStreams();enabled=false;domains=WebDomains(emptyList());sender=null;channel=null}
    override fun close(){detach();listener?.close();listener=null}
}

/** Called before MCEF startup as well as getInstance, on its initialization thread. */
object BrowserWebProxy {
    val client=BrowserWebClient()
    @JvmStatic fun arguments(original: Array<String>): Array<String> {
        val port=client.start()
        val keys=listOf("--proxy-server","--proxy-bypass-list","--host-resolver-rules","--force-webrtc-ip-handling-policy")
        return (original.filter{arg->keys.none{arg.startsWith(it)}}+listOf(
            "--proxy-server=socks5://127.0.0.1:$port","--proxy-bypass-list=<-loopback>",
            "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1","--disable-quic",
            "--force-webrtc-ip-handling-policy=disable_non_proxied_udp"
        )).distinct().toTypedArray()
    }
}
