#!/usr/bin/env python3
"""BLACK FREQUENCY v0.23: timed plot, client shader FX, private long-distance shadow.
Patch is applied AFTER v0.22. Python creates real Java + GLSL in the Forge source.
"""
from pathlib import Path
import json

ROOT=Path("black-frequency/project")
JAVA=ROOT/"src/main/java/com/blackfrequency/hollowsignal"
RES=ROOT/"src/main/resources/assets/hollowsignal"
def edit(rel,old,new):
    p=JAVA/rel
    src=p.read_text(encoding="utf-8")
    assert src.count(old)==1,(rel,repr(old[:75]),src.count(old))
    p.write_text(src.replace(old,new,1),encoding="utf-8")
def put_java(rel,content):
    path=JAVA/rel
    path.parent.mkdir(parents=True,exist_ok=True)
    assert not path.exists(),rel
    path.write_text(content.strip()+"\n",encoding="utf-8")
def asset(rel,contents):
    p=RES/rel
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(contents if isinstance(contents,str) else json.dumps(contents,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")

# Before day FOUR: only tiny signs. The white fragment is 100% silent.
edit("world/StoryAnomalies.java","if(n<=2) return 0;","if(n<=3) return 0;")
edit("world/StoryAnomalies.java","if(n<=5) return 1;","if(n<=6) return 1;")
edit("world/StoryAnomalies.java",
     "voice=FRIENDLY[next(p)%FRIENDLY.length];",
     "voice=null; // The white silhouette never speaks. It communicates through the note.")
# Day 3 no dream. The existing good/nightmare rules remain as they were.
dream=JAVA/"world/DreamSystem.java"
ds=dream.read_text(encoding="utf-8")
assert "StoryAnomalies.day(p)" in ds
# Check the gating context before replacing to avoid breaking the underlying schedule.
if "if(day<3)" in ds: ds=ds.replace("if(day<3)","if(day<4)")
elif "if (day < 3)" in ds: ds=ds.replace("if (day < 3)","if (day < 4)")
dream.write_text(ds,encoding="utf-8")

# Actual sightline: the enemy manifests in FRONT of the camera near view distance,
# and still has to be standing on a loaded solid block (no force-loading chunks).
edit("world/EntityEncounters.java",
     '        boolean zero=mind.sanity==0 && mind.morality==0;',
     '        if(StoryAnomalies.phase(player)==0) { clear(player); return; }\n'
     '        boolean zero=mind.sanity==0 && mind.morality==0;')
edit("world/EntityEncounters.java",
     'double angle=Math.atan2(view.z,view.x)+Math.PI+(rng.nextDouble()-.5)*1.6;',
     'double angle=Math.atan2(view.z,view.x)+(rng.nextDouble()-.5)*0.9;')
edit("world/EntityEncounters.java",
     'double distance=7+rng.nextInt(7);',
     'double distance=24+rng.nextInt(20); // Distant peripheral apparition')
edit("world/EntityEncounters.java",
     'String taunt=TAUNTS[encounter.tauntNumber++%TAUNTS.length];',
     'String taunt=HorrorTaunts.next(encounter.tauntNumber++,player.getUUID().getLeastSignificantBits());')

put_java("world/HorrorTaunts.java",r'''
package com.blackfrequency.hollowsignal.world;

/** Repeatable mocked sentences; variations remain semantically coherent. */
public final class HorrorTaunts {
    private static final String[] BEGIN={"Ты думаешь,","Ты надеешься,","Ты уверен,","Ты веришь,","Ты ещё ждёшь,"};
    private static final String[] MIDDLE={"что тебя спасут","что это закончится","что мир останется прежним",
        "что ты проснёшься","что я исчезну"};
    private static final String[] END={"? Как наивно.","? Смешно.",". Мне нравится твоя уверенность.",
        "? Ты уже опоздал.","? Попробуй ещё раз."};
    private HorrorTaunts() {}
    public static String next(int index,long seed) {
        long n=Math.floorMod(seed + index*73428767L,1000000007L);
        int a=(int)(n%BEGIN.length);
        int b=(int)((n/BEGIN.length)%MIDDLE.length);
        int c=(int)((n/BEGIN.length/MIDDLE.length)%END.length);
        return BEGIN[a]+" "+MIDDLE[b]+END[c];
    }
}
''')

# Day 10 is deterministic one-shot (flag persisted in existing SavedData).
# The server chooses ONE random eligible player; existing cinematic QTE handles
# the global event, dramatic cracked sky and post-rupture world effects.
edit("world/PsychologyData.java",
     '    public long storyStartDay = -1;',
     '    public long storyStartDay = -1;\n    public boolean dayTenChosen;')
edit("world/PsychologyData.java",
     '        d.storyStartDay = tag.contains("storyStartDay") ? tag.getLong("storyStartDay") : -1;',
     '        d.storyStartDay = tag.contains("storyStartDay") ? tag.getLong("storyStartDay") : -1;\n'
     '        d.dayTenChosen = tag.getBoolean("dayTenChosen");')
edit("world/PsychologyData.java",
     '        tag.putLong("storyStartDay", storyStartDay);',
     '        tag.putLong("storyStartDay", storyStartDay);\n        tag.putBoolean("dayTenChosen", dayTenChosen);')

put_java("world/DayTenClimax.java",r'''
package com.blackfrequency.hollowsignal.world;

import com.blackfrequency.hollowsignal.HorrorConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import java.util.List;

public final class DayTenClimax {
    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if(e.phase!=TickEvent.Phase.END || !HorrorConfig.ENABLED.get()) return;
        MinecraftServer server=ServerLifecycleHooks.getCurrentServer();
        if(server==null || server.overworld().getGameTime()%100L!=0L) return;
        PsychologyData data=PsychologyData.get(server);
        if(data.dayTenChosen || data.ruptureActive()) return;
        List<ServerPlayer> eligible=server.getPlayerList().getPlayers().stream()
            .filter(p->p.isAlive()&&!p.isSpectator()&&!p.isCreative())
            .toList();
        if(eligible.isEmpty() || StoryAnomalies.day(eligible.get(0))<10) return;
        ServerPlayer chosen=eligible.get(server.overworld().random.nextInt(eligible.size()));
        data.dayTenChosen=true;
        data.setDirty(); // prevents repeat after relog or after the boss is defeated
        RuptureCinematicSystem.begin(chosen,data);
    }
}
''')

# Synchronize the client's individual visual state from the server (multiplayer safe).
put_java("network/AtmospherePacket.java",r'''
package com.blackfrequency.hollowsignal.network;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.blackfrequency.hollowsignal.client.ClientAtmosphere;
import net.minecraft.network.FriendlyByteBuf;
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

public record AtmospherePacket(int phase, int sanity, boolean cinematic) {
    private static final String VERSION="1";
    private static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(
        new ResourceLocation(BlackFrequency.MODID,"atmosphere"),
        ()->VERSION,VERSION::equals,VERSION::equals);
    public static void register() {
        CHANNEL.registerMessage(0,AtmospherePacket.class,
            (p,b)->{b.writeVarInt(p.phase());b.writeVarInt(p.sanity());b.writeBoolean(p.cinematic());},
            b->new AtmospherePacket(b.readVarInt(),b.readVarInt(),b.readBoolean()),
            AtmospherePacket::receive,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    private static void receive(AtmospherePacket p,Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            ()->()->ClientAtmosphere.receive(p.phase(),p.sanity(),p.cinematic())));
        context.get().setPacketHandled(true);
    }
    public static void send(ServerPlayer p,int phase,int sanity,boolean cinematic) {
        CHANNEL.send(PacketDistributor.PLAYER.with(()->p),
            new AtmospherePacket(phase,sanity,cinematic));
    }
}
''')
put_java("world/AtmosphereServer.java",r'''
package com.blackfrequency.hollowsignal.world;

import com.blackfrequency.hollowsignal.HorrorConfig;
import com.blackfrequency.hollowsignal.network.AtmospherePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class AtmosphereServer {
    @SubscribeEvent
    public void tick(TickEvent.PlayerTickEvent e) {
        if(e.phase!=TickEvent.Phase.END || !(e.player instanceof ServerPlayer p))return;
        if(p.getServer().overworld().getGameTime()%40!=0) return; // every 2 seconds
        if(!HorrorConfig.ENABLED.get()) {
            AtmospherePacket.send(p,-1,100,false);
            return;
        }
        var data=PsychologyData.get(p.getServer());
        int phase=StoryAnomalies.phase(p);
        boolean release=data.ruptureActive() && data.ruptureStage==0;
        AtmospherePacket.send(p,phase,data.mind(p).sanity,release);
    }
}
''')

# The built-in 1.20.1 postprocessing system compiles these GLSL programs using
# the currently active OpenGL context upon initial world entry. We prewarm each
# preset chain at first login and close them, leaving the matching chain active.
put_java("client/ClientAtmosphere.java",r'''
package com.blackfrequency.hollowsignal.client;

import com.blackfrequency.hollowsignal.BlackFrequency;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/** Native OpenGL post shader: precompiled on first world entry, no menu shader. */
@Mod.EventBusSubscriber(modid=BlackFrequency.MODID,value=Dist.CLIENT,
                       bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientAtmosphere {
    private static final Logger LOG=LogUtils.getLogger();
    private static final String[] PRESETS={"quiet","visions","pressure","critical","rupture"};
    private static int received=-2, last=-2, sanity=100, ticks=0;
    private static boolean release=false, warmed=false, failed=false;
    private static boolean owned=false;
    private ClientAtmosphere() {}

    public static void receive(int phase,int sanityValue,boolean cinematic) {
        received=Math.max(-1,Math.min(4,phase));
        sanity=Math.max(0,Math.min(100,sanityValue));
        release=cinematic;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if(e.phase!=TickEvent.Phase.END)return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null) {
            if(owned) mc.gameRenderer.shutdownEffect();
            owned=false; warmed=false; failed=false;
            received=-2; last=-2; ticks=0;
            return;
        }
        if(failed) return;
        ticks++;
        if(ticks<8) return;
        // Do not overwrite another mod's or spectator's active post chain.
        if(!owned && mc.gameRenderer.currentEffect()!=null) return;
        if(!warmed) {
            warmed=true;
            for(String name:PRESETS) {
                ResourceLocation id=location(name);
                try(PostChain warm=new PostChain(mc.getTextureManager(),
                        mc.getResourceManager(),mc.getMainRenderTarget(),id)) {
                    LOG.info("BLACK FREQUENCY GPU: compiled {}",id);
                }catch(Exception ex) {
                    LOG.warn("BLACK FREQUENCY shader warmup {}: {}",id,ex.getMessage());
                    failed=true;
                    return; // never crash a player just because GL extension is absent
                }
            }
        }
        int stage=received<0?0:received;
        // More intense on critically low sanity even before the global fracture.
        if(stage>0&&stage<4&&sanity<20) stage=Math.max(stage,3);
        if(release) stage=4;
        if(stage!=last) {
            try {
                mc.gameRenderer.loadEffect(location(PRESETS[stage]));
                owned=mc.gameRenderer.currentEffect()!=null;
                if(owned)last=stage;
                else { failed=true; LOG.warn("BLACK FREQUENCY: shader chain not activated"); }
            }catch(Exception ex) {
                failed=true;
                LOG.warn("BLACK FREQUENCY: shader disabled to preserve game stability",ex);
                if(owned)mc.gameRenderer.shutdownEffect();
                owned=false;
            }
        }
    }
    private static ResourceLocation location(String name) {
        return new ResourceLocation(BlackFrequency.MODID,"shaders/post/atmosphere_"+name+".json");
    }
}
''')

# Registers ONLY on the correct side. Forge event subscriber is client-only.
edit("BlackFrequency.java",
    '        MinecraftForge.EVENT_BUS.register(com.blackfrequency.hollowsignal.world.BlackFrequencyStory.INSTANCE);',
    '        MinecraftForge.EVENT_BUS.register(com.blackfrequency.hollowsignal.world.BlackFrequencyStory.INSTANCE);\n'
    '        com.blackfrequency.hollowsignal.network.AtmospherePacket.register();\n'
    '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.AtmosphereServer());\n'
    '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.DayTenClimax());')

# Shader programs with a single dynamic Time uniform; presets override Strength.
# Avoid overriding any vanilla namespace; no external Iris/OptiFine dependency.
fragment=r'''#version 150
uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Time;
uniform float Strength;
in vec2 texCoord;
out vec4 fragColor;

float hash12(vec2 p) {
    p=fract(p*vec2(123.34,345.45));
    p+=dot(p,p+34.345);
    return fract(p.x*p.y);
}
void main() {
    vec2 uv=texCoord;
    vec2 pc=uv*2.0-1.0;
    float radius=dot(pc,pc*.80);
    float time=Time*170.0;
    float intensity=clamp(Strength,0.0,1.0);

    // A few rare horizontal data tears; almost invisible on early days.
    float row=floor(uv.y*250.0);
    float block=floor(time*2.5);
    float glitch=step(0.976,hash12(vec2(row,block)));
    float tear=(hash12(vec2(row,block+18.0))-.5)*0.032*glitch*intensity;
    uv.x=clamp(uv.x+tear,0.001,0.999);

    // Chromatic aberration uses 3 taps, preserving readable image/UI.
    vec2 dir=normalize(pc+vec2(.001));
    vec2 shift=dir*(.00015+intensity*.0028)*(0.35+radius);
    float r=texture(DiffuseSampler,clamp(uv+shift,0.001,0.999)).r;
    float g=texture(DiffuseSampler,uv).g;
    float b=texture(DiffuseSampler,clamp(uv-shift,0.001,0.999)).b;
    vec3 color=vec3(r,g,b);

    // Film grain, slight desaturation and cold violet-gray atmosphere.
    float n=hash12(floor(uv*InSize)+vec2(floor(time*7.0),floor(time*11.0)));
    color+=(n-.5)*(.015+.045*intensity);
    float luma=dot(color,vec3(.2126,.7152,.0722));
    color=mix(color,vec3(luma),intensity*.16);
    color=mix(color,color*vec3(.85,.90,1.03),intensity*.22);

    // Slight scanlines with randomized temporal drift.
    float scan=sin(uv.y*InSize.y*1.47 + time*.11)*.5+.5;
    color*=1.0-intensity*.044*scan;

    // Gentle vignette; stronger only for advanced corruption.
    float vignette=smoothstep(.28,1.67,radius);
    color*=1.0-vignette*(.018+intensity*.36);

    // Rupture phase: fracture-like discontinuities and red-violet flickers.
    float risk=smoothstep(.58,.90,intensity);
    float line=abs(fract(uv.x*.87+uv.y*.42+sin(uv.y*11.0)*.045)-.47);
    float crack=(1.0-smoothstep(.008,.025,line))*risk*.20;
    float blink=step(.992,hash12(vec2(floor(time*3.0),5.0)))*risk*.09;
    color=mix(color,vec3(.48,.11,.46),crack);
    color+=vec3(blink,blink*.05,blink*.10);
    fragColor=vec4(clamp(color,0.0,1.0),1.0);
}
'''
asset("shaders/program/atmosphere.fsh",fragment)
asset("shaders/program/atmosphere.json",{
 "vertex":"blit","fragment":"hollowsignal:atmosphere",
 "attributes":["Position"],"samplers":[{"name":"DiffuseSampler"}],
 "uniforms":[
  {"name":"ProjMat","type":"matrix4x4","count":16,"values":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]},
  {"name":"InSize","type":"float","count":2,"values":[1,1]},
  {"name":"OutSize","type":"float","count":2,"values":[1,1]},
  {"name":"Time","type":"float","count":1,"values":[0]},
  {"name":"Strength","type":"float","count":1,"values":[0.05]}]})
for name,strength in [
  ("quiet",0.035),("visions",0.24),("pressure",0.48),
  ("critical",0.66),("rupture",0.96)
]:
    asset("shaders/post/atmosphere_"+name+".json",{
        "targets":["swap"],
        "passes":[
            {"name":"hollowsignal:atmosphere","intarget":"minecraft:main",
             "outtarget":"swap","uniforms":[{"name":"Strength","values":[strength]}]},
            {"name":"blit","intarget":"swap","outtarget":"minecraft:main"}]})

props=ROOT/"gradle.properties"
txt=props.read_text(encoding="utf-8")
assert txt.count("0.22.0")==1
props.write_text(txt.replace("0.22.0","0.23.0"),encoding="utf-8")
print("v0.23 applied: GPU post shader 5 stages, day-10 lottery, distant shadow, silent white silhouette")
