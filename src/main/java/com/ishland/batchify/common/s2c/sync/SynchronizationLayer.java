package com.ishland.batchify.common.s2c.sync;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.util.Identifier;

public class SynchronizationLayer extends ChannelDuplexHandler {

    public static final Object SYNC_REQUEST_OBJECT = new Object();
    public static final Object SYNC_REQUEST_OBJECT_DIM_CHANGE = new Object();

    private Identifier dimension = null;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof PlayerRespawnS2CPacket packet) {
            final Identifier changedDimension = packet.getDimension().getValue();
            if (changedDimension.equals(this.dimension)) {
                ctx.pipeline().fireUserEventTriggered(SYNC_REQUEST_OBJECT);
            } else {
                this.dimension = changedDimension;
                ctx.pipeline().fireUserEventTriggered(SYNC_REQUEST_OBJECT_DIM_CHANGE);
            }
        }
        ctx.write(msg, promise);
    }
}
