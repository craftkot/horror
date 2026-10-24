#!/usr/bin/env python3
"""BLACK FREQUENCY v0.19: day-three dreams and independent psyche/morality roles.
Layer on top of the complete v0.18.0 restored sources, preserving all mechanics.
"""
from pathlib import Path
ROOT=Path("black-frequency/project")
JAVA=ROOT/"src/main/java/com/blackfrequency/hollowsignal"

def edit(relative, old, new):
    p=JAVA/relative
    src=p.read_text(encoding="utf-8")
    matches=src.count(old)
    assert matches==1,(relative,matches,old[:100])
    p.write_text(src.replace(old,new,1),encoding="utf-8")

def fresh(relative, contents):
    p=JAVA/relative
    p.parent.mkdir(parents=True,exist_ok=True)
    assert not p.exists(),p
    p.write_text(contents.strip()+"\n",encoding="utf-8")

fresh("network/DreamPacket.java",r'''
package com.blackfrequency.hollowsignal.network;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.blackfrequency.hollowsignal.client.ClientDream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;
import java.util.function.Supplier;

/** Server-only dream control, each packet goes to the dreamer's client only. */
public record DreamPacket(int event, int kind, int duration, int morality) {
    // event: 0 begin, 1 calm finish, 2 nightmare jumpscare, 3 cancel
    // kind: 0 peaceful, 1 nightmare, 2 uneasy
    private static final String VERSION="1";
    private static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BlackFrequency.MODID,"dream"), ()->VERSION,
            VERSION::equals, VERSION::equals);
    public static void register() {
        CHANNEL.registerMessage(0,DreamPacket.class,
                (msg,b)->{
                    b.writeVarInt(msg.event());
                    b.writeVarInt(msg.kind());
                    b.writeVarInt(msg.duration());
                    b.writeVarInt(msg.morality());
                },
                b->new DreamPacket(b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt()),
                DreamPacket::receive, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    private static void receive(DreamPacket packet,Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                ()->()->ClientDream.receive(packet.event(),packet.kind(),
                        packet.duration(),packet.morality())));
        ctx.get().setPacketHandled(true);
    }
    public static void send(ServerPlayer player,int event,int kind,int ticks,int morality) {
        CHANNEL.send(PacketDistributor.PLAYER.with(()->player),
                new DreamPacket(event,kind,ticks,morality));
    }
}
''')

