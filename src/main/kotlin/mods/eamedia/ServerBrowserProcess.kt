package mods.eamedia

import mods.eamedia.server.BrowserWorker
import net.neoforged.fml.ModList
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** All extraction, process startup, socket I/O and shutdown happen off the server tick. */
internal class ServerBrowserProcess(private val profile: Path,private val url: String,private val resume: String): AutoCloseable {
    data class Message(val type: Int,val bytes: ByteArray)
    val messages=ArrayBlockingQueue<Message>(16)
    private val outgoing=ArrayBlockingQueue<ByteArray>(256)
    @Volatile private var closed=false
    @Volatile private var process: Process?=null
    @Volatile private var socket: Socket?=null
    @Volatile private var listener: java.net.ServerSocket?=null
    @Volatile var problem: String?=null;private set
    val processId get()=process?.pid()
    fun start() {
        if(closed)return
        try {
            val runtime=ServerBrowserRuntime.prepare()
            if(closed)return
            Files.createDirectories(profile)
            // Minecraft enables preferIPv6Addresses; a standalone JVM need not. Use the
            // same explicit loopback family in both processes, regardless of JVM options.
            java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { listener->
                this.listener=listener
                if(closed)return
                listener.soTimeout=45_000
                val token=UUID.randomUUID().toString()+UUID.randomUUID().toString()
                val javaExecutable=Path.of(System.getProperty("java.home"),"bin",if(ServerBrowserRuntime.windows)"java.exe" else "java")
                val args=mutableListOf(javaExecutable.toString(),"-Xmx256m","-XX:ActiveProcessorCount=2","-Djava.awt.headless=true")
                if(java.lang.Boolean.getBoolean("eamedia.browserNoSandbox"))args+="-Deamedia.browserNoSandbox=true"
                args+=listOf("-cp",runtime.worker.toString()+File.pathSeparator+runtime.bridge,BrowserWorker::class.java.name,listener.localPort.toString(),runtime.native.toString(),profile.toAbsolutePath().toString())
                val builder=ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.appendTo(profile.resolve("worker.log").toFile())).redirectErrorStream(true)
                runtime.display?.let { builder.environment()["DISPLAY"]=it }
                // Chromium still runs its audio graph, but the host server needs no speaker.
                runtime.alsa?.let { builder.environment()["ALSA_CONFIG_PATH"]=it.toString() }
                val child=builder.start();process=child
                if(closed){child.destroyForcibly();return}
                DataOutputStream(child.outputStream).also {
                    it.writeUTF(token);it.writeUTF(url);it.writeUTF(resume);it.writeUTF(runtime.capture);it.flush()
                }
                val connected=listener.accept();socket=connected;connected.soTimeout=45_000;connected.tcpNoDelay=true
                val input=DataInputStream(BufferedInputStream(connected.getInputStream()))
                if(input.readUTF()!=token)throw IOException("Browser worker authentication failed")
                val writer=Thread({
                    try {
                        val output=BufferedOutputStream(connected.getOutputStream())
                        while(!closed){val bytes=outgoing.poll(1,TimeUnit.SECONDS) ?: continue;output.write(bytes);output.flush()}
                    }catch(e: Exception){if(!closed){problem="FAILED";close()}}
                },"EA Media browser controls").apply { isDaemon=true;start() }
                try {
                    while(!closed) {
                        val type=input.readUnsignedByte();val count=input.readInt()
                        val maximum=when(type){BrowserWorker.VIDEO->TelevisionStream.MAX_FRAME;BrowserWorker.AUDIO->8192;BrowserWorker.STATE->1024;BrowserWorker.ADDRESS->2048;BrowserWorker.ERROR->1024;BrowserWorker.READY->0;else->throw IOException("Invalid browser event")}
                        if(count !in 0..maximum)throw IOException("Oversized browser event")
                        val bytes=ByteArray(count);input.readFully(bytes)
                        val message=Message(type,bytes)
                        while(!messages.offer(message))messages.poll()
                    }
                } finally { writer.interrupt() }
            }
        }catch(e: Exception) {
            if(!closed) {
                problem=when(e){is MissingBrowserRuntime->"RUNTIME_MISSING";is MissingBrowserDisplay->"DISPLAY_MISSING";else->"FAILED"}
                mods.eln.Eln.LOGGER.error("EA Media server browser failed; see {}",profile.resolve("worker.log"),e)
            }
        }catch(e: LinkageError) {
            if(!closed){problem="FAILED";mods.eln.Eln.LOGGER.error("EA Media server browser library is unavailable",e)}
        }finally { close() }
    }
    fun command(type: Int,write: (DataOutputStream)->Unit={}) {
        if(closed)return
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use { it.writeByte(type);write(it) }
        // Mouse moves may be dropped under pressure; memory and the server tick never block.
        outgoing.offer(bytes.toByteArray())
    }
    fun input(value: String) {
        val d=com.google.gson.JsonParser.parseString(value).asJsonObject
        fun int(name: String)=if(d.has(name))d[name].asInt else 0
        command(BrowserWorker.INPUT) {
            it.writeUTF(d["type"].asString);it.writeInt(int("x"));it.writeInt(int("y"));it.writeInt(if(d.has("button"))int("button") else int("key"))
            it.writeInt(int("scan"));it.writeInt(int("mods"));it.writeDouble(if(d.has("delta"))d["delta"].asDouble else 0.0)
            it.writeUTF(if(d.has("text"))d["text"].asString else "")
        }
    }
    override fun close() {
        closed=true
        try { listener?.close() }catch(_: IOException){}
        try { socket?.close() }catch(_: IOException){}
        process?.let { child->
            // No wait/join on the tick thread. A daemon reaps only this worker's descendants.
            Thread({
                val descendants=child.toHandle().descendants().toList()
                child.destroy();descendants.forEach { it.destroy() }
                if(!child.waitFor(2,TimeUnit.SECONDS))child.destroyForcibly()
                descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            },"EA Media browser cleanup").apply { isDaemon=true;start() }
        }
        process=null;socket=null
    }
}

