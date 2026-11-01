#!/usr/bin/env python3
"""v0.24: translucent smoky silhouette + private full-screen whisper HUD.
Applied AFTER v0.23 on the restored Forge 1.20.1 project.
"""
from pathlib import Path
import math,random,struct,zlib
ROOT=Path("black-frequency/project")
JAVA=ROOT/"src/main/java/com/blackfrequency/hollowsignal"
ASSETS=ROOT/"src/main/resources/assets/hollowsignal"

def edit(rel,old,new):
    p=JAVA/rel
    text=p.read_text(encoding="utf-8")
    assert text.count(old)==1,(rel,old[:80],text.count(old))
    p.write_text(text.replace(old,new,1),encoding="utf-8")
def write(rel,content):
    p=JAVA/rel
    p.parent.mkdir(parents=True,exist_ok=True)
    assert not p.exists(),p
    p.write_text(content.strip()+"\n",encoding="utf-8")

# Smoke skins: four visual phases; fine dither, translucent wisps and violet
# disruptions. They are actual OpenGL entityTranslucent textures, not a HUD mask.
def png(dst,w,h,pix):
    dst.parent.mkdir(parents=True,exist_ok=True)
    def c(tag,p):
        return struct.pack(">I",len(p))+tag+p+struct.pack(">I",zlib.crc32(tag+p)&0xffffffff)
    scan=b"".join(b"\0"+bytes(channel for x in range(w) for channel in pix[y*w+x]) for y in range(h))
    dst.write_bytes(b"\x89PNG\r\n\x1a\n"+c(b"IHDR",struct.pack(">2I5B",w,h,8,6,0,0,0))
                    +c(b"IDAT",zlib.compress(scan,9))+c(b"IEND",b""))

for frame in range(4):
    rng=random.Random(240300+frame)
    pix=[]
    for y in range(64):
        for x in range(64):
            swirl=math.sin((x+frame*4)*.32+math.sin(y*.26+frame*1.7)*2.5)
            wisps=math.cos((y-frame*5)*.31+math.sin(x*.19)*2.2)
            salt=rng.random()
            alpha=int(112+55*swirl+37*wisps+29*(salt-.5))
            if salt<.12:alpha-=76
            if salt>.984:alpha+=45
            alpha=max(9,min(226,alpha))
            shade=7+int((swirl+1)*3.5)
            r,g,b=shade,shade+1,shade+6
            if rng.random()<.07:
                r,g,b=35+rng.randrange(25),12+rng.randrange(12),49+rng.randrange(40)
                alpha=min(235,alpha+45)
            # Face: only barely visible violet eye hints among the smoke.
            if y in (11,12) and x in (10,13) and frame%3!=2:
                r,g,b,alpha=83,30,107,205
            pix.append((r,g,b,alpha))
    png(ASSETS/f"textures/entity/shadow_smoke_{frame}.png",64,64,pix)

