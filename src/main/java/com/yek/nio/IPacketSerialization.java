package com.yek.nio;

import java.nio.ByteBuffer;

public interface IPacketSerialization {
    public static final int ERR_BAD_PACKET = -1;
    public static final int ERR_REQUIRE_MORE = -2;

    /**
     * 获取数据包长度
     * @param buffer
     * @return
     */
    public int peekPacketLength(ByteBuffer buffer);

    /**
     * 从ByteBuffer读取数据
     * @param connection
     * @param buffer
     * @param length
     * @return
     */
    public Object readPacket(Connection connection, ByteBuffer buffer, int length);

    /**
     * 写入ByteBuffer一个对象
     * @param connection
     * @param object
     * @return
     */
    public byte[] writePacket(Connection connection, Object object);
}
