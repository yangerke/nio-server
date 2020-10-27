package com.yek.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public class Server {
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_STARTING = 1;
    public static final int STATUS_LISTENING = 2;
    public static final int STATUS_CLOSING = 3;

    private ServerSocketChannel listenChannel;
    private Selector selector;
    private int defaultRecvSize = 65536;
    private int defaultSendSize = 65536;
    private long nextConnectionId;
    IPacketSerialization serialization;
    IEventListener listener;
    private InetSocketAddress listenAddress;
    private int status;
    private Object statusLock = new Object ();
    private Thread listenThread;
    private int keepAliveTime = 10000000;
    private Set<Connection> connectionSet = new LinkedHashSet<Connection>();
    private long selectCount;
    private int selectTimeout = 1;
    private boolean acceptConnections = true;
    long sendBytes;
    long recvBytes;

    private static Logger logger = LoggerFactory.getLogger (Server.class);


    public Server () {
    }

    public long getTotalRecvBytes () {
        return recvBytes;
    }

    public long getTotalSendBytes () {
        return sendBytes;
    }

    public int getConnectionCount () {
        return connectionSet.size ();
    }

    public void setAcceptConnections (boolean accept) {
        if (acceptConnections != accept) {
            acceptConnections = accept;
            if (listenChannel != null) {
                try {
                    listenChannel.register (selector, acceptConnections?SelectionKey.OP_CONNECT:0);
                } catch (ClosedChannelException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void setPacketListener(IEventListener l) {
        listener = l;
    }

    public void setSerialization(IPacketSerialization s) {
        serialization = s;
    }

    public void setSendBufferSize(int size) {
        defaultSendSize = size;
    }

    public void setRecvBufferSize(int size) {
        defaultRecvSize = size;
    }

    public void setKeepAliveTime(int keepAliveTime) {
        this.keepAliveTime = keepAliveTime;
    }

    public void setSelectTimeout (int selectTimeout) {
        this.selectTimeout = selectTimeout;
    }

    public Connection newConnection() {
        return new Connection ();
    }

    public void handleConnectionTimeout() {
        long ts = System.currentTimeMillis ();
        for (Iterator<Connection> iter = connectionSet.iterator (); iter.hasNext ();) {
            Connection conn = (Connection) iter.next ();

            if (ts - conn.lastActiveTimestamp > conn.keepAliveTime || !conn.isConnected) {
                iter.remove ();
                if (conn.isConnected) {
                    if (logger.isDebugEnabled ()) {
                        logger.debug ("CID["+conn.id+"] timeout "+ (ts - conn.lastActiveTimestamp) + "/" + conn.keepAliveTime);
                    }
                    conn.close (false);
                    if (listener != null) {
                        listener.handleEvent (conn, IEventListener.EVENT_TIMEOUT_ERROR);
                    }
                } else {
                    if (logger.isDebugEnabled ()) {
                        logger.debug ("CID["+conn.id+"][CLOSED] remove ");
                    }
                }
            }
        }
    }

    public void idleTask() {
        handleConnectionTimeout ();
    }

    public void accept (SocketChannel socketChannel) {
        Connection conn = null;
        try {
            conn = newConnection ();
            conn.theServer = this;
            socketChannel.configureBlocking (false);
            SelectionKey key = socketChannel.register (selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            conn.init (socketChannel, key, defaultRecvSize, defaultSendSize);
            conn.isConnected = true;
            conn.keepAliveTime = keepAliveTime;
            ++nextConnectionId;
            conn.id = nextConnectionId;
            key.attach (conn);
            conn.lastActiveTimestamp = System.currentTimeMillis ();
            connectionSet.add (conn);
            if (listener != null) {
                listener.handleEvent (conn, IEventListener.EVENT_CONNECTED);
            }
        } catch (Exception e) {
            conn.close (false);
            if (logger.isWarnEnabled ()) {
                logger.warn ("accept Error", e);
            }
        }
    }

    public void handleSelectionKey(SelectionKey key) throws IOException {
        int ops = key.readyOps ();
        if ((ops & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
            if (logger.isDebugEnabled ()) {
                Connection conn = (Connection) key.attachment ();
                logger.debug ("CID["+(conn==null?-1:conn.id)+"] handleSelectionKey ["+selectCount+"]: READ");
            }
            Connection conn = (Connection) key.attachment ();
            if (conn != null) {
                try {
                    conn.handleRecvEvent ();
                } catch (Exception e) {
                    conn.close (false);
                    if (listener != null) {
                        listener.handleEvent (conn, IEventListener.EVENT_RECV_ERROR);
                    }
                    if (logger.isErrorEnabled ()) {
                        logger.error ("read Error", e);
                    }
                }
            }
        } else if ((ops & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
            if (logger.isDebugEnabled ()) {
                Connection conn = (Connection) key.attachment ();
                logger.debug ("CID["+(conn==null?-1:conn.id)+"] handleSelectionKey ["+selectCount+"]: WRITE");
            }
            Connection conn = (Connection) key.attachment ();
            if (conn != null) {
                try {
                    conn.handleSendEvent ();
                } catch (Exception e) {
                    conn.close (false);
                    if (listener != null) {
                        listener.handleEvent (conn, IEventListener.EVENT_SEND_ERROR);
                    }
                    if (logger.isErrorEnabled ()) {
                        logger.error ("write Error", e);
                    }
                }
            }
        } else if ((ops & SelectionKey.OP_ACCEPT) == SelectionKey.OP_ACCEPT) {
            if (logger.isDebugEnabled ()) {
                Connection conn = (Connection) key.attachment ();
                logger.debug ("CID["+(conn==null?-1:conn.id)+"] handleSelectionKey ["+selectCount+"]: ACCEPT");
            }
            ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel ();
            SocketChannel socketChannel = serverChannel.accept ();
            if (socketChannel != null) {
                if (acceptConnections) {
                    accept (socketChannel);
                } else {
                    socketChannel.close ();
                }
            }
        }
    }

    public void listenOnce (int timeOut) throws IOException {
        int selects = 0;
        if (timeOut == -1) {
            selects = selector.select ();
        } else if (timeOut > 0) {
            selects = selector.select (timeOut);
        } else {
            selects = selector.selectNow ();
        }
        if (selects > 0) {
            ++selectCount;
            Set<SelectionKey> keys = selector.selectedKeys ();
            for (Iterator<SelectionKey> iter = keys.iterator (); iter.hasNext ();) {
                try {
                    SelectionKey key = iter.next ();
                    iter.remove ();
                    if (!key.isValid ())
                        continue;
                    handleSelectionKey (key);
                } catch (Exception e) {
                    if (logger.isErrorEnabled ()) {
                        logger.error ("handleSelectionKey Error", e);
                    }
                }
            }
        }
        // maybe send pending data here
        if (listener != null)
            listener.handleEvent (null, IEventListener.EVENT_SEND_REQUEST);
    }

    private class ListenThread implements Runnable {
        @Override
        public void run() {
            try {
                Thread.currentThread ().setName ("nio-server-thread");
                startListening ();
                while (status == STATUS_LISTENING) {
                    synchronized (statusLock) {
                        if (status != STATUS_LISTENING)
                            break;
                    }
                    listenOnce (selectTimeout);
                    idleTask ();
                }
            } catch (Exception e) {
                if (logger.isErrorEnabled ()) {
                    logger.error ("ListenThread Error", e);
                }
            }
            stopListening ();
        }
    }

    private void stopListening() {
        try {
            listenChannel.close ();
        } catch (Exception e) {
            if (logger.isErrorEnabled ()) {
                logger.error ("close listenChannel Error", e);
            }
        }
        listenChannel = null;
        try {
            selector.close ();
        } catch (Exception e) {
            if (logger.isErrorEnabled ()) {
                logger.error ("close selector Error", e);
            }
        }
        selector = null;
        status = STATUS_IDLE;
    }

    public void startListening() throws IOException {
        status = STATUS_STARTING;
        selector = Selector.open ();
        listenChannel = ServerSocketChannel.open ();
        ServerSocket socket = listenChannel.socket ();
        socket.bind (listenAddress);
        listenChannel.configureBlocking (false);
        listenChannel.register (selector, SelectionKey.OP_ACCEPT);
        status = STATUS_LISTENING;
    }

    public void startListenThread() {
        listenThread = new Thread (new ListenThread ());
        listenThread.setName ("nio-server-thread");
        listenThread.start ();
    }

    public void listen(InetSocketAddress address) {
        listenAddress = address;
        startListenThread ();
    }

    public void closeNoWait() {
        if (listenThread != null) {
            synchronized (statusLock) {
                status = STATUS_CLOSING;
            }
        }
    }

    public void awaitConnectionClose (long timeout) {
        long ts = System.currentTimeMillis ();
        try {
            if (selector != null)
                selector.wakeup ();
        } catch (Exception e) {
        }
        while (listenThread != null && connectionSet.size () > 0 && (timeout < 0 || (System.currentTimeMillis () - ts) < timeout)) {
            try {
                Thread.sleep (1);
            } catch (InterruptedException e) {
            }
        }
    }

    public void close() {
        if (listenThread != null) {
            synchronized (statusLock) {
                status = STATUS_CLOSING;
            }
            try {
                if (selector != null)
                    selector.wakeup ();
                listenThread.join ();
            } catch (InterruptedException e) {
            }
        }
    }
}
