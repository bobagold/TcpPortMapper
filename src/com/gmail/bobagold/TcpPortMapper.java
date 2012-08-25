/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.gmail.bobagold;

import com.gmail.bobagold.tcp.proxy.Proxy;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author vgold
 */
public class TcpPortMapper {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws InterruptedException {
        Proxy proxy;
        try {
            proxy = new Proxy();
        } catch (IOException ex) {
            Logger.getLogger(TcpPortMapper.class.getName()).log(Level.SEVERE, null, ex);
            System.err.println("Can't create proxy");
            return;
        }
        proxy.start();
        Thread.sleep(5000);
        proxy.shutdown();
        proxy.join();
        System.out.println("Done");
    }
}
