#!/usr/bin/env python3
"""v0.18 battle overlay after v0.18 ritual note, before compile."""
from pathlib import Path
ROOT=Path("black-frequency/project")
JAVA=ROOT/"src/main/java/com/blackfrequency/hollowsignal"

def edit(rel,old,new):
    p=JAVA/rel
    s=p.read_text(encoding="utf-8")
    assert s.count(old)==1,(rel,old[:90],s.count(old))
    p.write_text(s.replace(old,new,1),encoding="utf-8")

def fresh(rel,source):
    p=JAVA/rel
    p.parent.mkdir(parents=True,exist_ok=True)
    assert not p.exists(),str(p)
    p.write_text(source.strip()+"\n",encoding="utf-8")

fresh("world/RitualBattle.java",r'''
package com.blackfrequency.hollowsignal.world;

import com.blackfrequency.hollowsignal.registry.RitualItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;

/**
 * Server-authoritative boss battle prototype. Enderman is a temporary visual
 * placeholder for the future original rift entity. 5% HP = one charge shard;
 * five shards = next beacon shot; attacks escalate at 75, 50, 25% HP.
 * A final shot AFTER victory seals the remaining world rupture.
 */
public final class RitualBattle {
    public static final float MAX_HP=200.0f;
    private static final String BOSS_TAG="hollowsignal_ritual_boss";
    private static final int ARENA_RADIUS=23;

    public static void start(ServerLevel level,RitualData d,BlockPos pos) {
        if(!d.fightActive || d.shots!=0) return;
        EnderMan boss=EntityType.ENDERMAN.create(level);
        if(boss==null) return;
        var max=boss.getAttribute(Attributes.MAX_HEALTH);
        if(max!=null) max.setBaseValue(MAX_HP);
        boss.setHealth(MAX_HP);
        boss.setPersistenceRequired();
        boss.setCustomName(Component.literal("ОСКОЛОК РАЗРЫВА").withStyle(ChatFormatting.DARK_PURPLE));
        boss.setCustomNameVisible(true);
        boss.addTag(BOSS_TAG);
        BlockPos spot=pos.offset(11,1,0);
        if(!level.hasChunkAt(spot)) return;
        boss.moveTo(spot.getX()+.5,spot.getY(),spot.getZ()+.5,0,0);
        if(!level.addFreshEntity(boss)) return;
        d.bossId=boss.getUUID();
        d.bossDefeated=false;
        d.setDirty();
        shoot(level,d,pos);
        for(ServerPlayer p:level.players()) {
            if(p.distanceToSqr(pos.getX()+.5,pos.getY(),pos.getZ()+.5)<100*100)
                p.sendSystemMessage(Component.literal("Разломы начинают закрываться. Что-то атакует маяк!")
                        .withStyle(ChatFormatting.DARK_PURPLE));
        }
    }

    /** Triggered by inserting the fifth drop or by the four starter End Crystals. */
    public static void shoot(ServerLevel level,RitualData d,BlockPos pos) {
        if(!d.fightActive) return;
        if(d.shots>0 && d.recharge<5) return;
        if(d.shots>0 && !RitualSystem.validBase(level,pos)) return;
        if(d.shots>0 && !d.bossDefeated) {
            if(d.phase>=3) return;
            if(d.bossId==null) return;
            Entity entity=level.getEntity(d.bossId);
            float floor=MAX_HP*(3-d.phase)/4f;
            if(!(entity instanceof EnderMan boss) || boss.getHealth()>floor+0.05f) return;
            d.phase++; // Every recharged shot begins a harder 25% band.
        }
        d.shots++;
        d.recharge=0;
        d.lastShot=level.getGameTime();
        d.setDirty();
        // No full /rupture reset yet: each ray closes only a bounded fraction.
        PsychologyData mind=PsychologyData.get(level.getServer());
        mind.sealSome(level.getServer(),Math.max(1,(mind.cracks.size()+3)/4));
        level.sendParticles(ParticleTypes.END_ROD,pos.getX()+.5,pos.getY()+2,pos.getZ()+.5,
                110,1,15,1,.2);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL,pos.getX()+.5,pos.getY()+16,pos.getZ()+.5,
                65,2,10,2,.11);
        level.playSound(null,pos,SoundEvents.BEACON_ACTIVATE,SoundSource.BLOCKS,1.7f,.74f);
        if(d.bossDefeated) {
            int restored=mind.restore(level.getServer());
            d.fightActive=false;
            d.ritualFinished=true;
            d.setDirty();
            for(ServerPlayer p:level.players()) p.sendSystemMessage(Component.literal(
                    "Разломы затягиваются. Восстановлено блоков: "+restored
                    + ". Не загруженные участки остаются в журнале восстановления.")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            for(ServerPlayer p:level.players()) {
                if(p.distanceToSqr(pos.getX()+.5,pos.getY(),pos.getZ()+.5)<100*100)
                    p.sendSystemMessage(Component.literal("Луч маяка: выстрел "+d.shots
                            + " • концентрация сущности "+(d.phase+1)+"/4")
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        }
    }

    /** Caps health by the unlocked quarter: boss cannot skip shard cycles. */
    @SubscribeEvent public void hurt(LivingHurtEvent e) {
        if(!(e.getEntity().level() instanceof ServerLevel level)
                || !e.getEntity().getTags().contains(BOSS_TAG)) return;
        RitualData d=RitualData.get(level.getServer());
        if(!d.fightActive || d.bossId==null || !d.bossId.equals(e.getEntity().getUUID())) return;
        float floor=MAX_HP*(3-d.phase)/4f;
        e.setAmount(Math.max(0,Math.min(e.getAmount(),e.getEntity().getHealth()-floor)));
    }

    /** Item drops appear around mini-rift flashes for EACH completed 5% HP loss. */
    @SubscribeEvent public void damaged(LivingDamageEvent e) {
        if(!(e.getEntity().level() instanceof ServerLevel level)
                || !e.getEntity().getTags().contains(BOSS_TAG)) return;
        RitualData d=RitualData.get(level.getServer());
        if(!d.fightActive || d.bossId==null || !d.bossId.equals(e.getEntity().getUUID())) return;
        float after=Math.max(0,e.getEntity().getHealth()-e.getAmount());
        d.bossHealth=Math.round(after);
        int deserved=Math.min(20,(int)Math.floor((MAX_HP-after+0.001f)/(MAX_HP*.05f)));
        while(d.shardsIssued<deserved) {
            d.shardsIssued++;
            double x=e.getEntity().getX()+(level.random.nextFloat()-.5)*9;
            double y=e.getEntity().getY()+.8;
            double z=e.getEntity().getZ()+(level.random.nextFloat()-.5)*9;
            ItemEntity shard=new ItemEntity(level,x,y,z,
                    new ItemStack(RitualItems.RIFT_FRAGMENT.get()));
            shard.setPickUpDelay(15);
            level.addFreshEntity(shard);
            level.sendParticles(ParticleTypes.REVERSE_PORTAL,x,y,z,28,.5,.7,.5,.09);
        }
        d.setDirty();
    }

    @SubscribeEvent public void defeated(LivingDeathEvent e) {
        if(!(e.getEntity().level() instanceof ServerLevel level)
                || !e.getEntity().getTags().contains(BOSS_TAG)) return;
        RitualData d=RitualData.get(level.getServer());
        if(!d.fightActive || d.bossId==null || !d.bossId.equals(e.getEntity().getUUID())) return;
        d.bossDefeated=true;
        d.bossHealth=0;
        d.setDirty();
        for(ServerPlayer p:level.players())
            p.sendSystemMessage(Component.literal(
                    "Сущность распалась. Вставьте последние пять осколков в маяк, чтобы завершить восстановление.")
                    .withStyle(ChatFormatting.AQUA));
    }

    @SubscribeEvent public void onJoin(EntityJoinLevelEvent e) {
        if(!(e.getLevel() instanceof ServerLevel level)
                || !e.getEntity().getTags().contains(BOSS_TAG)) return;
        // Entities tagged during spawn are safe; old unowned entities never persist.
        RitualData d=RitualData.get(level.getServer());
        if(!d.fightActive || d.bossId==null || !d.bossId.equals(e.getEntity().getUUID()))
            e.setCanceled(true);
    }

    @SubscribeEvent public void tick(TickEvent.ServerTickEvent e) {
        if(e.phase!=TickEvent.Phase.END) return;
        MinecraftServer server=ServerLifecycleHooks.getCurrentServer();
        if(server==null) return;
        ServerLevel level=server.overworld();
        long now=level.getGameTime();
        if(now%20!=0) return;
        RitualData d=RitualData.get(server);
        if(!d.fightActive) return;
        if(!PsychologyData.get(server).ruptureActive()) {
            if(d.bossId!=null) {
                Entity stray=level.getEntity(d.bossId);
                if(stray!=null) stray.discard();
            }
            d.fightActive=false;
            d.setDirty();
            return;
        }
        BlockPos center=BlockPos.of(d.beaconPosition);
        if(!level.hasChunkAt(center)) return;
        double cx=center.getX()+.5,cy=center.getY()+1,cz=center.getZ()+.5;
        for(int i=0;i<48;i++) {
            double angle=Math.PI*2*i/48;
            level.sendParticles(ParticleTypes.END_ROD,
                    cx+Math.cos(angle)*ARENA_RADIUS,cy+1,cz+Math.sin(angle)*ARENA_RADIUS,
                    1,0,.7,0,.006);
        }
        if(d.bossDefeated || d.bossId==null) return;
        Entity entity=level.getEntity(d.bossId);
        if(!(entity instanceof EnderMan boss) || !boss.isAlive()) return;
        d.bossHealth=Math.round(boss.getHealth());
        if(boss.distanceToSqr(cx,cy,cz)>ARENA_RADIUS*ARENA_RADIUS) {
            boss.teleportTo(cx+11,cy,cz);
        }
        if(now%100!=0) return;
        List<ServerPlayer> players=level.players().stream()
                .filter(p->p.isAlive() && !p.isCreative() && !p.isSpectator()
                        && p.distanceToSqr(cx,cy,cz)<ARENA_RADIUS*ARENA_RADIUS)
                .toList();
        if(players.isEmpty()) return;
        ServerPlayer target=players.get(level.random.nextInt(players.size()));
        int phase=d.phase;
        if(phase>=1) {
            level.sendParticles(ParticleTypes.SOUL,target.getX(),target.getY()+1,
                    target.getZ(),18,.8,1,.8,.04);
            target.addEffect(new MobEffectInstance(MobEffects.DARKNESS,30+phase*15,0,false,false));
        }
        if(phase>=2) {
            target.hurt(target.damageSources().magic(),Math.min(4,phase));
            level.playSound(null,target.blockPosition(),SoundEvents.WARDEN_SONIC_BOOM,
                    SoundSource.HOSTILE,.6f,.85f);
        }
        if(phase>=3 && now%200==0) {
            int nearby=level.getEntitiesOfClass(Endermite.class,new AABB(center).inflate(20)).size();
            if(nearby<4) {
                Endermite minion=EntityType.ENDERMITE.create(level);
                if(minion!=null) {
                    minion.moveTo(target.getX()+3,target.getY(),target.getZ()+3,0,0);
                    minion.addTag("hollowsignal_rupture");
                    level.addFreshEntity(minion);
                }
            }
        }
        d.setDirty();
    }
}
''')

