package com.ishland.packumulator.common.s2c.chunkupdate;

import com.ishland.packumulator.common.PacketWrapper;
import com.ishland.packumulator.common.s2c.FutureUtil;
import com.ishland.packumulator.mixin.access.IChunkDeltaUpdateS2CPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.PromiseCombiner;
import it.unimi.dsi.fastutil.ints.Int2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.shorts.Short2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import org.jetbrains.annotations.Nullable;

public class ChunkPendingChanges {

    private final long pos;
    private boolean initialPacketSent = false;
    private ChunkData pendingChunkData = null;
    private ChannelPromise pendingChunkDataPromise = null;
    private LightData pendingLightData = null;
    private ChannelPromise pendingLightDataPromise = null;
    private final Int2ReferenceLinkedOpenHashMap<SectionedPendingDeltaUpdate> pendingDeltaUpdates = new Int2ReferenceLinkedOpenHashMap<>();
    private final Long2ReferenceLinkedOpenHashMap<PacketWrapper<BlockEntityUpdateS2CPacket>> blockEntityUpdates = new Long2ReferenceLinkedOpenHashMap<>();
    private final ObjectArrayFIFOQueue<PacketWrapper<Packet<?>>> extraPackets = new ObjectArrayFIFOQueue<>();

    public ChunkPendingChanges(long pos) {
        this.pos = pos;
    }

    @Nullable
    public PacketWrapper<? extends Packet<?>> producePacket(ChannelHandlerContext ctx) {
        if (this.pendingChunkData != null) {
            assert this.pendingLightData != null;
            final ChunkDataS2CPacket packet = ChunkDataUtil.makeChunkDataPacket(ChunkPos.getPackedX(this.pos), ChunkPos.getPackedZ(this.pos), this.pendingChunkData, this.pendingLightData, ctx.alloc());
            final ChannelPromise promise = ctx.newPromise();
            if (this.pendingChunkDataPromise != null) {
                FutureUtil.chainPromises(promise, this.pendingChunkDataPromise);
            }
            if (this.pendingLightDataPromise != null) {
                FutureUtil.chainPromises(promise, this.pendingLightDataPromise);
            }
            this.pendingChunkData = null;
            this.pendingLightData = null;
            this.pendingChunkDataPromise = null;
            this.pendingLightDataPromise = null;
            this.initialPacketSent = true;
            return new PacketWrapper<>(packet, promise);
        }

        if (!this.initialPacketSent) return null;

        if (this.pendingLightData != null) {
            final LightUpdateS2CPacket packet = ChunkDataUtil.makeLightUpdatePacket(ChunkPos.getPackedX(this.pos), ChunkPos.getPackedZ(this.pos), this.pendingLightData, ctx.alloc());
            final ChannelPromise promise = this.pendingLightDataPromise;
            this.pendingLightData = null;
            this.pendingLightDataPromise = null;
            return new PacketWrapper<>(packet, promise != null ? promise : ctx.newPromise());
        }

        while (!this.pendingDeltaUpdates.isEmpty()) {
            final SectionedPendingDeltaUpdate deltaUpdate = this.pendingDeltaUpdates.removeFirst();
            final PacketWrapper<? extends Packet<?>> packet = deltaUpdate.producePacket(ctx);
            if (packet != null) return packet;
        }

        if (!this.blockEntityUpdates.isEmpty()) {
            return this.blockEntityUpdates.removeFirst();
        }

        if (!this.extraPackets.isEmpty()) {
            return this.extraPackets.dequeue();
        }

        return null;
    }

    public void consume(ChunkData chunkData, ChannelPromise promise) {
        this.pendingChunkData = chunkData;
        if (this.pendingChunkDataPromise != null) this.pendingChunkDataPromise.trySuccess();
        this.pendingChunkDataPromise = promise;
    }

