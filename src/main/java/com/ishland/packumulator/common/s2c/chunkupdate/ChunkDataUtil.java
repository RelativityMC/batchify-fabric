package com.ishland.packumulator.common.s2c.chunkupdate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;

import java.util.BitSet;
import java.util.List;
import java.util.ListIterator;

public class ChunkDataUtil {


    public static void mergeLightData(BitSet dstInited, BitSet dstUninited, List<byte[]> dstNibbles,
                                      BitSet srcInited, BitSet srcUninited, List<byte[]> srcNibbles) {
        final int maxHeight = Math.max(Math.max(dstInited.size(), dstUninited.size()), Math.max(srcInited.size(), srcUninited.size()));
        final ListIterator<byte[]> dst = dstNibbles.listIterator();
        final ListIterator<byte[]> src = srcNibbles.listIterator();
        for (int i = 0; i < maxHeight; i++) {
            // inited and uninited states:
            // none are set: unchanged
            // only either can be set
            final boolean dstInitedValue = dstInited.get(i);
            final boolean dstUninitedValue = dstUninited.get(i);
            final boolean srcInitedValue = srcInited.get(i);
            final boolean srcUninitedValue = srcUninited.get(i);
            final boolean hasExisting = dstInitedValue || dstUninitedValue;
            if (hasExisting) {
                assert dst.hasNext(); // if not we are fucked
                dst.next();
            }
            if (srcInitedValue || srcUninitedValue) {
                assert src.hasNext(); // if not we are fucked
                final byte[] currentNibble = src.next();
                // copy nibbles
                if (hasExisting) {
                    dst.set(currentNibble.clone());
                } else {
                    dst.add(currentNibble.clone());
                }
                // copy flags
                dstInited.set(i, srcInitedValue);
                dstUninited.set(i, srcUninitedValue);
            }
        }
    }

    public static ChunkDataS2CPacket makeChunkDataPacket(int x, int z, ChunkData chunkData, LightData lightData, ByteBufAllocator alloc) {
        final ByteBuf parent = alloc.heapBuffer();
        try {
            final PacketByteBuf buf = new PacketByteBuf(parent);
            buf.writeInt(x);
            buf.writeInt(z);
            chunkData.write(buf);
            lightData.write(buf);
            return new ChunkDataS2CPacket(buf);
        } finally {
            parent.release();
        }
    }

    public static LightUpdateS2CPacket makeLightUpdatePacket(int x, int z, LightData lightData, ByteBufAllocator alloc) {
        final ByteBuf parent = alloc.heapBuffer();
        try {
            final PacketByteBuf buf = new PacketByteBuf(parent);
            buf.writeVarInt(x);
            buf.writeVarInt(z);
            lightData.write(buf);
            return new LightUpdateS2CPacket(buf);
        } finally {
            parent.release();
        }
    }

}
