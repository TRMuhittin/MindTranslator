package translator;

import arc.func.Cons;
import arc.func.Cons2;
import arc.func.Prov;
import arc.net.Server.ServerConnectFilter;
import arc.struct.IntMap;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Nullable;
import mindustry.gen.SendChatMessageCallPacket;
import mindustry.gen.SendMessageCallPacket2;
import mindustry.net.Host;
import mindustry.net.Net;
import mindustry.net.Net.NetProvider;
import mindustry.net.NetConnection;
import mindustry.net.Packet;
import mindustry.net.Streamable.StreamBuilder;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Delegating {@link Net} proxy installed over Vars.net while on a client.
 * All state and behaviour is forwarded to the original instance; only exact
 * vanilla chat packets (SendChatMessageCallPacket / SendMessageCallPacket2)
 * are intercepted. Subclasses registered by other mods (e.g. Mindustry Tool)
 * pass through untouched so both mods can hook the same traffic.
 *
 * Reflection compatibility: external tools (e.g. Mindustry Tool) read vanilla
 * {@link Net} fields via {@code getDeclaredField} on the runtime class of
 * Vars.net. This class therefore mirrors every field of the vanilla
 * {@link Net} class; values are copied from the original instance/class at
 * install time and kept in sync where state changes.
 */
public class TranslatingNet extends Net{

    private final Net delegate;

    private static boolean staticsCopied;

    private static Seq<Prov<? extends Packet>> packetProvs;
    private static Seq<Class<? extends Packet>> packetClasses;
    private static ObjectIntMap<Class<?>> packetToId;
    private static int packetIdAssetStream;
    private static int packetIdWorldStream;

    private boolean server;
    private boolean active;
    private boolean clientLoaded;
    private StreamBuilder currentStream;
    private Seq<Packet> packetQueue;
    private ObjectMap<Class<?>, Cons> clientListeners;
    private ObjectMap<Class<?>, Cons2<NetConnection, Object>> serverListeners;
    private IntMap<StreamBuilder> streams;
    private ExecutorService pingExecutor;
    private NetProvider provider;

    public TranslatingNet(Net delegate){
        super(providerOf(delegate));
        this.delegate = delegate;
        copyMirrors(delegate);
    }

    @Override
    public void handleException(Throwable e){
        delegate.handleException(e);
    }

    @Override
    public void showError(Throwable e){
        delegate.showError(e);
    }

    @Override
    public void setClientLoaded(boolean loaded){
        delegate.setClientLoaded(loaded);
        syncState();
    }

    @Override
    public void setClientConnected(){
        delegate.setClientConnected();
        syncState();
    }

    @Override
    public void connect(String ip, int port, Runnable success){
        delegate.connect(ip, port, success);
        syncState();
    }

    @Override
    public void host(int port) throws java.io.IOException{
        delegate.host(port);
        syncState();
    }

    @Override
    public void closeServer(){
        delegate.closeServer();
        syncState();
    }

    @Override
    public void reset(){
        delegate.reset();
        syncState();
    }

    @Override
    public void disconnect(){
        delegate.disconnect();
        syncState();
    }

    @Override
    public void discoverServers(Cons<Host> cons, Runnable done){
        delegate.discoverServers(cons, done);
    }

    @Override
    public Iterable<NetConnection> getConnections(){
        return delegate.getConnections();
    }

    @Override
    public void send(Object object, boolean reliable){
        if(object.getClass() == SendChatMessageCallPacket.class){
            ClientTranslateHandler.onSend((SendChatMessageCallPacket)object, reliable);
        }else{
            delegate.send(object, reliable);
        }
    }

    @Override
    public void send(Object object, Iterable<NetConnection> connections, boolean reliable){
        delegate.send(object, connections, reliable);
    }

    @Override
    public void sendExcept(NetConnection except, Object object, boolean reliable){
        delegate.sendExcept(except, object, reliable);
    }

    @Override
    public @Nullable StreamBuilder getCurrentStream(){
        return delegate.getCurrentStream();
    }

    @Override
    public <T> void handleClient(Class<T> type, Cons<T> listener){
        delegate.handleClient(type, listener);
    }

    @Override
    public <T> void handleServer(Class<T> type, Cons2<NetConnection, T> listener){
        delegate.handleServer(type, listener);
    }

    @Override
    public void handleClientReceived(Packet object){
        if(object.getClass() == SendMessageCallPacket2.class){
            if(object.allow(false)){
                object.handled();
                ClientTranslateHandler.onReceive((SendMessageCallPacket2)object);
            }
            return;
        }
        delegate.handleClientReceived(object);
    }

    @Override
    public void handleServerReceived(NetConnection connection, Packet object){
        delegate.handleServerReceived(connection, object);
    }

    @Override
    public void setConnectFilter(@Nullable ServerConnectFilter filter){
        delegate.setConnectFilter(filter);
    }

    @Override
    public @Nullable ServerConnectFilter getConnectFilter(){
        return delegate.getConnectFilter();
    }

    @Override
    public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> failed){
        delegate.pingHost(address, port, valid, failed);
    }

    @Override
    public boolean active(){
        return delegate.active();
    }

    @Override
    public boolean server(){
        return delegate.server();
    }

    @Override
    public boolean client(){
        return delegate.client();
    }

    @Override
    public void dispose(){
        delegate.dispose();
    }

    //------------------------------------------------------------------------
    // reflection mirrors
    //------------------------------------------------------------------------

    private void copyMirrors(Net delegate){
        try{
            if(!staticsCopied){
                packetProvs = staticValue("packetProvs");
                packetClasses = staticValue("packetClasses");
                packetToId = staticValue("packetToId");
                Integer asset = staticValue("packetIdAssetStream");
                Integer world = staticValue("packetIdWorldStream");
                if(asset != null){
                    packetIdAssetStream = asset;
                }
                if(world != null){
                    packetIdWorldStream = world;
                }
                staticsCopied = true;
            }
            server = valueOf(delegate, "server");
            active = valueOf(delegate, "active");
            clientLoaded = valueOf(delegate, "clientLoaded");
            currentStream = valueOf(delegate, "currentStream");
            packetQueue = valueOf(delegate, "packetQueue");
            clientListeners = valueOf(delegate, "clientListeners");
            serverListeners = valueOf(delegate, "serverListeners");
            streams = valueOf(delegate, "streams");
            pingExecutor = valueOf(delegate, "pingExecutor");
            provider = valueOf(delegate, "provider");
        }catch(Exception e){
            arc.util.Log.info("[Translator] Net field mirror copy failed.", e);
        }
    }

    /** Re-reads the mutable boolean mirrors after a state change on the delegate. */
    private void syncState(){
        try{
            server = valueOf(delegate, "server");
            active = valueOf(delegate, "active");
            clientLoaded = valueOf(delegate, "clientLoaded");
        }catch(Exception e){
            // mirrors stay at their last good values
        }
    }

    /** The original instance's provider, used as this class's super() provider so field reads up the class chain still see it. */
    private static NetProvider providerOf(Net delegate){
        return valueOf(delegate, "provider");
    }

    @SuppressWarnings("unchecked")
    private static <T> T staticValue(String name){
        try{
            Field f = Net.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T)f.get(null);
        }catch(Exception e){
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T valueOf(Net net, String name){
        try{
            Field f = net.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (T)f.get(net);
        }catch(Exception e){
            return null;
        }
    }
}