# Replace the old opaque Minecraft skin and renderer by four animated alpha textures.
renderer=JAVA/"client/GlitchShadowRenderer.java"
prior=renderer.read_text(encoding="utf-8")
assert "extends MobRenderer<WatcherEntity" in prior and "glitch_shadow.png" in prior
renderer.write_text(r'''
package com.blackfrequency.hollowsignal.client;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.blackfrequency.hollowsignal.entity.WatcherEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import javax.annotation.Nullable;

/**
 * Human-shaped darkness, not an opaque Minecraft player skin.
 * Animated transparent textures + drifting smoke particle emitters make the
 * character disintegrate into the environment.
 */
public final class GlitchShadowRenderer
        extends MobRenderer<WatcherEntity,HumanoidModel<WatcherEntity>> {
    private static final ResourceLocation[] SMOKE=new ResourceLocation[4];
    static {
        for(int i=0;i<4;i++)
            SMOKE[i]=new ResourceLocation(BlackFrequency.MODID,
                    "textures/entity/shadow_smoke_"+i+".png");
    }

    public GlitchShadowRenderer(EntityRendererProvider.Context context) {
        super(context,new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)),.0f);
    }

    @Override
    public ResourceLocation getTextureLocation(WatcherEntity entity) {
        return SMOKE[(entity.tickCount/7)&3];
    }

    @Nullable @Override
    protected RenderType getRenderType(WatcherEntity entity,boolean bodyVisible,
                                      boolean translucent,boolean glowing) {
        return (bodyVisible||translucent||glowing)
            ? RenderType.entityTranslucent(getTextureLocation(entity)):null;
    }

    @Override
    protected boolean shouldShowName(WatcherEntity entity) {
        return false; // No floating text; only the target player's screen speaks.
    }

    @Override
    protected int getBlockLightLevel(WatcherEntity entity,BlockPos pos) {
        return Math.max(7,super.getBlockLightLevel(entity,pos));
    }

    @Override
    public void render(WatcherEntity entity,float yaw,float partial,
                       PoseStack poses,MultiBufferSource buffers,int packedLight) {
        poses.pushPose();
        float time=entity.tickCount+partial;
        float flicker=(float)Math.sin(time*.19f);
        poses.scale(1.015f+flicker*.012f,1.015f-flicker*.006f,1.015f+flicker*.012f);
        if(entity.tickCount%17<2)
            poses.translate(Math.sin(time*3.3f)*.055,0,Math.cos(time*2.7f)*.038);
        super.render(entity,yaw,partial,poses,buffers,packedLight);
        poses.popPose();
    }
}
'''.strip()+"\n",encoding="utf-8")

write("client/ClientSmokeFx.java",r'''
package com.blackfrequency.hollowsignal.client;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.blackfrequency.hollowsignal.entity.WatcherEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only local smoke particles: no network flood or forced chunks. */
@Mod.EventBusSubscriber(modid=BlackFrequency.MODID,value=Dist.CLIENT,
                        bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientSmokeFx {
    private ClientSmokeFx() {}
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if(e.phase!=TickEvent.Phase.END)return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||mc.isPaused())return;
        if(mc.player.tickCount%2!=0)return;
        var nearby=mc.level.getEntitiesOfClass(WatcherEntity.class,
                mc.player.getBoundingBox().inflate(64.0));
        int processed=0;
        for(WatcherEntity watcher:nearby) {
            if(processed++>=6)break;
            var rand=mc.level.random;
            // Vertical black/grey vapour that preserves the humanoid outline.
            for(int i=0;i<3;i++) {
                double y=watcher.getY()+rand.nextDouble()*1.9;
                double x=watcher.getX()+(rand.nextDouble()-.5)*.62;
                double z=watcher.getZ()+(rand.nextDouble()-.5)*.62;
                mc.level.addParticle(i==0?ParticleTypes.LARGE_SMOKE:ParticleTypes.SMOKE,
                    x,y,z,(rand.nextDouble()-.5)*.017,
                    .012+rand.nextDouble()*.015,(rand.nextDouble()-.5)*.017);
            }
            if(rand.nextInt(7)==0)
                mc.level.addParticle(ParticleTypes.ASH,
                    watcher.getX()+(rand.nextDouble()-.5)*.7,
                    watcher.getY()+rand.nextDouble()*1.9,
                    watcher.getZ()+(rand.nextDouble()-.5)*.7,0,.015,0);
            if(rand.nextInt(16)==0)
                mc.level.addParticle(ParticleTypes.PORTAL,
                    watcher.getX(),watcher.getY()+rand.nextDouble()*1.8,
                    watcher.getZ(),0,.02,0);
        }
    }
}
''')

