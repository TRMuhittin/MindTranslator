package translator;

import arc.func.Cons;
import arc.func.Cons2;
import arc.net.Server.ServerConnectFilter;
import arc.util.Nullable;
import mindustry.gen.SendChatMessageCallPacket;
import mindustry.gen.SendMessageCallPacket2;
import mindustry.net.Host;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.net.Packet;
import mindustry.net.Streamable.StreamBuilder;

/**
 * Delegating {@link Net} proxy installed over Vars.net while on a client.
 * All state and behaviour is forwarded to the original instance; only
 * outgoing chat packets (SendChatMessageCallPacket) are intercepted.
 */
public class TranslatingNet extends Net{

    private final Net delegate;

    public TranslatingNet(Net delegate){
        super(null);
        this.delegate = delegate;
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
    }

    @Override
    public void setClientConnected(){
        delegate.setClientConnected();
    }

    @Override
    public void connect(String ip, int port, Runnable success){
        delegate.connect(ip, port, success);
    }

    @Override
    public void host(int port) throws java.io.IOException{
        delegate.host(port);
    }

    @Override
    public void closeServer(){
        delegate.closeServer();
    }

    @Override
    public void reset(){
        delegate.reset();
    }

    @Override
    public void disconnect(){
        delegate.disconnect();
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
        if(object instanceof SendChatMessageCallPacket){
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
        if(object instanceof SendMessageCallPacket2){
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
}