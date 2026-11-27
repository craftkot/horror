package dev.unwritten;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
public record SocialPayload() implements CustomPacketPayload {
 public static final SocialPayload INSTANCE=new SocialPayload();
 public static final Type<SocialPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("unwritten","fake_kick"));
 public static final StreamCodec<RegistryFriendlyByteBuf,SocialPayload> CODEC=StreamCodec.unit(INSTANCE);
 public Type<SocialPayload> type(){return TYPE;}
 static void register(RegisterPayloadHandlersEvent event){event.registrar("1").playToClient(TYPE,CODEC);}
}