fresh("world/DreamSystem.java",r'''
package com.blackfrequency.hollowsignal.world;

import com.blackfrequency.hollowsignal.HorrorConfig;
import com.blackfrequency.hollowsignal.network.DreamPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Snapshot the TWO independent stats at sleep onset:
 *  SANITY sets dream instability / nightmare risk.
 *  MORALITY changes the dream's intent (comfort, guilt, or malice).
 *  Sleep never edits morality: it is earned/lost by moral actions elsewhere.
 *
 * First dream: third WORLD day, dayTime >= 48000, including skipped nights.
 * 200 real server ticks of calm dream regardless of vanilla sleep acceleration.
 * Multiplayer-safe: target-client packets only and one dream per player/day.
 */
public final class DreamSystem {
    public static final int CALM=0, NIGHTMARE=1, UNEASY=2;
    public static final int CALM_TICKS=200;
    public static final int UNEASY_TICKS=130;
    public static final int NIGHTMARE_TICKS=65;
    private final Map<UUID,Dream> active = new HashMap<>();

    private static final class Dream {
        final int kind, sanityAtSleep, moralityAtSleep;
        final long begun;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        Dream(ServerPlayer p, int kind,int sanity,int morality,long began) {
            this.kind=kind;
            sanityAtSleep=sanity;
            moralityAtSleep=morality;
            begun=began;
            dimension=p.level().dimension();
        }
    }

    /** User can predict category from both bars, not from an arbitrary reset. */
    public static int classify(int sanity,int morality) {
        if(sanity>=65 && morality>=65) return CALM;
        if(sanity<=22 || (sanity<=40 && morality<=40)) return NIGHTMARE;
        return UNEASY;
    }

    @SubscribeEvent
    public void playerTick(TickEvent.PlayerTickEvent e) {
        if(e.phase!=TickEvent.Phase.END || !(e.player instanceof ServerPlayer p)) return;
        UUID id=p.getUUID();
        Dream dream=active.get(id);
        // End or cancel already-created dream even if master switch is disabled.
        if(dream!=null) {
            if(!p.isAlive() || !HorrorConfig.ENABLED.get() || !HorrorConfig.PSYCHOLOGY_SYSTEM.get()
                    || p.level().dimension()!=dream.dimension) {
                cancel(p);
                return;
            }
            long ticks=p.getServer().overworld().getGameTime()-dream.begun;
            if(dream.kind==NIGHTMARE && (ticks>=NIGHTMARE_TICKS || (!p.isSleeping() && ticks>0))) {
                nightmareWake(p,dream);
            } else if(dream.kind==CALM && ticks>=CALM_TICKS) {
                finishCalm(p,dream);
            } else if(dream.kind==UNEASY && ticks>=UNEASY_TICKS) {
                finishUneasy(p,dream);
            }
            return;
        }
        if(!p.isSleeping() || !p.isAlive() || p.isCreative() || p.isSpectator()
                || !HorrorConfig.ENABLED.get() || !HorrorConfig.PSYCHOLOGY_SYSTEM.get()) return;
        if(p.getServer()==null || p.getServer().overworld()==null) return;
        long day=p.getServer().overworld().getDayTime()/24000L+1;
        if(day<3) return;
        PsychologyData data=PsychologyData.get(p.getServer());
        if(data.ruptureActive() && data.ruptureStage==0 && id.equals(data.ruptureHost)) return;
        PsychologyData.Mind mind=data.mind(p);
        if(mind.lastDreamDay>=day) return;
        int sanity=mind.sanity, morality=mind.morality;
        int kind=classify(sanity,morality);
        mind.lastDreamDay=day;
        data.setDirty();
        long now=p.getServer().overworld().getGameTime();
        active.put(id,new Dream(p,kind,sanity,morality,now));
        DreamPacket.send(p,0,kind,
                kind==CALM?CALM_TICKS:kind==NIGHTMARE?NIGHTMARE_TICKS:UNEASY_TICKS,morality);
    }

    @SubscribeEvent
    public void waking(net.minecraftforge.event.entity.player.PlayerWakeUpEvent event) {
        if(!(event.getEntity() instanceof ServerPlayer player)) return;
        Dream dream=active.get(player.getUUID());
        if(dream!=null && dream.kind==NIGHTMARE)
            nightmareWake(player,dream);
        // Calm dreams continue to complete their 10 real seconds even if
        // vanilla/multiplayer advances the night and automatically wakes player.
    }

    private void nightmareWake(ServerPlayer p,Dream dream) {
        if(active.remove(p.getUUID())!=dream) return;
        if(p.isSleeping()) p.stopSleepInBed(true,true);
        // 1 food point = precisely HALF of one hunger icon, saturation reset.
        p.getFoodData().setFoodLevel(1);
        p.getFoodData().setSaturation(0f);
        PsychologyData.get(p.getServer()).change(p,-4,0);
        DreamPacket.send(p,2,NIGHTMARE,26,dream.moralityAtSleep);
        p.playNotifySound(SoundEvents.WARDEN_SONIC_BOOM,SoundSource.HOSTILE,.8f,.72f);
        p.sendSystemMessage(Component.literal("Кошмар оборвался. Голод: 1/20.")
                .withStyle(ChatFormatting.DARK_RED));
    }

    private void finishCalm(ServerPlayer p,Dream dream) {
        if(active.remove(p.getUUID())!=dream) return;
        DreamPacket.send(p,1,CALM,28,dream.moralityAtSleep);
        PsychologyData.get(p.getServer()).change(p,5,0);
        // Good morality colors the shadow; the buff is a real reward for rest.
        int roll=p.getRandom().nextInt(4);
        switch(roll) {
            case 0 -> p.addEffect(new MobEffectInstance(MobEffects.REGENERATION,20*35,0,false,true));
            case 1 -> p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE,20*95,0,false,true));
            case 2 -> p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,20*150,0,false,true));
            default -> p.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED,20*150,0,false,true));
        }
        p.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME,SoundSource.PLAYERS,.5f,1.1f);
        p.displayClientMessage(Component.literal("Тихая тень исчезла. Психика +5; тело отдохнуло.")
                .withStyle(ChatFormatting.GREEN),true);
    }

    private void finishUneasy(ServerPlayer p,Dream dream) {
        if(active.remove(p.getUUID())!=dream) return;
        DreamPacket.send(p,1,UNEASY,18,dream.moralityAtSleep);
        if(dream.moralityAtSleep>=65) {
            PsychologyData.get(p.getServer()).change(p,2,0);
            p.displayClientMessage(Component.literal("Добрая тень помогла удержать тревогу. Психика +2.")
                    .withStyle(ChatFormatting.GRAY),true);
        } else if(dream.moralityAtSleep<=30) {
            p.displayClientMessage(Component.literal("Чувство вины не даёт спокойно уснуть.")
                    .withStyle(ChatFormatting.DARK_GRAY),true);
        }
    }

    private void cancel(ServerPlayer p) {
        Dream old=active.remove(p.getUUID());
        if(old!=null) DreamPacket.send(p,3,old.kind,0,old.moralityAtSleep);
    }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if(e.getEntity() instanceof ServerPlayer p) active.remove(p.getUUID());
    }
    @SubscribeEvent public void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if(e.getEntity() instanceof ServerPlayer p) cancel(p);
    }
    @SubscribeEvent public void respawn(PlayerEvent.PlayerRespawnEvent e) {
        if(e.getEntity() instanceof ServerPlayer p) cancel(p);
    }
}
''')

