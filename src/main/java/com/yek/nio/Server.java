package com.yek.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_STARTING = 1;
    public static final int STATUS_LISTENING = 2;
    public static final int STATUS_CLOSING = 3;

    private InetSocketAddress listenAddress;
    private Thread listenThread;
    private int status;
    private Selector selector;
    private ServerSocketChannel listenChannel;
    private Object statusLock = new Object();
    private int selectTimeout = 1;

    public void listen(InetSocketAddress address) {
        //初始化监听地址
        listenAddress = address;
        //启动监听线程
        startListenThread();
    }

    public void startListenThread() {
        listenThread = new Thread();
        //设置监听线程名字
        listenThread.setName("NIOS-THREAD");
        //启动线程
        listenThread.start();
    }

    private class ListenThread implements Runnable {
        @Override
        public void run() {
            try {
                //开启socket监听
                startListening();
                while (status == STATUS_LISTENING) {
                    synchronized (statusLock) {
                        if (status != STATUS_LISTENING) {
                            break;
                        }
                    }
                    listenOnce(selectTimeout);
                }
            } catch(Exception e) {
                if (logger.isErrorEnabled()) {
                    logger.error("ListenThread error");
                }
            }
        }
    }

    public void startListening() throws IOException {
        status = STATUS_STARTING;
        selector = Selector.open();
        listenChannel = ServerSocketChannel.open();
        ServerSocket serverSocket = listenChannel.socket();
        serverSocket.bind(listenAddress);
        listenChannel.configureBlocking(false);
        listenChannel.register(selector, SelectionKey.OP_ACCEPT);
        status = STATUS_LISTENING;
    }

    public void listenOnce(int selectTimeout) {

    }
}
