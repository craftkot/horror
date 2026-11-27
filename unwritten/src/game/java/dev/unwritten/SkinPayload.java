package dev.unwritten;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
public record SkinPayload(UUID alias,UUID source) implements CustomPacketPayload {
 public static final Type<SkinPayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("unwritten","skin_link"));
 public static final StreamCodec<RegistryFriendlyByteBuf,SkinPayload> CODEC=StreamCodec.of((buf,value)->{buf.writeUUID(value.alias());buf.writeUUID(value.source());},buf->new SkinPayload(buf.readUUID(),buf.readUUID()));
 public Type<SkinPayload> type(){return TYPE;}
 static void register(RegisterPayloadHandlersEvent event){event.registrar("1").playToClient(TYPE,CODEC);}
}