fresh("client/ClientDream.java",r'''
package com.blackfrequency.hollowsignal.client;

import com.blackfrequency.hollowsignal.BlackFrequency;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** First-person noninteractive visions, never fake OS UI or steal input. */
@Mod.EventBusSubscriber(modid=BlackFrequency.MODID,bus=Mod.EventBusSubscriber.Bus.FORGE,value=Dist.CLIENT)
public final class ClientDream {
    private static int active=-1, age, duration, finish=0, morality;
    private static int eventType;
    public static void receive(int event,int dream,int ticks,int atSleepMorality) {
        if(event==0) {
            active=dream;
            duration=Math.max(1,ticks);
            age=0;
            finish=0;
            eventType=0;
            morality=atSleepMorality;
        } else if(event==3) clear();
        else {
            // Only actual server-generated results count as awakening outcomes.
            active=dream;
            age=0;
            duration=Math.max(1,ticks);
            finish=ticks;
            eventType=event;
            morality=atSleepMorality;
        }
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e) {
        if(e.phase!=TickEvent.Phase.END || active<0) return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null) { clear(); return; }
        if(mc.isPaused()) return;
        age++;
        if(eventType!=0 && --finish<=0) { clear(); return; }
        if(eventType==0 && age>duration+75) clear(); // Lost finish-packet failsafe.
    }
    @SubscribeEvent public static void onHud(RenderGuiEvent.Post e) {
        if(Minecraft.getInstance().screen==null)
            paint(e.getGuiGraphics(),e.getWindow().getGuiScaledWidth(),e.getWindow().getGuiScaledHeight());
    }
    @SubscribeEvent public static void onScreen(ScreenEvent.Render.Post e) {
        if(Minecraft.getInstance().screen==e.getScreen())
            paint(e.getGuiGraphics(),Minecraft.getInstance().getWindow().getGuiScaledWidth(),
                    Minecraft.getInstance().getWindow().getGuiScaledHeight());
    }
    private static void paint(GuiGraphics g,int w,int h) {
        if(active<0 || Minecraft.getInstance().player==null) return;
        int mid=w/2, centerY=h/2;
        boolean nightmare=active==1;
        boolean fear=eventType==2;
        int shade=nightmare?0xC51D0928:(morality>=65?0xA81A252B:0xBD24202A);
        g.fill(0,0,w,h,shade);
        int sway=(age/7)%9-4;
        int x=mid+sway, y=centerY-52;
        // Two staggered silhouettes, no actual world entity or motion hacking.
        g.fill(x-21,y,x+21,y+46,nightmare?0xF6000108:0xCC293139);
        g.fill(x-31,y+47,x+31,y+109,nightmare?0xE5000208:0xAB333D47);
        g.fill(x-50,y+51,x-31,y+103,nightmare?0xCA050207:0x803C4850);
        g.fill(x+31,y+51,x+50,y+103,nightmare?0xCA050207:0x803C4850);
        if(nightmare) {
            int phase=(age/4)%6;
            g.fill(x-14,y+19,x-6,y+21+phase,0xFFFF244E);
            g.fill(x+6,y+19,x+14,y+21+phase,0xFFFF244E);
            for(int i=0;i<17;i++) {
                int glitchX=(i*87+age*13)%Math.max(1,w);
                int glitchY=(i*61+age*7)%Math.max(1,h);
                g.fill(glitchX,glitchY,glitchX+15+i%5*7,glitchY+2,0x8AFF1550);
            }
            if(fear) {
                g.fill(0,0,w,h,(age%4<2)?0xA4F20035:0xB7000003);
                g.drawCenteredString(Minecraft.getInstance().font,"ПРОСНИСЬ!",
                        mid,centerY+91,0xFFFFBFC8);
            } else
                g.drawCenteredString(Minecraft.getInstance().font,
                        "ОН ТЕБЯ ВИДИТ",mid,centerY+91,0xFFDB8A9E);
        } else if(active==0) {
            int band=Math.min(w/3,(age%80)*4);
            g.fill(mid-band,centerY+131,mid+band,centerY+134,0x6FA3D7B7);
            g.drawCenteredString(Minecraft.getInstance().font,
                    "ТИХАЯ ТЕНЬ",mid,centerY+91,0xFFE1F2DC);
            if(eventType==0)
                g.drawCenteredString(Minecraft.getInstance().font,
                        "СПОКОЙНЫЙ СОН • "+Math.max(0,(duration-age+19)/20)+" с",
                        mid,centerY+114,0xFFB4CFBD);
        } else {
            String message=morality>=65?"ТЕНЬ ЗАЩИЩАЕТ ТЕБЯ":
                    morality<=30?"ТЕБЯ ПРЕСЛЕДУЕТ ВИНА":"НЕЯСНЫЙ СОН";
            g.drawCenteredString(Minecraft.getInstance().font,
                    message,mid,centerY+91,0xFFBBB9D4);
        }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e) { clear(); }
    private static void clear() {
        active=-1; age=0; duration=0; finish=0; eventType=0; morality=0;
    }
    private ClientDream() {}
}
''')

