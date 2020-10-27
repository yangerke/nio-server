package com.yek.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.LinkedList;

public class Connection {
    private ByteBuffer recvBuffer;
    private ByteBuffer sendBuffer;
    protected SelectionKey selectionKey;
    private Object userData;
    private LinkedList<byte[]> packetQueue = new LinkedList<byte[]> ();

    Server theServer;
    SocketChannel socketChannel;
    boolean isConnected;
    boolean isClosing;
    long id;
    long lastActiveTimestamp;
    int keepAliveTime;
    int seq;
    HashMap<Integer, Object> seqRelated;
    private int connectionProtocolVersion;

    private static Logger logger = LoggerFactory.getLogger(Connection.class);

    public static final int ERR_NONE = 0;
    public static final int ERR_SERIALIZATION = -1;
    public static final int ERR_NOROOM = -2;
    public static final int ERR_WOULDBLOCK = -3;
    public static final int ERR_OUTOFMEMORY = -4;
    public static final int ERR_DISCONNECTED = -5;

    Connection () {
    }

    @SuppressWarnings("unchecked")
    public <T> T getSeqRelated (int seq) {
        if (seqRelated == null)
            return null;
        return (T)seqRelated.get (seq);
    }

    @SuppressWarnings("unchecked")
    public <T> T removeSeqRelated (int seq) {
        if (seqRelated == null)
            return null;
        return (T)seqRelated.remove (seq);
    }

    public <T> void setSeqRelated (int seq, T obj) {
        if (seqRelated == null) {
            seqRelated = new HashMap<Integer, Object> ();
        }
        seqRelated.put (seq, obj);
    }

    public void setSeq (int seq) {
        this.seq = seq;
    }

    public int getSeq () {
        return seq;
    }

    public boolean isConnected () {
        return this.isConnected;
    }

    public void setKeepAliveTime (int time) {
        keepAliveTime = time;
    }

    public SocketChannel getSocketChannel () {
        return socketChannel;
    }

    public void init (SocketChannel channel, SelectionKey key, int recvBufferSize, int sendBufferSize) {
        socketChannel = channel;
        selectionKey = key;
        recvBuffer = ByteBuffer.allocate (recvBufferSize);
        recvBuffer.clear();
        sendBuffer = ByteBuffer.allocate (sendBufferSize);
        sendBuffer.clear ();
        sendBuffer.flip ();
    }

    public void close (boolean invokeDisconnectCB) {
        if (isConnected) {
            if (logger.isDebugEnabled ()) {
                logger.debug ("CID[" + id + "] close " +  isConnected + " " + isConnected);
            }
            boolean connected = isConnected;
            isConnected = false;
            if (socketChannel != null) {
                try {
                    socketChannel.close();
                } catch (IOException e) {
                }
                socketChannel = null;
            }
            if (selectionKey != null) {
                selectionKey.attach (null);
                selectionKey.cancel();
                selectionKey = null;
            }
            recvBuffer = null;
            sendBuffer = null;

            if (theServer.listener != null && connected && invokeDisconnectCB) {
                theServer.listener.handleEvent(this, IEventListener.EVENT_DISCONNECTED);
            }
        } else {
            // should not go here !!!
            if (socketChannel != null) {
                try {
                    socketChannel.close();
                } catch (IOException e) {
                }
                socketChannel = null;
            }
            recvBuffer = null;
            sendBuffer = null;
            if (selectionKey != null) {
                selectionKey.attach (null);
                selectionKey.cancel();
                selectionKey = null;
            }
        }
    }

    private static final int SEND_NONE = 0;
    private static final int SEND_ALL = 1;
    private static final int SEND_SOME = 2;

    private void supplyPacketData () {
        sendBuffer.clear ();
        while (packetQueue.size () > 0) {
            byte[] data = packetQueue.getFirst ();
            if (data.length > sendBuffer.remaining ()) {
                break;
            }
            sendBuffer.put (data);
            packetQueue.remove ();
        }
        sendBuffer.flip ();
    }

    public void requestClose () {
        isClosing = true;
    }

    public boolean isClosing () {
        return isClosing;
    }

    public int sendPacketToSocketChannel () throws IOException {
        boolean active = false;
        for (;;) {
            if (sendBuffer.hasRemaining ()) {
                int pos = sendBuffer.position ();
                if (socketChannel.write(sendBuffer) == 0) {
                    break;
                } else {
                    int sent = sendBuffer.position () - pos;
                    theServer.sendBytes += sent;
                    active = true;
                }
            } else {
                if (packetQueue.size () > 0) {
                    supplyPacketData ();
                } else {
                    break;
                }
            }
        }
        if (active)
            lastActiveTimestamp = System.currentTimeMillis();

        return sendBuffer.hasRemaining ()?(active?SEND_SOME:SEND_NONE):SEND_ALL;
    }

