package com.ishland.packumulator.common.s2c;

import io.netty.channel.ChannelPromise;

import java.util.Objects;

public class FutureUtil {

    public static void chainPromises(ChannelPromise src, ChannelPromise... dst) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(dst, "dst");
        for (ChannelPromise promise : dst) {
            Objects.requireNonNull(promise, "dst");
        }
        src.addListener(future -> {
            if (future.isSuccess()) {
                for (ChannelPromise promise : dst) {
                    promise.trySuccess();
                }
            } else {
                for (ChannelPromise promise : dst) {
                    promise.tryFailure(future.cause());
                }
            }
        });
    }

}
