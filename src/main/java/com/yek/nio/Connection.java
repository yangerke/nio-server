package com.yek.nio;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.LinkedList;

public class Connection {
    private ByteBuffer recvBuffer;
    private ByteBuffer sendBuffer;
    protected SelectionKey selectionKey;
    private Object userData;
    private LinkedList<byte[]> packetQueue = new LinkedList<byte[]>();

    Server server;
    SocketChannel socketChannel;
    boolean isConnected;
    boolean isClosing;
    long id;
    long lastActiveTimestamp;
    int keepAliveTime;
    int seq;
    HashMap<Integer, Object> seqRelated;
    private int connectionProtocolVersion;

    public static final int ERR_NONE = 0;
    public static final int ERR_SERIALIZATION = -1;
    public static final int ERR_NOROOM = -2;
    public static final int ERR_WOULDBLOCK = -3;
    public static final int ERR_OUTOFMEMORY = -4;
    public static final int ERR_DISCONNECTED = -5;

    Connection() {
    }

    public <T> T getSeqRelated(int seq) {
        if (seqRelated == null)
            return null;
        return (T)seqRelated.get(seq);
    }

    public <T> void setSeqRelated(int seq, T obj) {
        if (seqRelated == null) {
            seqRelated = new HashMap<Integer, Object> ();
        }
        seqRelated.put (seq, obj);
    }
}
