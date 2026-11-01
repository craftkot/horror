#!/usr/bin/env python3
"""BLACK FREQUENCY v0.25: physical, reversible bedrock faults and GPU-animated reality shatter."""
from pathlib import Path
import json
ROOT=Path("black-frequency/project")
J=ROOT/"src/main/java/com/blackfrequency/hollowsignal"
A=ROOT/"src/main/resources/assets/hollowsignal"
def add(rel,s):
    p=J/rel;p.parent.mkdir(parents=True,exist_ok=True)
    assert not p.exists(),rel
    p.write_text(s.strip()+"\n",encoding="utf-8")
def edit(rel,a,b):
    p=J/rel;s=p.read_text(encoding="utf-8")
    assert s.count(a)==1,(rel,a[:110],s.count(a))
    p.write_text(s.replace(a,b,1),encoding="utf-8")
def resource(path,data):
    p=A/path;p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(data if isinstance(data,str) else json.dumps(data,indent=2)+"\n",encoding="utf-8")

# Separate post programs deliberately use shader program uniforms from the
# JSON preset. Unlike newer Minecraft versions, PostChain in 1.20.1 does NOT
# expose a per-frame setUniform public method. The five release presets cover
# the animation states without reflection or access transformers.
fsh=r'''#version 150
uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Time;
uniform float CrackAmount;
uniform float VoidAmount;
uniform float GreenPulse;
uniform float RedReturn;
in vec2 texCoord;
out vec4 fragColor;

float hash21(vec2 p) {
    return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);
}
float segment(vec2 p,vec2 a,vec2 b) {
    vec2 v=b-a;
    float t=clamp(dot(p-a,v)/max(dot(v,v),.00001),0.0,1.0);
    return length(p-(a+t*v));
}
void main() {
    vec2 uv=texCoord;
    float aspect=InSize.x/max(InSize.y,1.0);
    vec2 p=vec2((uv.x-.5)*aspect,uv.y-.5);
    float tick=Time*52.0;
    float flick=sin(tick*19.0)*.5+.5;
    float progress=clamp(CrackAmount,0.0,1.0);
    float healed=clamp(GreenPulse,0.0,1.0);
    float anger=clamp(RedReturn,0.0,1.0);

    // Six long irregular fault-lines span the screen. Each grows through a
    // sine-warped polyline with secondary splinters; no HUD cross-shaped arms.
    float d=10.0;
    float branching=10.0;
    for(int branch=0;branch<6;branch++) {
        float b=float(branch);
        float ang=(b+0.28)*1.0472;
        vec2 dir=vec2(cos(ang)*aspect,sin(ang));
        vec2 tang=vec2(-dir.y,dir.x);
        vec2 last=dir*.87;
        for(int k=0;k<11;k++) {
            float t=float(k)/11.0;
            float nt=float(k+1)/11.0;
            float seed=hash21(vec2(b,float(k)));
            float nextSeed=hash21(vec2(b,float(k+1)));
            vec2 a=dir*(.87-1.42*t)+tang*(seed-.5)*.13;
            vec2 z=dir*(.87-1.42*nt)+tang*(nextSeed-.5)*.13;
            a+=tang*sin(tick*.45+b+k*1.9)*.004*progress;
            z+=tang*sin(tick*.45+b+k*2.3)*.004*progress;
            float growth=step(nt,progress*1.18+.05);
            float dist=segment(p,a,z);
            d=min(d,mix(10.0,dist,growth));
            if(k>1 && k<9 && (k%3==1)) {
                float side=(seed>.5)?1.0:-1.0;
                vec2 tip=z+tang*side*(.13+.09*seed)+dir*.06;
                branching=min(branching,mix(10.0,segment(p,z,tip),growth));
            }
        }
    }
    float fissure=min(d,branching*1.13);
    float edge=(.0034+.0022*anger)*(.8+.22*flick);
    float core=1.0-smoothstep(edge*.35,edge*1.4,fissure);
    float glow=(1.0-smoothstep(edge*1.2,edge*6.6,fissure))*.67;
    vec2 aberr=vec2((core+glow)*.012*progress*(1.0-healed),0.0);
    vec3 col=texture(DiffuseSampler,clamp(uv+aberr,vec2(.002),vec2(.998))).rgb;

    // True sky removal occurs in the Java AFTER_SKY render hook, leaving
    // a void behind terrain. Shader blackens the remaining atmosphere.
    float top=smoothstep(.26,.91,uv.y);
    float voidDarkness=VoidAmount*(.28+.57*top);
    col*=1.0-voidDarkness;

    float grain=(hash21(floor(uv*InSize)+floor(tick)*vec2(3.0,7.0))-.5);
    col+=grain*(.018+.036*progress)*(1.0-healed);
    float vignette=smoothstep(.21,1.35,dot(p,p));
    col*=1.0-.34*vignette*progress;

    vec3 under=vec3(.008,.001,.008);
    vec3 rim=mix(vec3(.48,.16,.65),vec3(.95,.055,.08),anger);
    float visibility=progress*(1.0-healed);
    col=mix(col,under,core*visibility);
    col+=rim*glow*visibility;
    col=mix(col,vec3(.09,.37,.18),healed*.20);
    fragColor=vec4(clamp(col,0.0,1.0),1.0);
}
'''
resource("shaders/program/reality_fracture.fsh",fsh)
resource("shaders/program/reality_fracture.json",{
 "vertex":"blit","fragment":"hollowsignal:reality_fracture",
 "attributes":["Position"],
 "samplers":[{"name":"DiffuseSampler"}],
 "uniforms":[
  {"name":"ProjMat","type":"matrix4x4","count":16,"values":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]},
  {"name":"InSize","type":"float","count":2,"values":[1,1]},
  {"name":"OutSize","type":"float","count":2,"values":[1,1]},
  {"name":"Time","type":"float","count":1,"values":[0]},
  {"name":"CrackAmount","type":"float","count":1,"values":[0.9]},
  {"name":"VoidAmount","type":"float","count":1,"values":[0.85]},
  {"name":"GreenPulse","type":"float","count":1,"values":[0.0]},
  {"name":"RedReturn","type":"float","count":1,"values":[0.0]}]})