# Dedicated authenticated server -> target-only screen message.
write("network/WhisperPacket.java",r'''
package com.blackfrequency.hollowsignal.network;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.blackfrequency.hollowsignal.client.ClientWhisperHud;
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

/** No client-to-server packet: clients cannot inject text onto other players. */
public record WhisperPacket(String text,int style,int duration) {
    private static final String VERSION="1";
    private static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(
        new ResourceLocation(BlackFrequency.MODID,"private_whisper"),
        ()->VERSION,VERSION::equals,VERSION::equals);
    public static void register() {
        CHANNEL.registerMessage(0,WhisperPacket.class,
            (p,b)->{b.writeUtf(p.text(),180);b.writeVarInt(p.style());b.writeVarInt(p.duration());},
            b->new WhisperPacket(b.readUtf(180),b.readVarInt(),b.readVarInt()),
            WhisperPacket::receive,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    private static void receive(WhisperPacket p,Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            ()->()->ClientWhisperHud.show(p.text(),p.style(),p.duration())));
        supplier.get().setPacketHandled(true);
    }
    public static void send(ServerPlayer p,String text,int style,int duration) {
        if(p==null||text==null||text.isEmpty())return;
        String clean=text.replace('\n',' ').replace('\r',' ');
        if(clean.length()>180)clean=clean.substring(0,180);
        CHANNEL.send(PacketDistributor.PLAYER.with(()->p),
            new WhisperPacket(clean,Math.max(0,Math.min(style,2)),
                    Math.max(24,Math.min(220,duration))));
    }
}
''')

write("client/ClientWhisperHud.java",r'''
package com.blackfrequency.hollowsignal.client;

import com.blackfrequency.hollowsignal.BlackFrequency;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Server-selected whispers appear ON SCREEN, never in chat or on a name tag. */
@Mod.EventBusSubscriber(modid=BlackFrequency.MODID,value=Dist.CLIENT,
                        bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientWhisperHud {
    private static String text="";
    private static int style,remaining,total,age;
    private ClientWhisperHud() {}

    public static void show(String message,int kind,int ticks) {
        if(message==null||message.isEmpty())return;
        text=message.length()>180?message.substring(0,180):message;
        style=Mth.clamp(kind,0,2);
        total=Mth.clamp(ticks,24,220);
        remaining=total;
        age=0;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null||mc.level==null) {
            remaining=0;text="";age=0;return;
        }
        if(mc.isPaused()||remaining<=0)return;
        remaining--;age++;
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        if(remaining<=0||text.isBlank())return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||mc.screen!=null)return;
        GuiGraphics g=event.getGuiGraphics();
        int w=event.getWindow().getGuiScaledWidth();
        int h=event.getWindow().getGuiScaledHeight();
        int x=w/2,y=h/2+(style==2?4:style==1?34:47);
        float fade=Math.min(1.0f,Math.min(age/11.0f,remaining/17.0f));
        // Deterministic intermittent glitches; not flashing the whole screen.
        boolean tear=style>0 && (age%29<2||age%43==11);
        int dx=tear?(age%3-1)*3:0;
        int a=Mth.clamp((int)(225*fade),0,255);
        int red=style==2?255:style==1?190:164;
        int green=style==2?52:style==1?93:151;
        int blue=style==2?75:style==1?210:191;
        int color=(a<<24)|(red<<16)|(green<<8)|blue;
        int tw=Math.min(w-30,mc.font.width(text)+28);
        int left=x-tw/2;
        if(tw>0) {
            g.fill(left,y-7,left+tw,y+18,(Math.min(160,a/2)<<24)|0x060309);
            if(style>0) {
                int edge=(Math.min(a,145)<<24)|0x50255F;
                g.fill(left,y-7,left+Math.min(31,tw),y-6,edge);
                g.fill(left+Math.max(0,tw-28),y+17,left+tw,y+18,edge);
            }
        }
        // Visible glitch text built from the already-authorized server text.
        String output=text;
        if(tear&&output.length()>7) {
            int start=(age*7)%Math.max(1,output.length()-3);
            output=output.substring(0,start)+"█"+output.substring(start+1);
        }
        if(style==2&&tear)
            g.drawCenteredString(mc.font,output,x+dx+2,y+1,(a<<24)|0x771747);
        g.drawCenteredString(mc.font,output,x+dx,y,color);
    }
}
''')

# Remove floating labels and replace old action-bar chat broadcasts.
edit("world/EntityEncounters.java",
     '            watcher.setCustomName(Component.literal("Сдайся").withStyle(ChatFormatting.DARK_RED));\n'
     '            watcher.setCustomNameVisible(true);',
     '            // The target sees whispers only through the private HUD packet.')