    // must be called from selection thread !!!
    public void sendObject (Object obj) throws IOException {
        if (!isConnected) {
            throw new IOException ("CID[" + id + "] sendObject '" + obj + "' err:ERR_DISCONNECTED");
        }
        byte[] data = theServer.serialization.writePacket (this, obj);
        if (packetQueue.size () == 0 && data.length <= sendBuffer.capacity () - sendBuffer.limit ()) {
            sendBuffer.compact ();
            sendBuffer.put (data);
            sendBuffer.flip ();
        } else {
            packetQueue.addLast (data);
        }

        try {
            int sendStatus = sendPacketToSocketChannel ();
            if (sendStatus != SEND_ALL) {
                if (logger.isDebugEnabled ()) {
                    logger.debug ("CID[" + id + "] sendObject: wait next send");
                }
                selectionKey.interestOps(SelectionKey.OP_WRITE|SelectionKey.OP_READ);
            } else {
                if (logger.isDebugEnabled ()) {
                    logger.debug ("CID[" + id + "] sendObject: send finish");
                }
                selectionKey.interestOps(SelectionKey.OP_READ);
                if (isClosing) {
                    close (true);
                }
            }
        } catch (ClosedChannelException ex) {
            if (logger.isDebugEnabled ()) {
                logger.debug ("CID[" + id + "] sendObject '" + obj + "' err:ERR_DISCONNECTED in sending");
            }
        }
    }

    public void handleRecvEvent () throws IOException {
        IPacketSerialization serialization = theServer.serialization;
        for (;;) {
            int recv = socketChannel.read (recvBuffer);
            if (logger.isDebugEnabled ()) {
                logger.debug ("CID[" + id + "] recv " + recv + "/" + recvBuffer.remaining ());
            }
            if (recv > 0) {
                theServer.recvBytes += recv;
                lastActiveTimestamp = System.currentTimeMillis();
                recvBuffer.flip();
                int limit = recvBuffer.limit();
                for (;;) {
                    int position = recvBuffer.position();
                    int packetLen = serialization.peekPacketLength (recvBuffer);
                    if (logger.isDebugEnabled ()) {
                        logger.debug ("CID[" + id + "] serialization.peekPacketLength " + packetLen);
                    }
                    if (packetLen == IPacketSerialization.ERR_BAD_PACKET) {
                        // Error
                        close (false);
                        if (theServer.listener != null)
                            theServer.listener.handleEvent (this, IEventListener.EVENT_PACKET_ERROR);
                        return;
                    }
                    else if (packetLen == IPacketSerialization.ERR_REQUIRE_MORE || position + packetLen > limit)
                        break;
                    recvBuffer.position (position);
                    Object obj = serialization.readPacket (this, recvBuffer, packetLen);
                    if (obj == null) {
                        // Error
                        close (false);
                        if (theServer.listener != null)
                            theServer.listener.handleEvent (this, IEventListener.EVENT_PACKET_ERROR);
                        return;
                    } else {
                        if (theServer.listener != null)
                            theServer.listener.handlePacket (this, obj);
                    }
                    recvBuffer.position (position + packetLen);
                }
                recvBuffer.compact();
            } else if (recv == 0) {
                break;
            } else /* if (recv < 0) */ {
                // remote close
                if (logger.isDebugEnabled ()) {
                    logger.debug ("CID[" + id + "] remote close");
                }
                close (true);
                break;
            }
        }
    }

    public void handleSendEvent () throws IOException {
        int sendStatus = sendPacketToSocketChannel ();
        if (sendStatus != Connection.SEND_ALL) {
            if (logger.isDebugEnabled ()) {
                logger.debug ("CID[" + id + "] wait next send");
            }
            selectionKey.interestOps(SelectionKey.OP_WRITE|SelectionKey.OP_READ);
        } else {
            if (sendStatus == Connection.SEND_ALL) {
                if (logger.isDebugEnabled ()) {
                    logger.debug ("CID[" + id + "] send finished");
                }
            }
            selectionKey.interestOps(SelectionKey.OP_READ);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getUserData () {
        return (T)userData;
    }

    public <T> void setUserData (T data) {
        userData = data;
    }

    public long getId () {
        return id;
    }

    public static String getEventName (int event) {
        switch (event) {
            case IEventListener.EVENT_CONNECTED:
                return "EVENT_CONNECTED";
            case IEventListener.EVENT_DISCONNECTED:
                return "EVENT_DISCONNECTED";
            case IEventListener.EVENT_TIMEOUT_ERROR:
                return "EVENT_TIMEOUT_ERROR";
            case IEventListener.EVENT_SEND_ERROR:
                return "EVENT_SEND_ERROR";
            case IEventListener.EVENT_RECV_ERROR:
                return "EVENT_RECV_ERROR";
            case IEventListener.EVENT_PACKET_ERROR:
                return "EVENT_PACKET_ERROR";
            default:
                return "<Unknown Event>";
        }
    }

    public int getConnectionProtocolVersion () {
        return connectionProtocolVersion;
    }

    public void setConnectionProtocolVersion (int protocolVersion) {
        this.connectionProtocolVersion = protocolVersion;
    }
}