for preset,crack,void,green,red in [
    ("release",.87,.98,0,0),
    ("green",.045,.22,1,0),
    ("relapse",1,.99,0,1),
    ("aftershock",.65,.55,0,.65)]:
    resource("shaders/post/atmosphere_"+preset+".json",{
        "targets":["swap"],
        "passes":[
            {"name":"hollowsignal:reality_fracture",
             "intarget":"minecraft:main","outtarget":"swap",
             "uniforms":[{"name":"CrackAmount","values":[crack]},
                         {"name":"VoidAmount","values":[void]},
                         {"name":"GreenPulse","values":[green]},
                         {"name":"RedReturn","values":[red]}]},
            {"name":"blit","intarget":"swap","outtarget":"minecraft:main"}]})

edit("client/ClientRuptureQte.java",
     "    public static boolean isActive() { return active; }",
     "    public static boolean isActive() { return active; }\n"
     "    public static boolean isGreen() { return active && greenTicks>0; }\n"
     "    public static boolean isRed() { return active && redTicks>0; }")
# The QTE UI stays for the button, but ALL old GUI cross-line drawing is removed.
p=J/"client/ClientRuptureQte.java"
s=p.read_text(encoding="utf-8")
start="        int baseDepth = Math.min(9, age / 30);"
stop="        if (currentBeat >= 0 && age < promptEndsAt) {"
assert s.count(start)==s.count(stop)==1
i=s.index(start);j=s.index(stop,i)
s=s[:i]+'''        // Fractures are now real GLSL, across the entire framebuffer.
        // During green recovery they vanish; shader snaps back to angry red.
        if (greenTicks > 0) {
            g.drawCenteredString(Minecraft.getInstance().font,
                    "СТАБИЛИЗАЦИЯ...",centerX,centerY-33,0xFF89FFC1);
        } else if (redTicks > 0) {
            g.drawCenteredString(Minecraft.getInstance().font,
                    "УЖЕ ПОЗДНО",centerX,centerY-33,0xFFFF6B75);
        }

'''+s[j:]
p.write_text(s,encoding="utf-8")

edit("client/ClientAtmosphere.java",
     'private static final String[] PRESETS={"quiet","visions","pressure","critical","rupture"};',
     'private static final String[] PRESETS={"quiet","visions","pressure","critical","rupture",'
     '"release","green","relapse","aftershock"};')
edit("client/ClientAtmosphere.java",
     '        if(release) stage=4;',
     '        if(release || ClientRuptureQte.isActive()) {\n'
     '            stage=ClientRuptureQte.isGreen()?6:ClientRuptureQte.isRed()?7:5;\n'
     '        } else if(stage==4)stage=8;')