edit("world/RitualData.java",
     '    public boolean ritualFinished;\n',
     '    public boolean ritualFinished;\n    public boolean bossDefeated;\n')
edit("world/RitualData.java",
     '        data.ritualFinished=nbt.getBoolean("ritualFinished");\n',
     '        data.ritualFinished=nbt.getBoolean("ritualFinished");\n'
     '        data.bossDefeated=nbt.getBoolean("bossDefeated");\n')
edit("world/RitualData.java",
     '        nbt.putBoolean("ritualFinished",ritualFinished);\n',
     '        nbt.putBoolean("ritualFinished",ritualFinished);\n'
     '        nbt.putBoolean("bossDefeated",bossDefeated);\n')
edit("world/RitualSystem.java",
     '        d.fightActive=true;\n',
     '        d.fightActive=true;\n        d.bossDefeated=false;\n')

edit("world/PsychologyData.java",
     '    public boolean ruptureActive() { return ruptureHost != null; }',
     '''    /** Incremental bounded crack closure: does not terminate the shared rupture. */
    public int sealSome(MinecraftServer server,int budget) {
        int fixed=0;
        var iterator=cracks.entrySet().iterator();
        while(iterator.hasNext() && fixed<budget) {
            var entry=iterator.next();
            try {
                String[] parts=entry.getKey().split(";",2);
                var dimension=net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        new net.minecraft.resources.ResourceLocation(parts[0]));
                var level=server.getLevel(dimension);
                if(level==null) continue;
                var pos=net.minecraft.core.BlockPos.of(Long.parseLong(parts[1]));
                if(!level.hasChunkAt(pos)) continue;
                if(!level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.SCULK)) {
                    iterator.remove(); continue;
                }
                var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                        new net.minecraft.resources.ResourceLocation(entry.getValue()));
                if(level.setBlock(pos,block.defaultBlockState(),3)) {
                    fixed++;
                    iterator.remove();
                }
            } catch(RuntimeException bad) {
                iterator.remove();
            }
        }
        if(fixed>0) setDirty();
        return fixed;
    }
    public boolean ruptureActive() { return ruptureHost != null; }''')
print("PASS v0.18 battle: boss 200HP, 5 percent shards, 25 percent phase locks, final 5-shard shot, bounded crack closure")
