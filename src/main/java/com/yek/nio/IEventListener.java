package com.yek.nio;

public interface IEventListener {
    public static final int EVENT_CONNECTED = 1;
    public static final int EVENT_DISCONNECTED = 2;
    public static final int EVENT_TIMEOUT_ERROR = 3;
    public static final int EVENT_RECV_ERROR = 4;
    public static final int EVENT_SEND_ERROR = 5;
    public static final int EVENT_PACKET_ERROR = 6;
    public static final int EVENT_SEND_REQUEST = 7;

    public void handlePacket(Connection connection, Object packetData);

    public void handleEvent(Connection connection, int event);
}