internal class MissingBrowserRuntime: IOException("The full EA Media jar is required on the server for Chromium")
internal class MissingBrowserDisplay: IOException("Install Xvfb on the server, or configure DISPLAY")

internal object ServerBrowserRuntime {
    val windows get()=System.getProperty("os.name").startsWith("Windows",true)
    data class Ready(val native: Path,val bridge: Path,val worker: Path,val capture: String,val display: String?,val alsa: Path?)
    @Volatile private var ready: Ready?=null
    @Volatile private var displayProcess: Process?=null
    init { Runtime.getRuntime().addShutdownHook(Thread({displayProcess?.destroy()},"EA Media display shutdown")) }
    @Synchronized fun prepare(): Ready {
        ready?.let { return it }
        if(BundledChromium::class.java.getResource("/assets/eamedia/chromium/manifest.json")==null)throw MissingBrowserRuntime()
        val base=Path.of("eamedia-server").toAbsolutePath();Files.createDirectories(base)
        val os=System.getProperty("os.name").lowercase();val arch=System.getProperty("os.arch").lowercase()
        val platform=when {
            os.contains("linux") && arch in listOf("amd64","x86_64")->"linux_amd64"
            windows && arch in listOf("amd64","x86_64")->"windows_amd64"
            os.contains("mac") && arch in listOf("aarch64","arm64")->"macos_arm64"
            os.contains("mac")->"macos_amd64"
            else->throw IOException("Unsupported server browser platform")
        }
        val native=BundledChromium(base.resolve("chromium"),serverTar=true).install(platform)
        val bridge=base.resolve("browser-bridge.jar")
        val mcef=ModList.get().getModFileById("mcef")?.file ?: throw IOException("Bundled browser bridge missing")
        val cef=mcef.findResource("org/cef")
        JarOutputStream(Files.newOutputStream(bridge)).use { jar->
            Files.walk(cef).use { files->files.filter { Files.isRegularFile(it) }.forEach { path->
                jar.putNextEntry(JarEntry("org/cef/"+cef.relativize(path).toString().replace('\\','/')));Files.copy(path,jar);jar.closeEntry()
            } }
        }
        val worker=base.resolve("browser-worker.jar")
        JarOutputStream(Files.newOutputStream(worker)).use { jar->
            jar.putNextEntry(JarEntry("org/lwjgl/glfw/GLFW.class"));jar.write(WorkerInputConstants.bytecode());jar.closeEntry()
            val names=mutableListOf<String>()
            fun collect(type: Class<*>) { names+=type.name.replace('.','/');type.declaredClasses.forEach(::collect) }
            collect(BrowserWorker::class.java)
            names.toList().forEach { name->for(i in 1..32)names+="$name\$$i" }
            for(name in names) {
                val stream=BrowserWorker::class.java.getResourceAsStream("/$name.class") ?: continue
                jar.putNextEntry(JarEntry("$name.class"));stream.use { it.copyTo(jar) };jar.closeEntry()
            }
        }
        val capture=checkNotNull(BundledChromium::class.java.getResourceAsStream("/assets/eln/browser/mirror-capture.js")).bufferedReader().use { it.readText() }
        var display=System.getenv("DISPLAY")?.takeIf { it.isNotBlank() };var alsa: Path?=null
        if(platform=="linux_amd64") {
            if(display==null) {
                val child=try { ProcessBuilder("Xvfb","-displayfd","1","-screen","0","1024x768x24","-nolisten","tcp","-noreset").redirectError(base.resolve("display.log").toFile()).start() }
                    catch(_: IOException){throw MissingBrowserDisplay()}
                displayProcess=child
                val reader=java.util.concurrent.CompletableFuture.supplyAsync { child.inputStream.bufferedReader().readLine() }
                val number=try { reader.get(10,TimeUnit.SECONDS) }catch(e: Exception){child.destroyForcibly();throw MissingBrowserDisplay()}
                if(number==null || !number.matches(Regex("[0-9]{1,5}"))){child.destroyForcibly();throw MissingBrowserDisplay()}
                display=":$number"
            }
            alsa=base.resolve("audio.conf");Files.writeString(alsa,"pcm.!default { type null }\n")
        }
        return Ready(native,bridge,worker,capture,display,alsa).also { ready=it }
    }
    @Synchronized fun close(){displayProcess?.destroy();displayProcess=null;ready=null}
}
