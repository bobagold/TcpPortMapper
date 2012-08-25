package com.gmail.bobagold.tcp.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author vgold
 */
public class Proxy extends Thread {
    private volatile boolean shutdown = false;
    private Selector selector;
    private LinkedList opened_sockets = new LinkedList();
    private static Charset charset = Charset.forName("US-ASCII");
    private static CharsetDecoder decoder = charset.newDecoder();

    public Proxy() throws IOException {
        selector = Selector.open();
    }
    @Override
    public void run() {
        try {
            listen(8080);//TODO move it to caller
            for (;;) {
                int selected_count = selector.select();
                System.out.println("selector worked");
                if (selected_count > 0)
                    processSelectedKeys();
                if (shutdown) {
                    selector.close();
                    break;
                }
            }
            closeSockets();
        } catch (IOException ex) {
            Logger.getLogger(Proxy.class.getName()).log(Level.SEVERE, null, ex);
        }
        System.out.println("Ok");
    }

    private void listen(int port) throws IOException {
	ServerSocketChannel ssc = ServerSocketChannel.open();
	InetSocketAddress isa
	    = new InetSocketAddress(InetAddress.getLocalHost(), port);
	ssc.socket().bind(isa);
        ssc.configureBlocking(false);
        ssc.register(selector, SelectionKey.OP_ACCEPT, new ServerChannel(ssc, port));
        synchronized (opened_sockets) {
            opened_sockets.add(ssc.socket());
        }
    }

    public void shutdown() {
        shutdown = true;
        selector.wakeup();
    }

    private void closeSockets() throws IOException {
        synchronized (opened_sockets) {
            while (opened_sockets.size() > 0) {
                Object socket = opened_sockets.removeLast();
                if (socket instanceof ServerSocket)
                    ((ServerSocket)socket).close();
            }
        }
    }

    private void processSelectedKeys() {
        for (SelectionKey i: selector.selectedKeys()) {
            Object attachment = i.attachment();
            if (attachment instanceof ServerChannel && i.isAcceptable()) {
                try {
                    accept((ServerChannel) attachment);
                } catch (IOException ex) {
                    Logger.getLogger(Proxy.class.getName()).log(Level.SEVERE, null, ex);
                }
            } else if (attachment instanceof ClientChannel && i.isReadable()) {
                try {
                    readClient((ClientChannel) attachment);
                } catch (IOException ex) {
                    Logger.getLogger(Proxy.class.getName()).log(Level.SEVERE, null, ex);
                }
            } else if (attachment instanceof RemoteChannel && i.isConnectable()) {
                try {
                    connectedRemote((RemoteChannel) attachment);
                } catch (IOException ex) {
                    Logger.getLogger(Proxy.class.getName()).log(Level.SEVERE, null, ex);
                }
            } else if (attachment instanceof RemoteChannel && i.isWritable()) {
                try {
                    writeRemote((RemoteChannel) attachment);
                } catch (IOException ex) {
                    Logger.getLogger(Proxy.class.getName()).log(Level.SEVERE, null, ex);
                }
            } else if (attachment instanceof RemoteChannel && i.isReadable()) {
                try {
                    readRemote((RemoteChannel) attachment);
                } catch (IOException ex) {
                    Logger.getLogger(Proxy.class.getName()).log(Level.SEVERE, null, ex);
                }
            } else if (attachment instanceof ClientChannel && i.isWritable()) {
                try {
                    writeClient((ClientChannel) attachment);
                } catch (IOException ex) {
                    Logger.getLogger(Proxy.class.getName()).log(Level.SEVERE, null, ex);
                }
            } else {
                System.err.println("unknown action");
            }
        }
        selector.selectedKeys().clear();
    }

    private RemoteSettings getRemoteSettings(int port) {
        return new RemoteSettings("localhost", 80);
    }

