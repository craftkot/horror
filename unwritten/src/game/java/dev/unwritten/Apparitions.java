package dev.unwritten;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;

/** Temporary, player-specific illusions. No server mobs or world modifications. */
final class Apparitions {
    private static final Random RNG = new Random();
    private record Ghost(int id, ServerPlayer player, Object level, Vec3 point, int age, int seen, boolean stalk) {}
    private final Map<UUID,Ghost> active = new HashMap<>();
    private int nextId = -1;
    static void soundBehind(ServerPlayer p, SoundEvent sound, float volume, float pitch) {
        double angle = Math.toRadians(p.getYRot()) + (RNG.nextDouble() - .5) * 1.4;
        double distance = 4 + RNG.nextDouble()*4;
        p.connection.send(new ClientboundSoundPacket(Holder.direct(sound), SoundSource.AMBIENT,
            p.getX()+Math.sin(angle)*distance, p.getY(), p.getZ()-Math.cos(angle)*distance, volume,pitch,RNG.nextLong()));
    }
    void show(ServerPlayer p, boolean stalk) {
        if(active.containsKey(p.getUUID())) return;
        var level=p.level();
        double yaw=Math.toRadians(p.getYRot());
        for(int i=0;i<24;i++){
            // Outside the forward hemisphere; enough room for the tall placeholder.
            double angle=yaw+(RNG.nextDouble()-.5)*Math.PI;
            double radius=14+RNG.nextDouble()*16;
            int x=(int)Math.floor(p.getX()+Math.sin(angle)*radius);
            int z=(int)Math.floor(p.getZ()-Math.cos(angle)*radius);
            if(!level.hasChunkAt(x,z))continue;
            for(int dy=3;dy>=-4;dy--){
                var block=new BlockPos(x,p.blockPosition().getY()+dy,z);
                if(!level.getBlockState(block.below()).isSolidRender() ||
                   !level.getBlockState(block).isAir() ||
                   !level.getBlockState(block.above()).isAir() ||
                   !level.getBlockState(block.above(2)).isAir())continue;
                Vec3 point=Vec3.atBottomCenterOf(block);
                float facing=(float)Math.toDegrees(Math.atan2(-(p.getX()-point.x),p.getZ()-point.z));
                int id=nextId--;
                p.connection.send(new ClientboundAddEntityPacket(id,UUID.randomUUID(),point.x,point.y,point.z,
                    0f,facing,Objects.requireNonNull(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("enderman"))),0,Vec3.ZERO,facing));
                active.put(p.getUUID(),new Ghost(id,p,level,point,0,0,stalk));
                return;
            }
        }
    }
    void tick(List<ServerPlayer> players, ChaseEncounter chases) {
        if(active.isEmpty())return;
        var it=active.entrySet().iterator();
        while(it.hasNext()){
            var e=it.next();var g=e.getValue();var p=g.player();
            if(!players.contains(p)){it.remove();continue;}
            if(p.level()!=g.level()){it.remove();continue;}
            var target=g.point().add(0,1.5,0);
            var delta=target.subtract(p.getEyePosition());
            double distance=delta.lengthSqr();
            var pos=BlockPos.containing(g.point());
            if(!p.level().hasChunkAt(pos.getX(),pos.getZ())){
                p.connection.send(new ClientboundRemoveEntitiesPacket(g.id()));
                it.remove();continue;
            }
            boolean looking=distance>0 && p.getLookAngle().dot(delta.normalize())>.96;
            boolean visible=(looking||distance<=64) && p.level().clip(new ClipContext(p.getEyePosition(),target,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p)).getType()==HitResult.Type.MISS;
            int seen=looking&&visible?g.seen()+1:0;
            var action=WatcherPolicy.decide(g.age(),distance,seen,visible,g.stalk(),p.isAlive()&&!p.isCreative()&&!p.isSpectator());
            if(action!=WatcherPolicy.Action.WAIT){
                p.connection.send(new ClientboundRemoveEntitiesPacket(g.id()));
                it.remove();
                if(action==WatcherPolicy.Action.CLOSE || action==WatcherPolicy.Action.SPOTTED){
                    boolean close=action==WatcherPolicy.Action.CLOSE;
                    if(RNG.nextInt(3)==0)chases.start(p,g.point());
                    var effect=BuiltInRegistries.MOB_EFFECT.getOrThrow(ResourceKey.create(Registries.MOB_EFFECT,Identifier.withDefaultNamespace(close?"blindness":"darkness")));
                    if(!p.hasEffect(effect))p.addEffect(new MobEffectInstance(effect,close?100:60,0,false,false));
                    p.connection.send(new ClientboundSoundPacket(Holder.direct(SoundEvents.AMBIENT_CAVE.value()),SoundSource.AMBIENT,g.point().x,g.point().y,g.point().z,.7f,.65f,RNG.nextLong()));
                }
            }else e.setValue(new Ghost(g.id(),p,g.level(),g.point(),g.age()+1,seen,g.stalk()));
        }
    }
    boolean hasWatcher(ServerPlayer p){var g=active.get(p.getUUID());return g!=null&&g.player()==p&&g.level()==p.level();}
    void clear(){active.clear();nextId=-1;}
}