edit("world/EntityEncounters.java",
     '                if (old.physical) {\n'
     '                    if (now-old.lastTaunt >= 20*5) taunt(player,old,now);\n'
     '                }',
     '                if(now-old.lastTaunt >= 20*7) taunt(player,old,now);')
edit("world/EntityEncounters.java",
     '        active.put(id,new Encounter(watcher,zero,level,now));',
     '        active.put(id,new Encounter(watcher,zero,level,now));\n'
     '        com.blackfrequency.hollowsignal.network.WhisperPacket.send(player,\n'
     '            HorrorTaunts.next(player.getRandom().nextInt(700),id.getLeastSignificantBits()),1,86);')
edit("world/EntityEncounters.java",
     '        encounter.watcher.setCustomName(Component.literal(taunt)\n'
     '                .withStyle(ChatFormatting.DARK_RED));\n'
     '        encounter.watcher.setCustomNameVisible(true);\n'
     '        // Target receives a quiet localized whisper instead of chat spam.\n'
     '        player.displayClientMessage(Component.literal("§8[Шёпот] §4"+taunt),true);',
     '        // Target-only packet. No floating name and no chat/action-bar message.\n'
     '        com.blackfrequency.hollowsignal.network.WhisperPacket.send(player,taunt,1,92);')

edit("world/StoryAnomalies.java",
     '                p.displayClientMessage(Component.literal(voice).withStyle(\n'
     '                        phase==4?ChatFormatting.DARK_RED:\n'
     '                                mind.morality>=80?ChatFormatting.GRAY:ChatFormatting.DARK_PURPLE),true);',
     '                com.blackfrequency.hollowsignal.network.WhisperPacket.send(p,voice,\n'
     '                    phase==4?2:1,100);')
edit("world/StoryAnomalies.java",
     '                p.displayClientMessage(Component.literal("██ СДАЙСЯ ██")\n'
     '                        .withStyle(ChatFormatting.DARK_RED),true);',
     '                com.blackfrequency.hollowsignal.network.WhisperPacket.send(p,\n'
     '                    "██ СДАЙСЯ ██",2,90);')

# The final sky break announcement also goes on screen.
edit("world/RuptureCinematicSystem.java",
     '            player.sendSystemMessage(Component.literal("РАЗРЫВ РЕАЛЬНОСТИ. Мир больше не прежний.")\n'
     '                    .withStyle(ChatFormatting.DARK_RED));',
     '            com.blackfrequency.hollowsignal.network.WhisperPacket.send(player,\n'
     '                "РАЗРЫВ РЕАЛЬНОСТИ. МИР БОЛЬШЕ НЕ ПРЕЖНИЙ.",2,140);')

# Register separate authorized packet and add an operator-only HUD smoke test.
edit("BlackFrequency.java",
     '        com.blackfrequency.hollowsignal.network.AtmospherePacket.register();',
     '        com.blackfrequency.hollowsignal.network.AtmospherePacket.register();\n'
     '        com.blackfrequency.hollowsignal.network.WhisperPacket.register();')
edit("command/HorrorCommands.java",
     '                .then(Commands.literal("story")',
     '                .then(Commands.literal("whisper").executes(ctx -> {\n'
     '                    var p=ctx.getSource().getPlayerOrException();\n'
     '                    com.blackfrequency.hollowsignal.network.WhisperPacket.send(p,\n'
     '                        "ТЫ НАДЕЕШЬСЯ, ЧТО МИР ОСТАНЕТСЯ ПРЕЖНИМ? КАК НАИВНО.",1,160);\n'
     '                    return 1;\n'
     '                }))\n'
     '                .then(Commands.literal("story")')

properties=ROOT/"gradle.properties"
s=properties.read_text(encoding="utf-8")
assert s.count("0.23.0")==1
properties.write_text(s.replace("0.23.0","0.24.0"),encoding="utf-8")
print("PASS v0.24: four animated translucent smoky textures, particle aura, targeted private HUD text, no in-chat or floating watcher taunts")
