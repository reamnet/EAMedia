package mods.eamedia.server;

import org.cef.*;
import org.cef.browser.*;
import org.cef.callback.*;
import org.cef.event.*;
import org.cef.handler.*;
import org.cef.network.CefRequest;

import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A separate JVM, with no Minecraft, OpenGL, Kotlin or player-browser dependency.
 * Its only IPC peer is the parent server over an authenticated loopback socket.
 * CEF's pinned native audio callback leaks memory and loses a channel: use the
 * document's bounded PCM bridge, never that native callback. */
public final class BrowserWorker {
    public static final int VIDEO=0, AUDIO=1, STATE=2, ADDRESS=3, ERROR=4, READY=5;
    public static final int INPUT=1, RELEASE=2, BACK=3, FORWARD=4, RELOAD=5, WATCHING=6, CLOSE=7;
    private static final int WIDTH=960, HEIGHT=540, MAX_FRAME=131072;
    private static final AudioMixer sound=new AudioMixer(System::nanoTime);
    private static final ArrayBlockingQueue<Event> events=new ArrayBlockingQueue<>(32);
    private static final AtomicReference<byte[]> picture=new AtomicReference<>();
    private static final ConcurrentLinkedQueue<Runnable> commands=new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean encoding=new AtomicBoolean();
    private static final ExecutorService encoder=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"TV encoder");t.setDaemon(true);return t;});
    private static volatile boolean running=true, watching=true;
    private static volatile WorkerBrowser browser;
    private static long nextPaint;
    private static final class Event {
        final int type; final byte[] bytes;
        Event(int type, byte[] bytes){this.type=type;this.bytes=bytes;}
    }
    private static void event(int type,String text){events.offer(new Event(type,text.getBytes(StandardCharsets.UTF_8)));}
    private static boolean web(String value) {
        try { URI u=new URI(value);return value.length()<=2048 && u.getHost()!=null && u.getUserInfo()==null && ("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme())); }
        catch(Exception e){return false;}
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Worker needs port, native directory and profile directory");
        // The token travels through stdin, never the process command line or log.
        DataInputStream parent=new DataInputStream(System.in);
        String token=parent.readUTF(), url=parent.readUTF(), resume=parent.readUTF(), capture=parent.readUTF();
        if(!web(url) || capture.length()>24000 || resume.length()>1024)throw new IOException("Invalid initial browser request");
        Socket socket=new Socket(InetAddress.getByName("127.0.0.1"),Integer.parseInt(args[0]));socket.setTcpNoDelay(true);
        DataOutputStream output=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        output.writeUTF(token);output.flush();
        Thread writer=new Thread(()->{
            try {
                long nextAudio=0;
                while(running) {
                    long now=System.nanoTime();byte[] pcm=null;
                    if(now>=nextAudio){nextAudio=Math.max(nextAudio+20_000_000L,now-40_000_000L);pcm=sound.read(480);}
                    Event e=events.poll();byte[] jpeg=picture.getAndSet(null);
                    if(e!=null)write(output,e.type,e.bytes);
                    if(pcm!=null)write(output,AUDIO,pcm);
                    if(jpeg!=null)write(output,VIDEO,jpeg);
                    if(e==null && pcm==null && jpeg==null)Thread.sleep(2);
                }
            } catch(Exception e){running=false;}
        },"TV IPC output");writer.setDaemon(true);writer.start();
        Thread reader=new Thread(()->{
            try {
                DataInputStream input=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                while(running) {
                    int type=input.readUnsignedByte();
                    if(type==CLOSE){running=false;break;}
                    if(type==WATCHING){watching=input.readBoolean();if(!watching){sound.clear();picture.set(null);}continue;}
                    if(type==INPUT) {
                        String name=input.readUTF();int x=input.readInt(),y=input.readInt(),key=input.readInt(),scan=input.readInt(),mods=input.readInt();double delta=input.readDouble();String text=input.readUTF();
                        if(text.length()>256 || commands.size()>=240)continue;
                        commands.offer(()->{if(browser!=null)browser.input(name,x,y,key,scan,mods,delta,text);});
                    } else if(type==RELEASE || type==BACK || type==FORWARD || type==RELOAD)commands.offer(()->{
                        WorkerBrowser b=browser;if(b==null)return;
                        if(type==RELEASE)b.releaseInputs();else if(type==BACK)b.goBack();else if(type==FORWARD)b.goForward();else b.reload();
                    });
                    else throw new IOException("Unknown worker command");
                }
            }catch(Exception e){running=false;}
        },"TV IPC input");reader.setDaemon(true);reader.start();

        System.setProperty("jcef.path",Path.of(args[1]).toAbsolutePath().toString());
        ArrayList<String> switches=new ArrayList<>(Arrays.asList("--disable-gpu","--disable-dev-shm-usage","--autoplay-policy=no-user-gesture-required","--disable-background-timer-throttling","--disable-renderer-backgrounding"));
        // Some container hosts cannot create Chromium's namespace sandbox. Explicit opt-in only.
        if(Boolean.getBoolean("eamedia.browserNoSandbox"))switches.add("--no-sandbox");
        if(!CefApp.startup(switches.toArray(new String[0])))throw new IOException("Browser native startup failed");
        CefSettings settings=new CefSettings();settings.windowless_rendering_enabled=true;
        settings.cache_path=Path.of(args[2]).toAbsolutePath().toString();settings.log_file=Path.of(args[2]).resolve("chromium.log").toAbsolutePath().toString();
        if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
            settings.browser_subprocess_path=Path.of(args[1],"jcef_helper").toAbsolutePath().toString();settings.resources_dir_path=args[1];settings.locales_dir_path=Path.of(args[1],"locales").toString();
        }
        CefApp app=CefApp.getInstance(switches.toArray(new String[0]),settings);
        CefClient client=app.createClient();client.removeAudioHandler();
        CefMessageRouter router=CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("eaTvCapture","eaTvCaptureCancel"),new CefMessageRouterHandlerAdapter(){
            public boolean onQuery(CefBrowser b,CefFrame frame,long id,String request,boolean persistent,CefQueryCallback callback){
                if(b!=browser || persistent || request.length()>16384){callback.failure(1,"Unavailable");return true;}
                callback.success("");
                if(request.startsWith("pcm:") && watching) {
                    try {byte[] data=Base64.getDecoder().decode(request.substring(4));if(data.length>=4 && data.length<=8192 && data.length%4==0)sound.offer(frame.getIdentifier(),data);}catch(IllegalArgumentException ignored){}
                } else if(request.startsWith("state:") && request.length()<=1030)event(STATE,request.substring(6));
                return true;
            }
        });client.addMessageRouter(router);
        client.addLoadHandler(new CefLoadHandlerAdapter(){
            private String currentResume=resume;private boolean firstDocumentEnded;
            private void install(CefFrame frame){frame.executeJavaScript("window.__eaTVResume="+currentResume+";\n"+capture,frame.getURL(),0);}
            public void onLoadStart(CefBrowser b,CefFrame frame,CefRequest.TransitionType transition){
                if(frame.isMain() && firstDocumentEnded)currentResume="{\"known\":false}";
                install(frame);
            }
            public void onLoadEnd(CefBrowser b,CefFrame frame,int status){install(frame);if(frame.isMain())firstDocumentEnded=true;}
        });
        client.addDisplayHandler(new CefDisplayHandlerAdapter(){
            public void onAddressChange(CefBrowser b,CefFrame frame,String value){if(frame.isMain() && web(value))event(ADDRESS,value);}
        });
        client.addRequestHandler(new CefRequestHandlerAdapter(){
            public boolean onBeforeBrowse(CefBrowser b,CefFrame frame,CefRequest request,boolean gesture,boolean redirect){return !web(request.getURL()) && !"about:blank".equals(request.getURL());}
        });
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter(){
            public boolean onBeforePopup(CefBrowser b,CefFrame frame,String value,String name){if(web(value))commands.offer(()->b.loadURL(value));return true;}
        });
        client.addContextMenuHandler(new CefContextMenuHandlerAdapter(){
            public void onBeforeContextMenu(CefBrowser b,CefFrame f,CefContextMenuParams p,CefMenuModel model){model.clear();}
        });
        client.addDialogHandler(new CefDialogHandler(){
            public boolean onFileDialog(CefBrowser b,FileDialogMode mode,String title,String path,Vector<String> filters,CefFileDialogCallback callback){callback.Cancel();return true;}
        });
        browser=new WorkerBrowser(client,url);browser.createImmediately();event(READY,"");long heartbeat=0;
        try {
            while(running) {
                for(int i=0;i<120;i++){Runnable command=commands.poll();if(command==null)break;command.run();}
                if(browser.lastInput>0 && System.nanoTime()-browser.lastInput>2_000_000_000L)browser.releaseInputs();
                if(System.nanoTime()>heartbeat){heartbeat=System.nanoTime()+5_000_000_000L;event(READY,"");}
                app.N_DoMessageLoopWork();Thread.sleep(8);
            }
        } finally {
            running=false;browser.setCloseAllowed();browser.close(true);client.removeMessageRouter(router);router.dispose();client.dispose();app.dispose();socket.close();encoder.shutdownNow();
        }
        System.exit(0);
    }
    private static void write(DataOutputStream output,int type,byte[] bytes)throws IOException{output.writeByte(type);output.writeInt(bytes.length);output.write(bytes);output.flush();}
    private static void paint(ByteBuffer input,int width,int height) {
        if(!watching || width!=WIDTH || height!=HEIGHT || System.nanoTime()<nextPaint || !encoding.compareAndSet(false,true))return;
        nextPaint=System.nanoTime()+1_000_000_000L/12;
        byte[] pixels=new byte[width*height*4];input.duplicate().position(0).get(pixels);
        encoder.execute(()->{
            try {
                BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);int[] rgb=new int[width*height];
                for(int i=0;i<rgb.length;i++){int p=i*4;rgb[i]=((pixels[p+2]&255)<<16)|((pixels[p+1]&255)<<8)|(pixels[p]&255);}
                image.setRGB(0,0,width,height,rgb,0,width);
                for(float quality:new float[]{.65f,.45f,.25f,.12f}){byte[] jpeg=jpeg(image,quality);if(jpeg.length<=MAX_FRAME){picture.set(jpeg);break;}}
            }catch(Exception e){event(ERROR,"Could not encode browser picture");}finally{encoding.set(false);}
        });
    }
    public static byte[] jpeg(BufferedImage image,float quality)throws IOException {
        ImageWriter writer=ImageIO.getImageWritersByFormatName("jpeg").next();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(MemoryCacheImageOutputStream output=new MemoryCacheImageOutputStream(bytes)){writer.setOutput(output);ImageWriteParam p=writer.getDefaultWriteParam();p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);p.setCompressionQuality(quality);writer.write(null,new IIOImage(image,null,null),p);}finally{writer.dispose();}
        return bytes.toByteArray();
    }
    /** Mix iframe audio at one fixed 24 kHz clock. Page callback floods cannot accelerate
     * playback or send unlimited PCM to the Minecraft process. Each source is bounded. */
    public static final class AudioMixer {
        private final java.util.function.LongSupplier clock;
        private final LinkedHashMap<Long,Input> sources=new LinkedHashMap<>();
        private static final class Input {
            final ArrayDeque<byte[]> queue=new ArrayDeque<>();int offset,buffered;long last,ready;
        }
        public AudioMixer(java.util.function.LongSupplier clock){this.clock=clock;}
        public synchronized void clear(){sources.clear();}
        public synchronized int sourceCount(){return sources.size();}
        public synchronized void offer(long id,byte[] bytes) {
            if(bytes.length<4 || bytes.length>8192 || bytes.length%4!=0)return;
            Input input=sources.get(id);long now=clock.getAsLong();
            if(input==null){if(sources.size()>=32)sources.remove(sources.keySet().iterator().next());input=new Input();sources.put(id,input);}
            if(input.buffered==0)input.ready=now+85_000_000L;
            input.last=now;
            while(input.buffered+bytes.length>23040 && !input.queue.isEmpty()){input.buffered-=input.queue.removeFirst().length-input.offset;input.offset=0;}
            input.queue.addLast(bytes);input.buffered+=bytes.length;
        }
        public synchronized byte[] read(int frames) {
            if(frames<1 || frames>2048)throw new IllegalArgumentException("Invalid audio packet size");
            long now=clock.getAsLong();sources.values().removeIf(s->now-s.last>2_000_000_000L);
            int[] mixed=new int[frames*2];boolean present=false;
            for(Input input:sources.values()) {
                if(now<input.ready)continue;
                for(int i=0;i<frames && !input.queue.isEmpty();i++) {
                    byte[] first=input.queue.getFirst();int p=input.offset;
                    mixed[i*2]+=(short)((first[p]&255)|(first[p+1]<<8));mixed[i*2+1]+=(short)((first[p+2]&255)|(first[p+3]<<8));
                    input.offset+=4;input.buffered-=4;present=true;
                    if(input.offset==first.length){input.queue.removeFirst();input.offset=0;}
                }
            }
            if(!present)return null;
            byte[] bytes=new byte[frames*4];
            for(int i=0;i<mixed.length;i++){int value=Math.max(-32768,Math.min(32767,mixed[i]));bytes[i*2]=(byte)value;bytes[i*2+1]=(byte)(value>>8);}
            return bytes;
        }
    }
    public static final class WorkerBrowser extends CefBrowserOsr {
        private final Set<Integer> keys=new HashSet<>(),buttons=new HashSet<>();private int mask;long lastInput;
        private byte[] popup;private Rectangle bounds;private boolean showing;
        WorkerBrowser(CefClient client,String url){super(client,url,false,null);}
        public Rectangle getViewRect(CefBrowser b){return new Rectangle(0,0,WIDTH,HEIGHT);}
        public void onPopupShow(CefBrowser b,boolean show){showing=show;if(!show)popup=null;}
        public void onPopupSize(CefBrowser b,Rectangle rectangle){bounds=new Rectangle(rectangle);}
        public synchronized void onPaint(CefBrowser b,boolean isPopup,Rectangle[] dirty,ByteBuffer buffer,int width,int height){
            if(isPopup){if(width>0 && height>0 && (long)width*height<=WIDTH*HEIGHT){popup=new byte[width*height*4];buffer.duplicate().position(0).get(popup);}return;}
            if(showing && popup!=null && bounds!=null && popup.length==(long)bounds.width*bounds.height*4 && width==WIDTH && height==HEIGHT && watching && System.nanoTime()>=nextPaint){
                byte[] raw=new byte[width*height*4];buffer.duplicate().position(0).get(raw);Rectangle clipped=bounds.intersection(new Rectangle(0,0,width,height));
                for(int y=clipped.y;y<clipped.y+clipped.height;y++)System.arraycopy(popup,((y-bounds.y)*bounds.width+clipped.x-bounds.x)*4,raw,(y*width+clipped.x)*4,clipped.width*4);
                paint(ByteBuffer.wrap(raw),width,height);
            }else paint(buffer,width,height);
        }
        void input(String type,int x,int y,int key,int scan,int mods,double delta,String text){
            lastInput=System.nanoTime();
            if(type.equals("move")){sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED,x,y,0,0,mask));return;}
            setFocus(true);
            if(type.equals("down") || type.equals("up")) {
                int button=key==1?2:key==2?1:0;int bit=button==0?CefMouseEvent.BUTTON1_MASK:button==1?CefMouseEvent.BUTTON2_MASK:CefMouseEvent.BUTTON3_MASK;
                boolean down=type.equals("down");if(down){mask|=bit;buttons.add(key);}else{mask&=~bit;buttons.remove(key);}
                sendMouseEvent(new CefMouseEvent(down?1:0,x,y,1,button,mask));
            } else if(type.equals("wheel"))sendMouseWheelEvent(new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL,x,y,delta<0?Math.floor(delta)*3:Math.ceil(delta)*3,mods));
            else if(type.equals("keyDown") || type.equals("keyUp")) {
                if((key==86 && (mods&10)!=0) || (key==260 && (mods&1)!=0))return;
                boolean down=type.equals("keyDown");if(down)keys.add(key);else keys.remove(key);
                CefKeyEvent e=new CefKeyEvent(down?CefKeyEvent.KEY_PRESS:CefKeyEvent.KEY_RELEASE,key,(char)key,mods);e.scancode=scan;sendKeyEvent(e);
            } else if(type.equals("char") || type.equals("paste")) {
                // Text is supplied by the controller, never the server machine's clipboard.
                if(type.equals("paste")) {
                    StringBuilder literal=new StringBuilder("\"");for(char c:text.toCharArray())literal.append(String.format("\\u%04x",(int)c));literal.append('"');
                    CefFrame f=getFocusedFrame();if(f==null)f=getMainFrame();if(f!=null)f.executeJavaScript("document.execCommand('insertText',false,"+literal+");",f.getURL(),0);
                }else for(char c:text.toCharArray())sendKeyEvent(new CefKeyEvent(CefKeyEvent.KEY_TYPE,c,c,mods));
            }
        }
        void releaseInputs(){for(int key:keys)sendKeyEvent(new CefKeyEvent(CefKeyEvent.KEY_RELEASE,key,(char)key,0));keys.clear();for(int b:new HashSet<>(buttons))input("up",0,0,b,0,0,0,"");buttons.clear();mask=0;lastInput=0;setFocus(false);}
    }
}