    public void consume(LightData lightData, ChannelPromise promise) {
        if (this.pendingLightData == null) {
            this.pendingLightData = lightData;
            if (this.pendingLightDataPromise != null) this.pendingLightDataPromise.trySuccess();
            this.pendingLightDataPromise = promise;
        } else {
            ChunkDataUtil.mergeLightData(
                    this.pendingLightData.getInitedBlock(), this.pendingLightData.getUninitedBlock(), this.pendingLightData.getBlockNibbles(),
                    lightData.getInitedBlock(), lightData.getUninitedBlock(), lightData.getBlockNibbles()
            );
            ChunkDataUtil.mergeLightData(
                    this.pendingLightData.getInitedSky(), this.pendingLightData.getUninitedSky(), this.pendingLightData.getSkyNibbles(),
                    lightData.getInitedSky(), lightData.getUninitedSky(), lightData.getSkyNibbles()
            );
            if (this.pendingLightDataPromise != null) {
                FutureUtil.chainPromises(this.pendingLightDataPromise, promise);
            } else {
                this.pendingLightDataPromise = promise;
            }
        }
    }

    public void consume(ChunkDeltaUpdateS2CPacket packet, ChannelPromise promise) {
        final ChunkSectionPos sectionPos = ((IChunkDeltaUpdateS2CPacket) packet).getSectionPos();
        assert sectionPos.toChunkPos().toLong() == this.pos;
        final SectionedPendingDeltaUpdate sectionedPendingDeltaUpdate = this.pendingDeltaUpdates.computeIfAbsent(sectionPos.getSectionY(), SectionedPendingDeltaUpdate::new);
        packet.visitUpdates(sectionedPendingDeltaUpdate::consume);
        sectionedPendingDeltaUpdate.promises.add(promise);
    }

    public void consume(BlockPos pos, BlockState state, ChannelPromise promise) {
        assert ChunkPos.toLong(pos) == this.pos;
        final int sectionY = ChunkSectionPos.getSectionCoord(pos.getY());
        final SectionedPendingDeltaUpdate sectionedPendingDeltaUpdate = this.pendingDeltaUpdates.computeIfAbsent(sectionY, SectionedPendingDeltaUpdate::new);
        sectionedPendingDeltaUpdate.consume(pos, state);
        sectionedPendingDeltaUpdate.promises.add(promise);
    }

    public void consume(BlockEntityUpdateS2CPacket packet, ChannelPromise promise) {
        assert ChunkPos.toLong(packet.getPos()) == this.pos;
        this.blockEntityUpdates.putAndMoveToLast(packet.getPos().asLong(), new PacketWrapper<>(packet, promise));
    }

    public void consumeRaw(Packet<?> packet, ChannelPromise promise) {
        this.extraPackets.enqueue(new PacketWrapper<>(packet, promise));
    }

    public boolean isInitialPacketSent() {
        return this.initialPacketSent;
    }

    private class SectionedPendingDeltaUpdate {
        private final int y;
        public final Short2ReferenceLinkedOpenHashMap<BlockState> updates = new Short2ReferenceLinkedOpenHashMap<>();
        public final ReferenceArrayList<ChannelPromise> promises = new ReferenceArrayList<>();

        private SectionedPendingDeltaUpdate(int y) {
            this.y = y;
        }

        public void consume(BlockPos pos, BlockState state) {
            assert ChunkSectionPos.getSectionCoord(pos.getY()) == this.y;
            this.updates.put(ChunkSectionPos.packLocal(pos), state);
        }

        public PacketWrapper<? extends Packet<?>> producePacket(ChannelHandlerContext ctx) {
            if (updates.isEmpty()) return null;
            final ChunkDeltaUpdateS2CPacket packet = new ChunkDeltaUpdateS2CPacket(
                    ChunkSectionPos.from(ChunkPos.getPackedX(ChunkPendingChanges.this.pos), y, ChunkPos.getPackedZ(ChunkPendingChanges.this.pos)),
                    ShortSet.of(),
                    null
            );
            ((IChunkDeltaUpdateS2CPacket) packet).setPositions(this.updates.keySet().toShortArray());
            ((IChunkDeltaUpdateS2CPacket) packet).setBlockStates(this.updates.values().toArray(BlockState[]::new));
            final ChannelPromise promise = ctx.newPromise();
            FutureUtil.chainPromises(promise, this.promises.toArray(ChannelPromise[]::new));
            this.updates.clear();
            this.promises.clear();
            return new PacketWrapper<>(packet, promise);
        }
    }

}
