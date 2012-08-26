/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.gmail.bobagold;

import com.gmail.bobagold.tcp.proxy.Proxy;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author vgold
 */
public class TcpPortMapper {
    private static Proxy proxy;
    private static Properties properties = new Properties();
    private static HashMap <String, Integer>mappings = new HashMap();

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws InterruptedException {
        try {
            Integer one = Integer.valueOf(1);
            parseConfig();
            proxy = new Proxy();
            HashMap<Integer, Integer> listen_ports = new HashMap<Integer, Integer>();
            for (String i: mappings.keySet()) {
                try {
                    int localPort = Integer.parseInt(properties.getProperty(i + ".localPort", "0"));
                    String remoteHost = properties.getProperty(i + ".remoteHost");
                    int remotePort = Integer.parseInt(properties.getProperty(i + ".remotePort", "0"));
                    if (localPort <= 0 || remoteHost.length() == 0 || remotePort <= 0 || listen_ports.containsKey(localPort))
                        continue;
                    listen_ports.put(Integer.valueOf(localPort), one);
                    proxy.putRemoteSettings(localPort, remoteHost, remotePort);
                    proxy.listen(localPort, InetAddress.getByName("localhost"));
                } catch (NumberFormatException e) {
                } catch (IllegalArgumentException e) {
                }
            }
        } catch (IOException ex) {
            Logger.getLogger(TcpPortMapper.class.getName()).log(Level.SEVERE, null, ex);
            System.err.println("Can't create proxy");
            return;
        }
        Runtime.getRuntime().addShutdownHook(new ShutdownHook());
        proxy.start();
        Thread.sleep(5000);
        proxy.shutdown();
        proxy.join();
        System.out.println("Done");
    }

    private static void parseConfig() throws IOException {
        InputStream properties_stream = ClassLoader.getSystemResourceAsStream("proxy.properties");
        Integer one = Integer.valueOf(1);
        if (properties_stream == null) {
            System.out.println("properties file can not be read");
            mappings.put("default", one);
            properties.setProperty("default.localPort", "8080");
            properties.setProperty("default.remoteHost", "localhost");
            properties.setProperty("default.remotePort", "80");
        } else {
            properties.load(properties_stream);
            properties.list(System.out);
            for (String i: properties.stringPropertyNames()) {
                System.out.println("key: " + i);
                mappings.put(i.split("\\.")[0], one);
            }
        }
    }

    private static class ShutdownHook extends Thread {
        @Override
        public void run() {
            proxy.shutdown();
            try {
                proxy.join();
            } catch (InterruptedException ex) {
                Logger.getLogger(TcpPortMapper.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
}