# Persist dream day per player, do not permit relog to farm 10-second buffs.
edit("world/PsychologyData.java",
     '        public long lastMessage;\n',
     '        public long lastMessage;\n        public long lastDreamDay = -1;\n')
edit("world/PsychologyData.java",
     '            m.failedQte = t.getInt("failures");\n',
     '            m.failedQte = t.getInt("failures");\n'
     '            m.lastDreamDay = t.contains("lastDreamDay") ? t.getLong("lastDreamDay") : -1;\n')
edit("world/PsychologyData.java",
     '            t.putInt("failures", m.failedQte);\n',
     '            t.putInt("failures", m.failedQte);\n'
     '            t.putLong("lastDreamDay", m.lastDreamDay);\n')

edit("BlackFrequency.java",
     'import com.blackfrequency.hollowsignal.network.FragmentNotePacket;\n',
     'import com.blackfrequency.hollowsignal.network.FragmentNotePacket;\n'
     'import com.blackfrequency.hollowsignal.network.DreamPacket;\n')
edit("BlackFrequency.java",
     '        FragmentNotePacket.register();\n',
     '        FragmentNotePacket.register();\n        DreamPacket.register();\n')
edit("BlackFrequency.java",
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.RitualBattle());\n',
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.RitualBattle());\n'
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.DreamSystem());\n')

edit("command/HorrorCommands.java",
     '                .then(Commands.literal("ritual")',
     '''                .then(Commands.literal("dream")
                        .then(Commands.literal("status").executes(ctx -> {
                            var p=ctx.getSource().getPlayerOrException();
                            var m=com.blackfrequency.hollowsignal.world.PsychologyData.get(p.getServer()).mind(p);
                            long day=p.getServer().overworld().getDayTime()/24000L+1;
                            int kind=com.blackfrequency.hollowsignal.world.DreamSystem.classify(
                                    m.sanity,m.morality);
                            ctx.getSource().sendSuccess(()->Component.literal(
                                    "День мира: "+day+" | сны с дня 3 | психика: "+
                                    m.sanity+" | мораль: "+m.morality+
                                    " | тип: "+(kind==0?"спокойный":kind==1?"кошмар":"тревожный")+
                                    " | последний сон: "+m.lastDreamDay),false);
                            return 1;
                        })))
                .then(Commands.literal("ritual")''')

props=ROOT/"gradle.properties"
text=props.read_text(encoding="utf-8")
assert text.count("0.18.0")==1
props.write_text(text.replace("0.18.0","0.19.0"),encoding="utf-8")
print("PASS v0.19: dreams from day 3, bed-time snapshots, distinct sanity/morality, 10s rest, 1/20 food")
