package com.ishland.packumulator.mixin;

import com.ishland.packumulator.common.s2c.chunkupdate.ChunkUpdateConsolidator;
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
        pipeline.addLast("ChunkUpdateConsolidator", new ChunkUpdateConsolidator());
        pipeline.addLast(new FlushConsolidationHandler());
    }

}