# Sky vanishes during actual QTE. RenderLevelStageEvent Stage.AFTER_SKY
# is the Forge-supported stage after clouds/celestial visuals and before
# solid terrain. Only COLOR is cleared; depth is left unchanged.
add("client/ReleaseVoidSky.java",r'''
package com.blackfrequency.hollowsignal.client;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;

/** No opaque overlay over the whole world: only sky pixels are cleared. */
@Mod.EventBusSubscriber(modid=BlackFrequency.MODID,value=Dist.CLIENT,
                        bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class ReleaseVoidSky {
    private ReleaseVoidSky() {}
    @SubscribeEvent
    public static void sky(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_SKY
                || !ClientRuptureQte.isActive())return;
        RenderSystem.assertOnRenderThread();
        // Without clearing depth, terrain and entities still render normally.
        RenderSystem.clearColor(.006f,.002f,.011f,1f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT,false);
    }
}
''')

# The "entire world cracks" must be physical terrain, not only GLSL/HUD.
# Dedicated persistent journal stores exact blockstate IDs for recovery in
# the SAME MC/mod registry configuration. Bounded to guard against runaway lag.
add("world/FaultJournal.java",r'''
package com.blackfrequency.hollowsignal.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Persistent reversible fault trench journal; includes native BEDROCK. */
public final class FaultJournal extends SavedData {
    public static final int MAX_CHANGES=12000;
    private final Map<Long,Integer> before=new LinkedHashMap<>();
    private boolean started;
    public static FaultJournal get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                FaultJournal::load,FaultJournal::new,"hollowsignal_faults");
    }
    private static FaultJournal load(CompoundTag tag) {
        FaultJournal journal=new FaultJournal();
        journal.started=tag.getBoolean("started");
        ListTag list=tag.getList("states",10);
        for(int i=0;i<list.size();i++) {
            CompoundTag row=list.getCompound(i);
            journal.before.put(row.getLong("p"),row.getInt("s"));
        }
        return journal;
    }
    public boolean mark(ServerLevel level,BlockPos pos,BlockState state) {
        if(before.containsKey(pos.asLong()))return false;
        if(before.size()>=MAX_CHANGES)return false;
        if(!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD))return false;
        if(state.isAir()||state.hasBlockEntity())return false;
        before.put(pos.asLong(),Block.getId(state));
        setDirty();
        return true;
    }
    public int size() { return before.size(); }
    public boolean isStarted() { return started; }
    public void markStarted() { started=true;setDirty(); }

    public int restore(MinecraftServer server,int limit) {
        ServerLevel level=server.overworld();
        int count=0;
        Iterator<Map.Entry<Long,Integer>> it=before.entrySet().iterator();
        while(it.hasNext()&&count<limit) {
            var e=it.next();
            BlockPos p=BlockPos.of(e.getKey());
            if(!level.hasChunkAt(p))continue;
            // A player's repairs must take precedence over automated rollback.
            if(!level.getBlockState(p).isAir()) {it.remove();continue;}
            if(level.setBlock(p,Block.stateById(e.getValue()),3)) {
                it.remove();count++;
            }
        }
        if(count>0)setDirty();
        return count;
    }
    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("started",started);
        ListTag rows=new ListTag();
        for(var e:before.entrySet()) {
            CompoundTag row=new CompoundTag();
            row.putLong("p",e.getKey());row.putInt("s",e.getValue());
            rows.add(row);
        }
        tag.put("states",rows);
        return tag;
    }
}
''')