    private void accept(ServerChannel sc) throws IOException {
        SocketChannel accepted = sc.ssc.accept();
        accepted.configureBlocking(false);
        accepted.register(selector, SelectionKey.OP_READ, new ClientChannel(accepted, sc.port));
        System.out.println("Accepted");
    }

    private void readClient(ClientChannel clientChannel) throws IOException {
        ByteBuffer dbuf = ByteBuffer.allocateDirect(1024);
        dbuf.clear();
        clientChannel.sc.read(dbuf);
        dbuf.flip();
        System.out.print("Received " + decoder.decode(dbuf));
        clientChannel.sc.keyFor(selector).cancel();
        connectRemote(clientChannel, dbuf);
    }

    private void connectRemote(ClientChannel clientChannel, ByteBuffer dbuf) throws UnknownHostException, IOException {
        RemoteSettings rs = getRemoteSettings(clientChannel.port);
	InetSocketAddress isa
	    = new InetSocketAddress(InetAddress.getByName(rs.host), rs.port);
	SocketChannel sc = null;
        sc = SocketChannel.open();
        sc.configureBlocking(false);
        sc.connect(isa);
        sc.register(selector, SelectionKey.OP_CONNECT, new RemoteChannel(clientChannel, sc, dbuf));
        System.out.println("Connect remote");
    }

    private void connectedRemote(RemoteChannel remoteChannel) throws IOException {
        remoteChannel.sc.finishConnect();
        remoteChannel.sc.register(selector, SelectionKey.OP_WRITE, remoteChannel);
        remoteChannel.to_write.flip();
        System.out.println("Connected remote");
    }

    private void writeRemote(RemoteChannel remoteChannel) throws IOException {
        int written = remoteChannel.sc.write(remoteChannel.to_write);
        if (!remoteChannel.to_write.hasRemaining())
            remoteChannel.sc.register(selector, SelectionKey.OP_READ, remoteChannel);
        System.out.println("Written remote " + written + " bytes");
    }

    private void readRemote(RemoteChannel remoteChannel) throws IOException {
        ByteBuffer dbuf = ByteBuffer.allocateDirect(10240);
        dbuf.clear();
        int read = remoteChannel.sc.read(dbuf);
        dbuf.flip();
        System.out.print("Received from remote " + read + " bytes");
//        if (dbuf.)
        remoteChannel.sc.close();
        remoteChannel.clientChannel.remote_done = true;
        remoteChannel.clientChannel.to_write = dbuf;
        remoteChannel.clientChannel.sc.register(selector, SelectionKey.OP_WRITE, remoteChannel.clientChannel);
    }

    private void writeClient(ClientChannel clientChannel) throws IOException {
        int written = clientChannel.sc.write(clientChannel.to_write);
        if (!clientChannel.to_write.hasRemaining())
        {
            if (clientChannel.remote_done)
                clientChannel.sc.close();
            else
                ;//TODO allow remote to read further
        }
        System.out.println("Written client" + written + " bytes");
    }

    private static class ServerChannel {
        private ServerSocketChannel ssc;
        private int port;
        public ServerChannel(ServerSocketChannel ssc, int port) {
            this.ssc = ssc; this.port = port;
        }
    }
    
    private static class ClientChannel {
        private SocketChannel sc;
        private int port;
        private ByteBuffer to_write;
        private boolean remote_done = false;
        public ClientChannel(SocketChannel sc, int port) {
            this.sc = sc; this.port = port;
        }
    }

    private static class RemoteSettings {
        private String host;
        private int port;
        public RemoteSettings(String host, int port) {
            this.host = host; this.port = port;
        }
    }

    private static class RemoteChannel {
        private ClientChannel clientChannel;
        private SocketChannel sc;
        private ByteBuffer to_write;
        public RemoteChannel(ClientChannel clientChannel, SocketChannel sc, ByteBuffer to_write) {
            this.clientChannel = clientChannel;
            this.sc = sc;
            this.to_write = to_write;
        }
    }
}
