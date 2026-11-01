package com.blackfrequency.rift;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

public final class RiftNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("blackfrequency", "main"), () -> VERSION,
        VERSION::equals, VERSION::equals);
    private static boolean registered;

    private RiftNetwork() {}
    public static void register() {
        if (registered) return;
        registered = true;
        CHANNEL.messageBuilder(RiftStatePacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
            .encoder((p, buf) -> { buf.writeFloat(p.progress); buf.writeBoolean(p.collapse); buf.writeVarInt(p.finaleTicks); })
            .decoder(buf -> new RiftStatePacket(buf.readFloat(), buf.readBoolean(), buf.readVarInt()))
            .consumerMainThread(RiftNetwork::receive)
            .add();
    }
    public static void send(ServerPlayer player, float progress, boolean collapse, int finaleTicks) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new RiftStatePacket(progress, collapse, finaleTicks));
    }
    private static void receive(RiftStatePacket packet, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            com.blackfrequency.rift.client.RiftClientState.update(packet.progress, packet.collapse, packet.finaleTicks));
        ctx.get().setPacketHandled(true);
    }
    public static final class RiftStatePacket {
        public final float progress;
        public final boolean collapse;
        public final int finaleTicks;
        public RiftStatePacket(float progress, boolean collapse, int finaleTicks) {
            this.progress = progress;
            this.collapse = collapse;
            this.finaleTicks = finaleTicks;
        }
    }
}
