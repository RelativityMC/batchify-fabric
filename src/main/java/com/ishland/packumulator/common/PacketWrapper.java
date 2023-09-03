package com.ishland.packumulator.common;

import io.netty.channel.ChannelPromise;

public record PacketWrapper<T>(T msg, ChannelPromise promise) {
}
