package com.ishland.batchify.mixin;

import com.ishland.batchify.common.s2c.chunkupdate.ChunkUpdateConsolidator;
import com.ishland.batchify.common.s2c.sync.SynchronizationLayer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class MixinClientConnection {

    @Inject(method = "addHandlers", at = @At("RETURN"))
    private static void onAddHandlers(ChannelPipeline pipeline, NetworkSide side, CallbackInfo ci) {
        pipeline.addLast("ChunkUpdateConsolidator", new ChunkUpdateConsolidator());
        pipeline.addLast("SynchronizationLayer", new SynchronizationLayer());
        pipeline.addLast(new FlushConsolidationHandler());
        if (Boolean.getBoolean("batchify.2mbpsConnection") && pipeline.channel() instanceof SocketChannel) {
            final ChannelTrafficShapingHandler trafficShapingHandler = new ChannelTrafficShapingHandler(2 * 1024 * 1024 / 8, 0, 1000L);
            pipeline.addAfter("timeout", "TrafficShaping", trafficShapingHandler);
        }
    }

}