add("world/PhysicalFaults.java",r'''
package com.blackfrequency.hollowsignal.world;

import com.blackfrequency.hollowsignal.HorrorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Real fault columns descend all the way below Y=-60 and CUT THROUGH BEDROCK.
 * Budgeted server updates, no chunk force-loading, journal-backed recovery.
 */
public final class PhysicalFaults {
    private static final int BLOCKS_PER_TICK=72;
    private static final int COLUMN_LIMIT=42;
    private final ArrayDeque<Shaft> active=new ArrayDeque<>();
    private final Set<Long> queued=new HashSet<>();
    private long lastStarted=-1;
    private int scheduled;
    private boolean lastRupture;

    private static final class Shaft {
        int x,z,y,min;
        Shaft(int x,int z,int y,int min) {
            this.x=x;this.z=z;this.y=y;this.min=min;
        }
        long id() {return BlockPos.asLong(x,0,z);}
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || !HorrorConfig.ENABLED.get()
                || !HorrorConfig.WORLD_RUPTURE.get())return;
        MinecraftServer server=ServerLifecycleHooks.getCurrentServer();
        if(server==null)return;
        ServerLevel level=server.overworld();
        PsychologyData psy=PsychologyData.get(server);
        boolean torn=psy.ruptureActive()&&psy.ruptureStage>0;
        if(!torn) {
            active.clear();queued.clear();scheduled=0;lastRupture=false;
            return;
        }
        FaultJournal journal=FaultJournal.get(server);
        if(!lastRupture) {
            lastRupture=true;
            active.clear();queued.clear();scheduled=0;
        }
        if(journal.size()>=FaultJournal.MAX_CHANGES)return;
        long time=level.getGameTime();
        if(time-lastStarted>=60 && scheduled<COLUMN_LIMIT) {
            var survivors=server.getPlayerList().getPlayers().stream()
                    .filter(p->p.serverLevel()==level&&!p.isSpectator())
                    .toList();
            if(!survivors.isEmpty()) {
                ServerPlayer p=survivors.get((int)(time/60%survivors.size()));
                // Winding jagged ground fault in loaded terrain near witnesses.
                int seq=scheduled;
                double angle=(seq*.618033988749895)*6.283185;
                int distance=9+(seq*7)%29;
                int x=p.blockPosition().getX()+(int)Math.round(Math.cos(angle)*distance);
                int z=p.blockPosition().getZ()+(int)Math.round(Math.sin(angle)*distance);
                BlockPos marker=new BlockPos(x,0,z);
                if(level.hasChunkAt(marker)) {
                    int top=level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
                    int floor=level.getMinBuildHeight();
                    // Side-by-side columns make V-shaped fissures, not isolated pinholes.
                    for(int side=0;side<3;side++) {
                        int fx=x+side-1, fz=z+((seq+side)%2);
                        BlockPos probe=new BlockPos(fx,0,fz);
                        if(level.hasChunkAt(probe)) {
                            long key=BlockPos.asLong(fx,0,fz);
                            if(queued.add(key))
                                active.add(new Shaft(fx,fz,Math.max(top,
                                    level.getHeight(Heightmap.Types.WORLD_SURFACE,fx,fz)),
                                    floor));
                        }
                    }
                    scheduled++;lastStarted=time;
                    journal.markStarted();
                }
            }
        }
        int budget=BLOCKS_PER_TICK;
        int examined=0;
        while(budget>0&&!active.isEmpty()&&examined<192) {
            Shaft s=active.poll();
            examined++;
            BlockPos pos=new BlockPos(s.x,s.y,s.z);
            if(!level.hasChunkAt(pos)) {
                queued.remove(s.id());
                continue;
            }
            if(s.y>=s.min && journal.size()<FaultJournal.MAX_CHANGES) {
                var state=level.getBlockState(pos);
                if(!state.isAir()&&!state.hasBlockEntity()
                        && journal.mark(level,pos,state)) {
                    // setBlock bypasses normal mining rules: yes, it cracks BEDROCK.
                    level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
                    budget--;
                    if(s.y%10==0)
                        level.sendParticles(ParticleTypes.ASH,s.x+.5,s.y+.5,s.z+.5,
                                2,.35,.35,.35,.015);
                }
                s.y--;
            }
            if(s.y>=s.min && journal.size()<FaultJournal.MAX_CHANGES)active.add(s);
            else queued.remove(s.id());
        }
    }
}
''')
# Admin recovery and ritual repair also restore real air-filled fault columns.
edit("world/PsychologyData.java",
     '        return count;\n    }\n    /** Incremental bounded crack closure:',
     '        count+=FaultJournal.get(server).restore(server,Integer.MAX_VALUE);\n'
     '        return count;\n    }\n    /** Incremental bounded crack closure:')
# Verify where sealSome is defined: do NOT make a fragile assumption about its tail.
datafile=J/"world/PsychologyData.java"
data=datafile.read_text(encoding="utf-8")
# Earlier world-data refactors changed the spacing inside sealSome; operate on
# the actual method region rather than a brittle exact-whitespace substring.
import re
hit=re.search(r'    public int sealSome\(MinecraftServer server,int budget\) \{.*?\n    \}',data,re.S)
assert hit,"sealSome method missing"
region=hit.group(0)
assert 'return fixed;' in region,"sealSome return not found"
region=region.replace('return fixed;',
    'return fixed+FaultJournal.get(server).restore(server,Math.max(1,budget/2));',1)
datafile.write_text(data[:hit.start()]+region+data[hit.end():],encoding="utf-8")
edit("BlackFrequency.java",
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.DayTenClimax());',
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.DayTenClimax());\n'
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.PhysicalFaults());')
props=ROOT/"gradle.properties"
s=props.read_text(encoding="utf-8")
assert s.count("0.24.0")==1
props.write_text(s.replace("0.24.0","0.25.0"),encoding="utf-8")
print("PASS v0.25: multi-branch fractal GPU faults, sky void, red-green collapse, reversible real bedrock trenches")
