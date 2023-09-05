package com.ishland.packumulator.common.test;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.TimeUnit;

public class LatencyOnPurpose extends ChannelDuplexHandler {

    private final long latencyMillis;

    public LatencyOnPurpose(long latencyMillis) {
        this.latencyMillis = latencyMillis;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ctx.executor().schedule(() -> {
            if (!ctx.isRemoved()) {
                ctx.write(msg, promise);
            }
        }, latencyMillis, TimeUnit.MILLISECONDS);
    }

}
