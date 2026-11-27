package dev.unwritten;
import java.util.UUID;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
public record TabPayload(UUID target,boolean begin,boolean announce) implements CustomPacketPayload {
 public static final Type<TabPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("unwritten","false_leave"));
 public static final StreamCodec<RegistryFriendlyByteBuf,TabPayload> CODEC=StreamCodec.of((buf,value)->{buf.writeUUID(value.target());buf.writeBoolean(value.begin());buf.writeBoolean(value.announce());},buf->new TabPayload(buf.readUUID(),buf.readBoolean(),buf.readBoolean()));
 public Type<TabPayload> type(){return TYPE;}
 static void register(RegisterPayloadHandlersEvent event){event.registrar("1").playToClient(TYPE,CODEC);}
}
