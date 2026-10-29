package mods.eamedia.web

import java.io.IOException
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Per-player service; only an authenticated Minecraft payload handler creates one. */
internal class WebPeer(private val destination: WebDestination,private val send: (WebMessage)->Unit,private val dial: (WebDestination.Address)->Socket,private val denied: (String)->Unit={}): AutoCloseable {
    private class Stream {
        val cancelled=AtomicBoolean();val released=AtomicBoolean();@Volatile var pipe: WebPipe?=null
    }
    private val streams=ConcurrentHashMap<Int,Stream>()
    private val deniedDomains=ConcurrentHashMap.newKeySet<String>()
    private val opens=mods.eamedia.TelevisionByteBudget(8.0,32.0)
    private val input=mods.eamedia.TelevisionByteBudget(8_388_608.0,4_194_304.0)
    private val output=WebThrottle()
    @Volatile private var stopped=false
    val opened=AtomicLong();val downloaded=AtomicLong();val uploaded=AtomicLong()
    fun receive(message: WebMessage) {
        if(stopped)return
        if(message.kind==WebMessage.OPEN) {
            val allowed=synchronized(opens){opens.accept(1)}
            if(message.id<=0 || !allowed || streams.size>=64 || streams.containsKey(message.id) || !globalSlots.tryAcquire()){reject(message.id);return}
            val stream=Stream()
            if(streams.putIfAbsent(message.id,stream)!=null){globalSlots.release();reject(message.id);return}
            fun release(){if(stream.released.compareAndSet(false,true)){streams.remove(message.id,stream);globalSlots.release()}}
            try {
                connecting.execute {
                    var transferred=false
                    try {
                        if(stopped || stream.cancelled.get())return@execute
                        val target=destination.resolve(message.host,message.port)
                        val socket=dial(target)
                        if(stopped || stream.cancelled.get()){socket.close();return@execute}
                        val pipe=WebPipe(message.id,socket,{packet->
                            if(packet.kind==WebMessage.DATA){downloaded.addAndGet(packet.bytes.size.toLong())}
                            send(packet)
                        },::release,output::acquire)
                        stream.pipe=pipe
                        if(stopped || stream.cancelled.get()){pipe.close();return@execute}
                        transferred=true;opened.incrementAndGet();send(WebMessage(WebMessage.READY,message.id));pipe.start()
                    }catch(error: Exception){if(error is WebDomainDenied && deniedDomains.size<16 && deniedDomains.add(error.domain))denied(error.domain);stream.pipe?.close();reject(message.id)}finally{if(!transferred)release()}
                }
            }catch(_: RejectedExecutionException){release();reject(message.id)}
            return
        }
        val stream=streams[message.id] ?: return
        if(message.kind==WebMessage.CLOSE){stream.cancelled.set(true);stream.pipe?.close(false);return}
        if(message.kind==WebMessage.DATA) {
            if(!synchronized(input){input.accept(message.bytes.size)}){stream.cancelled.set(true);stream.pipe?.close();return}
            uploaded.addAndGet(message.bytes.size.toLong())
        }
        val pipe=stream.pipe
        if(pipe==null){stream.cancelled.set(true);reject(message.id)}else pipe.receive(message)
    }
    private fun reject(id: Int){try{send(WebMessage(WebMessage.CLOSE,id))}catch(_: Exception){}}
    override fun close(){stopped=true;streams.values.toList().forEach{it.cancelled.set(true);it.pipe?.close()}}
    companion object {
        private val globalSlots=Semaphore(256)
        private val connecting=ThreadPoolExecutor(8,8,0L,TimeUnit.SECONDS,ArrayBlockingQueue(256),{r->Thread(r,"EA Media web connect").apply{isDaemon=true}})
    }
}

/** Shared per player, not per socket. Backpressure waits only on I/O workers. */
internal class WebThrottle {
    private var balance=4_194_304.0;private var sampled=System.nanoTime()
    fun acquire(bytes: Int) {
        while(true) {
            val wait=synchronized(this) {
                val now=System.nanoTime();balance=(balance+(now-sampled).coerceAtLeast(0)*8_388_608.0/1e9).coerceAtMost(4_194_304.0);sampled=now
                if(balance>=bytes){balance-=bytes;0L}else ((bytes-balance)/8_388_608.0*1e9).toLong().coerceIn(100_000,10_000_000)
            }
            if(wait==0L)return
            java.util.concurrent.locks.LockSupport.parkNanos(wait)
            if(Thread.interrupted())throw IOException("Browser route interrupted")
        }
    }
}
