package com.ishland.packumulator.common.s2c.chunkupdate;

import com.ishland.packumulator.common.PacketWrapper;
import com.ishland.packumulator.common.s2c.sync.SynchronizationLayer;
import com.ishland.packumulator.common.structs.DynamicPriorityQueue;
import com.ishland.packumulator.mixin.access.IChunkDeltaUpdateS2CPacket;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.PromiseCombiner;
import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkBiomeDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkLoadDistanceS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkRenderDistanceCenterS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkUpdateConsolidator extends ChannelDuplexHandler {

    private static final int MAX_CONCURRENT_PACKETS = 64;
    private static final int PRIORITY_COUNT = 256;
    private static final AtomicBoolean issuedWarning = new AtomicBoolean(false);

    private ChannelHandlerContext ctx;
    private final Long2ReferenceLinkedOpenHashMap<ChunkPendingChanges> chunks = new Long2ReferenceLinkedOpenHashMap<>();
    private final DynamicPriorityQueue<ChunkPendingChanges> priorityQueue = new DynamicPriorityQueue<>(PRIORITY_COUNT);
    private final AtomicInteger packetsInProgress = new AtomicInteger(0);
    private int centerX = 0;
    private int centerZ = 0;
    private int viewDistance = 3;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        this.ctx = ctx;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof GameJoinS2CPacket packet) {
            this.viewDistance = packet.viewDistance();
            this.handleChunkChanges();
        } else if (msg instanceof ChunkLoadDistanceS2CPacket packet) {
            this.viewDistance = packet.getDistance();
            this.handleChunkChanges();
        } else if (msg instanceof ChunkRenderDistanceCenterS2CPacket packet) {
            this.centerX = packet.getChunkX();
            this.centerZ = packet.getChunkZ();
            this.handleChunkChanges();
        } else if (msg instanceof UnloadChunkS2CPacket packet) {
            final ChunkPendingChanges removed = this.chunks.remove(ChunkPos.toLong(packet.getX(), packet.getZ()));
            if (removed != null) {
                this.priorityQueue.remove(removed);
            }
            if (removed == null || !removed.isInitialPacketSent()) {
                promise.trySuccess();
                return; // no need to send unload
            }
        } else if (msg instanceof ChunkDataS2CPacket packet) {
            if (!this.isWithinViewDistance(packet.getX(), packet.getZ())) {
                System.err.println("Discarding chunk [%d, %d] since it isn't within view distance".formatted(packet.getX(), packet.getZ()));
                promise.trySuccess();
                return;
            }
            final ChunkPendingChanges chunkPendingChanges = this.getPendingChanges(packet.getX(), packet.getZ());
            chunkPendingChanges.consume(packet.getChunkData(), promise);
            chunkPendingChanges.consume(packet.getLightData(), promise);
            return;
        } else if (msg instanceof LightUpdateS2CPacket packet) {
            if (!this.isWithinViewDistance(packet.getChunkX(), packet.getChunkZ())) {
                promise.trySuccess();
                return;
            }
            final ChunkPendingChanges chunkPendingChanges = this.getPendingChanges(packet.getChunkX(), packet.getChunkZ());
            chunkPendingChanges.consume(packet.getData(), promise);
            return;
        } else if (msg instanceof ChunkDeltaUpdateS2CPacket packet) {
            final ChunkSectionPos sectionPos = ((IChunkDeltaUpdateS2CPacket) packet).getSectionPos();
            if (!this.isWithinViewDistance(sectionPos.getSectionX(), sectionPos.getSectionZ())) {
                promise.trySuccess();
                return;
            }
            final ChunkPendingChanges chunkPendingChanges = this.getPendingChanges(sectionPos.getSectionX(), sectionPos.getSectionZ());
            chunkPendingChanges.consume(packet, promise);
            return;
        } else if (msg instanceof BlockUpdateS2CPacket packet) {
            if (!this.isWithinViewDistance(packet.getPos())) {
                promise.trySuccess();
                return;
            }
            final ChunkPendingChanges chunkPendingChanges = this.getPendingChanges(packet.getPos());
            chunkPendingChanges.consume(packet.getPos(), packet.getState(), promise);
            return;
        } else if (msg instanceof BlockEntityUpdateS2CPacket packet) {
            if (!this.isWithinViewDistance(packet.getPos())) {
                promise.trySuccess();
                return;
            }
            final ChunkPendingChanges chunkPendingChanges = this.getPendingChanges(packet.getPos());
            chunkPendingChanges.consume(packet, promise);
            return;
        } else if (msg instanceof BlockEventS2CPacket packet) {
            if (!this.isWithinViewDistance(packet.getPos())) {
                promise.trySuccess();
                return;
            }
            final ChunkPendingChanges chunkPendingChanges = this.getPendingChanges(packet.getPos());
            chunkPendingChanges.consumeRaw(packet, promise);
            return;
        } else if (msg instanceof BlockBreakingProgressS2CPacket packet) {
            if (!this.isWithinViewDistance(packet.getPos())) {
                promise.trySuccess();
                return;
            }
            final ChunkPendingChanges chunkPendingChanges = this.getPendingChanges(packet.getPos());
            chunkPendingChanges.consumeRaw(packet, promise);
            return;
        } else if (msg instanceof ChunkBiomeDataS2CPacket packet) {
            ArrayList<ChunkBiomeDataS2CPacket.Serialized> newList = new ArrayList<>(packet.chunkBiomeData().size());
            final PromiseCombiner combiner = new PromiseCombiner(ctx.executor());
            for (ChunkBiomeDataS2CPacket.Serialized serialized : packet.chunkBiomeData()) {
                if (this.chunks.containsKey(serialized.pos().toLong())) {
                    newList.add(serialized);
                } else {
                    if (!this.isWithinViewDistance(serialized.pos().x, serialized.pos().z)) {
                        continue;
                    }
                    final ChunkPendingChanges chunkPendingChanges = this.getPendingChanges(serialized.pos().x, serialized.pos().z);
                    final ChannelPromise promise1 = ctx.newPromise();
                    chunkPendingChanges.consumeRaw(new ChunkBiomeDataS2CPacket(List.of(serialized)), promise1);
                    combiner.add((Future<?>) promise1);
                }
            }
            if (!newList.isEmpty()) {
                final ChannelPromise promise1 = ctx.newPromise();
                ctx.write(new ChunkBiomeDataS2CPacket(Collections.unmodifiableList(newList)), promise1);
                combiner.add((Future<?>) promise1);
            }
            combiner.finish(promise);
            return;
        }
        ctx.write(msg, promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        ctx.flush();
        this.sendPackets(ctx, false);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        ctx.fireChannelWritabilityChanged();
        if (false) {
            ctx.channel().eventLoop().submit(() -> this.sendPackets(ctx, false));
//            ctx.flush();
//            this.sendPackets(ctx, false);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == SynchronizationLayer.SYNC_REQUEST_OBJECT_DIM_CHANGE) {
            System.out.println("Discarding %d waiting chunks".formatted(this.priorityQueue.size()));
            this.packetsInProgress.set(0);
            this.priorityQueue.clear();
            this.chunks.clear();
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        this.sendPackets(ctx, true);
    }

    private void sendPackets(ChannelHandlerContext ctx, boolean ignoreWritability) {
        if (!ctx.channel().isOpen() || ctx.isRemoved()) return;
        ChunkPendingChanges changes;
        int burst = 0;
        while ((ignoreWritability || (burst ++ < MAX_CONCURRENT_PACKETS && this.isWritableToChannel(ctx))) && (changes = this.priorityQueue.peek()) != null) {
            final PacketWrapper<? extends Packet<?>> wrapper = changes.producePacket(ctx);
            if (wrapper != null) {
                ctx.write(wrapper.msg(), wrapper.promise());
                this.packetsInProgress.incrementAndGet();
                wrapper.promise().addListener(future -> this.packetsInProgress.decrementAndGet());
            } else {
                this.priorityQueue.dequeue();
            }
        }
//        if (!ctx.channel().isWritable()) {
//            System.out.println(this.priorityQueue.size());
//        }
    }

    private boolean isWritableToChannel(ChannelHandlerContext ctx) {
        return ctx.channel().isWritable() && this.packetsInProgress.get() < MAX_CONCURRENT_PACKETS;
    }

    private ChunkPendingChanges getPendingChanges(BlockPos pos) {
        return this.getPendingChanges(ChunkSectionPos.getSectionCoord(pos.getX()), ChunkSectionPos.getSectionCoord(pos.getZ()));
    }

    private ChunkPendingChanges getPendingChanges(int x, int z) {
        final ChunkPendingChanges changes = this.chunks.computeIfAbsent(ChunkPos.toLong(x, z), ChunkPendingChanges::new);
        if (!this.priorityQueue.contains(changes))
            this.priorityQueue.enqueue(changes, this.distanceToCenter(x, z));
        return changes;
    }

    private boolean isWithinViewDistance(BlockPos pos) {
        return this.isWithinViewDistance(ChunkSectionPos.getSectionCoord(pos.getX()), ChunkSectionPos.getSectionCoord(pos.getZ()));
    }

    private boolean isWithinViewDistance(int x, int z) {
        return this.distanceToCenter(x, z) <= this.viewDistance;
    }

    private void handleChunkChanges() {
        if (this.viewDistance >= PRIORITY_COUNT) {
            if (issuedWarning.compareAndSet(false, true)) {
                System.err.println("Packumulator Warning: ChunkUpdateConsolidator is not optimized to handle view distances >= 255");
            }
        }
        final ObjectBidirectionalIterator<Long2ReferenceMap.Entry<ChunkPendingChanges>> iterator = this.chunks.long2ReferenceEntrySet().fastIterator();
        LongArrayList pendingRemovals = new LongArrayList();
        while (iterator.hasNext()) {
            final Long2ReferenceMap.Entry<ChunkPendingChanges> entry = iterator.next();
            final int distance = distanceToCenter(ChunkPos.getPackedX(entry.getLongKey()), ChunkPos.getPackedZ(entry.getLongKey()));
            if (distance > this.viewDistance) {
                pendingRemovals.add(entry.getLongKey());
            } else {
                this.priorityQueue.changePriority(entry.getValue(), distance);
            }
        }
        for (long pos : pendingRemovals) {
            this.write(this.ctx, new UnloadChunkS2CPacket(ChunkPos.getPackedX(pos), ChunkPos.getPackedZ(pos)), this.ctx.newPromise());
        }
    }

    private int distanceToCenter(int x, int z) {
        final int distance = Math.max(Math.abs(x - this.centerX), Math.abs(z - this.centerZ));
        return MathHelper.clamp(distance, 0, PRIORITY_COUNT - 1);
    }

}
