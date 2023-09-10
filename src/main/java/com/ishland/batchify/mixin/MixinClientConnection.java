package com.ishland.batchify.mixin;

import com.ishland.batchify.common.s2c.chunkupdate.ChunkUpdateConsolidator;
import com.ishland.batchify.common.s2c.sync.SynchronizationLayer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.flush.FlushConsolidationHandler;
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
//        pipeline.addLast("LatencyOnPurpose", new LatencyOnPurpose(100L));
        pipeline.addLast("ChunkUpdateConsolidator", new ChunkUpdateConsolidator());
        pipeline.addLast("SynchronizationLayer", new SynchronizationLayer());
        pipeline.addLast(new FlushConsolidationHandler());
    }

}
