package com.gmail.bobagold.tcp.proxy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
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

    public Proxy() throws IOException {
        selector = Selector.open();
    }
    @Override
    public void run() {
        try {
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

    public void listen(int port) throws IOException {
        listen(port, InetAddress.getLocalHost());
    }
    public void listen(int port, final InetAddress localHost) throws IOException {
        if (null == getRemoteSettings(port))
            throw new IllegalArgumentException("port is not in mapping");
	ServerSocketChannel ssc = ServerSocketChannel.open();
	InetSocketAddress isa
	    = new InetSocketAddress(localHost, port);
	ssc.socket().bind(isa);
        ssc.configureBlocking(false);
        ssc.register(selector, SelectionKey.OP_ACCEPT, new ServerChannel(ssc, port));
        synchronized (opened_sockets) {
            opened_sockets.add(ssc.socket());
        }
        System.out.println("Accepting connections on " + localHost + ":" + port);
    }

    public void shutdown() {
        shutdown = true;
        selector.wakeup();
    }

    private void closeSockets() throws IOException {
        synchronized (opened_sockets) {
            int closed = 0;
            while (opened_sockets.size() > 0) {
                Object socket = opened_sockets.removeLast();
                if (socket instanceof ServerSocket)
                    ((ServerSocket)socket).close();
                else if (socket instanceof Socket)
                    ((Socket)socket).close();
                closed++;
            }
            System.out.println("Closed sockets: " + closed);
        }
    }

    private void processSelectedKeys() {
        for (SelectionKey i: selector.selectedKeys()) {
            Object attachment = i.attachment();
            try {
                if (attachment instanceof ServerChannel && i.isAcceptable()) {
                        accept((ServerChannel) attachment);
                } else if (attachment instanceof ClientChannel && i.isReadable()) {
                        readClient((ClientChannel) attachment);
                } else if (attachment instanceof RemoteChannel && i.isConnectable()) {
                        connectedRemote((RemoteChannel) attachment);
                } else if (attachment instanceof RemoteChannel && i.isWritable()) {
                        writeRemote((RemoteChannel) attachment);
                } else if (attachment instanceof RemoteChannel && i.isReadable()) {
                        readRemote((RemoteChannel) attachment);
                } else if (attachment instanceof ClientChannel && i.isWritable()) {
                        writeClient((ClientChannel) attachment);
                } else {
                    System.err.println("unknown action");
                }
            } catch (IOException ex) {
                ex.printStackTrace();
                Logger.getLogger(Proxy.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        selector.selectedKeys().clear();
    }

    private RemoteSettings getRemoteSettings(int port) {
        return remote_settings.get(Integer.valueOf(port));
    }
    private HashMap<Integer, RemoteSettings> remote_settings = new HashMap<Integer, RemoteSettings>();
    public void putRemoteSettings(int port_from, String host_to, int port_to) {
        remote_settings.put(Integer.valueOf(port_from), new RemoteSettings(host_to, port_to));
    }

    private void accept(ServerChannel sc) throws IOException {
        SocketChannel accepted = sc.ssc.accept();
        accepted.configureBlocking(false);
        synchronized (opened_sockets) {
            opened_sockets.add(accepted.socket());
        }
        System.out.println("Accepted");
        connectRemote(new ClientChannel(accepted, sc.port));
    }

    private void connectRemote(ClientChannel clientChannel) throws UnknownHostException, IOException {
        RemoteSettings rs = getRemoteSettings(clientChannel.port);
	InetSocketAddress isa;
        try {
            isa = new InetSocketAddress(InetAddress.getByName(rs.host), rs.port);
        } catch (UnknownHostException e) {
            closeClient(clientChannel);
            return;
        } catch (IllegalArgumentException e) {
            closeClient(clientChannel);
            return;
        }
	SocketChannel sc = null;
        sc = SocketChannel.open();
        sc.configureBlocking(false);
        sc.connect(isa);
        sc.register(selector, SelectionKey.OP_CONNECT, new RemoteChannel(clientChannel, sc));
        System.out.println("Connect remote");
    }

    private void connectedRemote(RemoteChannel remoteChannel) throws IOException {
        try {
            remoteChannel.sc.finishConnect();
        } catch (ConnectException ce) {
            closedRemote(remoteChannel);
            return;
        }
        remoteChannel.sc.register(selector, SelectionKey.OP_READ, remoteChannel);
        remoteChannel.clientChannel.sc.register(selector, SelectionKey.OP_READ, remoteChannel.clientChannel);
        synchronized (opened_sockets) {
            opened_sockets.add(remoteChannel.sc.socket());
        }
        System.out.println("Connected remote");
    }

    private void readClient(ClientChannel clientChannel) throws IOException {
        int read = clientChannel.sc.read(clientChannel.client_to_remote);
        if (read == -1) {
            closedClient(clientChannel);
            return;
        }
        System.out.println("Received client " + read + " bytes");
        clientChannel.client_to_remote.flip();
        clientChannel.sc.register(selector, 0, clientChannel);
        clientChannel.remoteChannel.sc.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, clientChannel.remoteChannel);
    }

    private void writeRemote(RemoteChannel remoteChannel) throws IOException {
        int written = remoteChannel.sc.write(remoteChannel.clientChannel.client_to_remote);
        if (!remoteChannel.clientChannel.client_to_remote.hasRemaining()) {
            remoteChannel.clientChannel.client_to_remote.clear();
            remoteChannel.sc.register(selector, SelectionKey.OP_READ, remoteChannel);
            remoteChannel.clientChannel.sc.register(selector, SelectionKey.OP_READ, remoteChannel.clientChannel);
        }
        System.out.println("Written remote " + written + " bytes");
    }

    private void readRemote(RemoteChannel remoteChannel) throws IOException {
        int start = remoteChannel.remote_to_client.position();
        int read = remoteChannel.sc.read(remoteChannel.remote_to_client);
        if (read == -1) {
            closedRemote(remoteChannel);
            return;
        }
        System.out.println("Received remote " + read + " bytes");
        remoteChannel.remote_to_client.flip();
        remoteChannel.sc.register(selector, 0, remoteChannel);
        remoteChannel.clientChannel.sc.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, remoteChannel.clientChannel);
    }

    private void writeClient(ClientChannel clientChannel) throws IOException {
        int written = clientChannel.sc.write(clientChannel.remoteChannel.remote_to_client);
        if (!clientChannel.remoteChannel.remote_to_client.hasRemaining()) {
            clientChannel.remoteChannel.remote_to_client.clear();
            clientChannel.sc.register(selector, SelectionKey.OP_READ, clientChannel);
            clientChannel.remoteChannel.sc.register(selector, SelectionKey.OP_READ, clientChannel.remoteChannel);
        }
        System.out.println("Written client " + written + " bytes");
    }

    private void closedClient(ClientChannel clientChannel) throws IOException {
        clientChannel.sc.close();
        RemoteChannel remoteChannel = clientChannel.remoteChannel;
        clientChannel.remoteChannel = null;
        remoteChannel.clientChannel = null;
        remoteChannel.sc.close();
        synchronized (opened_sockets) {
            opened_sockets.remove(clientChannel.sc.socket());
            opened_sockets.remove(remoteChannel.sc.socket());
        }
        System.out.println("Closed client");
    }

    private void closedRemote(RemoteChannel remoteChannel) throws IOException {
        remoteChannel.sc.close();
        ClientChannel clientChannel = remoteChannel.clientChannel;
        remoteChannel.clientChannel = null;
        clientChannel.remoteChannel = null;
        clientChannel.sc.close();
        synchronized (opened_sockets) {
            opened_sockets.remove(clientChannel.sc.socket());
            opened_sockets.remove(remoteChannel.sc.socket());
        }
        System.out.println("Closed remote");
    }

    private void closeClient(ClientChannel clientChannel) throws IOException {
        clientChannel.sc.close();
        synchronized (opened_sockets) {
            opened_sockets.remove(clientChannel.sc.socket());
        }
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
        private ByteBuffer client_to_remote;
        private RemoteChannel remoteChannel;
        public ClientChannel(SocketChannel sc, int port) {
            this.sc = sc; this.port = port; this.client_to_remote = ByteBuffer.allocateDirect(1024);
        }
    }

    private static class RemoteChannel {
        private ClientChannel clientChannel;
        private SocketChannel sc;
        private ByteBuffer remote_to_client;
        public RemoteChannel(ClientChannel clientChannel, SocketChannel sc) {
            this.clientChannel = clientChannel;
            this.sc = sc;
            remote_to_client = ByteBuffer.allocateDirect(10240);
            clientChannel.remoteChannel = this;
        }
    }

    private static class RemoteSettings {
        private String host;
        private int port;
        public RemoteSettings(String host, int port) {
            this.host = host; this.port = port;
        }
    }
}
