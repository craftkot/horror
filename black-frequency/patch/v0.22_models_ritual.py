#!/usr/bin/env python3
"""v0.22.0: actual rendered glitch humanoid, textured note, and exact ritual layout.
Applied AFTER all v0.21.1 overlays. Procedural PNGs are committed reproducibly
through the build overlay to avoid binary base64 zip patches.
"""
from pathlib import Path
import math
import re
import random
import struct
import zlib
ROOT=Path("black-frequency/project")
JAVA=ROOT/"src/main/java/com/blackfrequency/hollowsignal"
ASSETS=ROOT/"src/main/resources/assets/hollowsignal"
RNG=random.Random(221026)

def edit(rel,old,new):
    path=JAVA/rel
    original=path.read_text(encoding="utf-8")
    assert original.count(old)==1,(rel,original.count(old),old[:90])
    path.write_text(original.replace(old,new,1),encoding="utf-8")

def java(rel,content):
    path=JAVA/rel
    path.parent.mkdir(parents=True,exist_ok=True)
    assert not path.exists(),path
    path.write_text(content.strip()+"\n",encoding="utf-8")

def png(path,w,h,pixels):
    path.parent.mkdir(parents=True,exist_ok=True)
    def chunk(kind,data):
        return struct.pack("!I",len(data))+kind+data+struct.pack("!I",zlib.crc32(kind+data)&0xffffffff)
    scan=b"".join(b"\0"+bytes(component for x in range(w) for component in pixels[y*w+x]) for y in range(h))
    content=b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack("!2I5B",w,h,8,6,0,0,0))
    content+=chunk(b"IDAT",zlib.compress(scan,9))+chunk(b"IEND",b"")
    path.write_bytes(content)

# Minecraft 64x64 skin UV is used DIRECTLY by the vanilla 6-part humanoid
# model: no third-party geometry or resource pipeline is required.
skin=[]
for y in range(64):
    for x in range(64):
        noise=RNG.randint(0,12)
        base=8+(4 if (x//4+y//4)%2 else 0)
        skin.append([base+noise//2,base+noise//2,base+noise,255])
def dot(x,y,color):
    if 0<=x<64 and 0<=y<64: skin[y*64+x]=list(color)
# Minecraft player face front UV x8..15 y8..15; black without a human mouth.
for x,y in [(10,11),(11,11),(13,11),(14,11),(10,12),(14,12),
            (10,13),(14,13),(10,14),(14,14)]:
    dot(x,y,(165,60,247,255))
dot(10,11,(228,157,255,255));dot(14,11,(228,157,255,255))
# Organic glitch lines that run across UV faces and make every side different.
for origin_x,origin_y,steps in [(25,21,11),(5,23,8),(45,22,11),
                               (21,53,10),(13,7,5),(52,41,9),(35,54,9)]:
    xx=origin_x
    for n in range(steps):
        xx+=(-1 if n%3==0 else 1)
        yy=origin_y+n
        dot(xx,yy,(104+RNG.randrange(70),29,168+RNG.randrange(60),255))
        if n%4==0: dot(xx+1,yy,(43,19,64,255))
for i in range(100):
    x,y=RNG.randrange(64),RNG.randrange(64)
    if RNG.random()<.22: dot(x,y,(52,19,79,255))
png(ASSETS/"textures/entity/glitch_shadow.png",64,64,skin)

# Explicit model/renderer gives physical blocky arms/legs and shadow jitter.
java("client/GlitchShadowRenderer.java",r'''
package com.blackfrequency.hollowsignal.client;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.blackfrequency.hollowsignal.entity.WatcherEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Visible in-world humanoid model (not an image concept). Blocks use the
 * player-model UVs, a dedicated 64x64 skin and intermittent digital offsets.
 */
public final class GlitchShadowRenderer
        extends MobRenderer<WatcherEntity, HumanoidModel<WatcherEntity>> {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(BlackFrequency.MODID, "textures/entity/glitch_shadow.png");

    public GlitchShadowRenderer(EntityRendererProvider.Context context) {
        super(context,new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)),.18f);
    }

    @Override
    public ResourceLocation getTextureLocation(WatcherEntity entity) {
        return TEXTURE;
    }

    @Override
    protected int getBlockLightLevel(WatcherEntity entity,BlockPos pos) {
        return Math.max(10,super.getBlockLightLevel(entity,pos));
    }

    @Override
    public void render(WatcherEntity entity,float yaw,float partialTick,
                       PoseStack poses,MultiBufferSource buffers,int packedLight) {
        poses.pushPose();
        float t=entity.tickCount+partialTick;
        // Brief tearing/jitter once in a while, no permanent seizure wobble.
        if (((entity.tickCount/4)%13)==0) {
            poses.translate(Math.sin(t*15.3f)*.045,0,Math.cos(t*12.7f)*.025);
        }
        super.render(entity,yaw,partialTick,poses,buffers,packedLight);
        poses.popPose();
    }
}
''')
# Override the old renderer through its ORIGINAL registration site when possible.
# Avoid adding a second renderer subscriber if source has a clear registration.
registry_files=[]
for path in JAVA.rglob("*.java"):
    if path.name=="GlitchShadowRenderer.java": continue
    s=path.read_text(encoding="utf-8")
    if "WATCHER" in s and ("registerEntityRenderer" in s or "EntityRenderers.register" in s):
        registry_files.append(path)
changed=0
for path in registry_files:
    s=path.read_text(encoding="utf-8")
    # Match renderer constructor refs for the WatcherEntity.
    n=re.subn(r'(\b(?:WatcherRenderer|WatcherEntityRenderer)::new\b)',
              r'com.blackfrequency.hollowsignal.client.GlitchShadowRenderer::new',s)
    if n[1]>0:
        path.write_text(n[0],encoding="utf-8")
        changed+=n[1]
if changed==0:
    java("client/GlitchShadowRegistration.java",r'''
package com.blackfrequency.hollowsignal.client;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.blackfrequency.hollowsignal.registry.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.EventPriority;

@Mod.EventBusSubscriber(modid=BlackFrequency.MODID,
        bus=Mod.EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
public final class GlitchShadowRegistration {
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void renderer(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModEntities.WATCHER.get(),GlitchShadowRenderer::new);
    }
}
''')
print("WATCHER_REGISTRATION_PATCHED",changed,"/ discovered",len(registry_files))

# Craft a real 16px inventory icon (no longer a vanilla paper texture).
paper=[]
for y in range(16):
    for x in range(16):
        torn=(x in (0,15) or y in (0,15)) and RNG.random()<.55
        crack=(x+y*2)%13==0 and x>=9
        if torn: color=(0,0,0,0)
        elif crack: color=(80,10,121,255)
        elif 2<=x<=13 and 1<=y<=14:
            offset=RNG.randrange(22)
            color=(205-offset,184-offset,158-offset,255)
        else: color=(9,8,14,0)
        paper.append(color)
for px,py in [(8,4),(9,5),(9,6),(10,7),(11,8),(10,9),
              (11,10),(12,11),(11,12)]:
    paper[py*16+px]=(67,5,108,255)
png(ASSETS/"textures/item/fractured_note.png",16,16,paper)
(ASSETS/"models/item/fractured_note.json").write_text(
    '{"parent":"minecraft:item/generated","textures":{"layer0":"hollowsignal:item/fractured_note"}}\n',
    encoding="utf-8")

# Actual parchment GUI background, purple microsplits, rough tear edges.
w,h=336,244
bg=[]
for y in range(h):
    for x in range(w):
        edge=min(x,y,w-1-x,h-1-y)
        cut=RNG.randrange(7) if edge<=6 else 0
        void=((x-259)**2//490 +(y-164)**2//105<1) or (
            46<x<56 and 55<y<175 and (y//12)%3==0)
        damaged=(edge<cut or (x>272 and y<58 and x+y>310))
        if damaged: color=(0,0,0,0)
        elif void: color=(19,7,30,255)
        elif edge<=8:
            color=(69+RNG.randrange(13),40+RNG.randrange(14),43+RNG.randrange(18),255)
        else:
            no=RNG.randrange(22)
            color=(204-no,190-no,157-no,255)
        bg.append(color)
# Readable dark rift scars with purple edges, not a full opaque UI overlay.
for start_x,start_y,length in [(275,31,57),(47,159,21),(247,170,31)]:
    for i in range(length):
        xx=start_x+i//4*(1 if i%9<6 else -1)
        yy=start_y+i
        for dx in (-1,0,1):
            if 0<=xx+dx<w and 0<=yy<h:
                bg[yy*w+xx+dx]=(111,26,162,255) if dx else (15,2,28,255)
png(ASSETS/"textures/gui/fractured_note.png",w,h,bg)

# GUI draws an in-game schematic on its actual paper: 3x3 base in top view,
# netherite under the central beacon, crystal sockets at x/z = +/-2.
screen=JAVA/"client/FragmentNoteScreen.java"
source=screen.read_text(encoding="utf-8")
assert "class FragmentNoteScreen" in source and 'renderBackground(g);' in source
screen.write_text(r'''
package com.blackfrequency.hollowsignal.client;

import com.blackfrequency.hollowsignal.BlackFrequency;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/** R-click the item: parchment texture, readable ritual drawing and rift scars. */
public final class FragmentNoteScreen extends Screen {
    private static final ResourceLocation PAPER =
            new ResourceLocation(BlackFrequency.MODID,"textures/gui/fractured_note.png");
    private int page=0;
    private int age=0;

    private FragmentNoteScreen() { super(Component.literal("Повреждённая записка")); }
    public static void open() { Minecraft.getInstance().setScreen(new FragmentNoteScreen()); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { age++; }

    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==GLFW.GLFW_KEY_RIGHT || key==GLFW.GLFW_KEY_D) {
            page=(page+1)%3;
            return true;
        }
        if(key==GLFW.GLFW_KEY_LEFT || key==GLFW.GLFW_KEY_A) {
            page=(page+2)%3;
            return true;
        }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseScrolled(double x,double y,double delta) {
        page=(page+(delta<0?1:2))%3;
        return true;
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial) {
        renderBackground(g);
        int x=(width-336)/2,y=(height-244)/2;
        g.blit(PAPER,x,y,0,0,336,244,336,244);
        g.drawCenteredString(font,"ПОВРЕЖДЁННАЯ ЗАПИСКА",width/2,y+15,0xFF271B23);
        if(page==0) renderRitual(g,x,y);
        else if(page==1) renderStory(g,x,y);
        else renderCharge(g,x,y);

        // Small tears fluctuate, some readable text is temporarily hidden.
        for(int i=0;i<5;i++) {
            int xx=x+61+(i*47+age/6)%207;
            int yy=y+47+(i*29)%144;
            int color=((age/5+i)%5==0)?0xBCBA4AF1:0x99391350;
            g.fill(xx,yy,xx+2,yy+7,color);
            g.fill(xx+2,yy+3,xx+6,yy+5,color);
        }
        g.drawCenteredString(font,
                "СТРАНИЦА "+(page+1)+"/3      ◀   ▶",
                width/2,y+216,0xFF4A2939);
        super.render(g,mouseX,mouseY,partial);
    }

    private void renderRitual(GuiGraphics g,int x,int y) {
        g.drawString(font,"СХЕМА ВОССТАНОВЛЕНИЯ",x+59,y+35,0xFF3A202D,false);
        int cx=x+166,cy=y+105,s=23;
        for(int z=-1;z<=1;z++)for(int i=-1;i<=1;i++) {
            int tx=cx+i*s,ty=cy+z*s;
            g.fill(tx-10,ty-10,tx+10,ty+10,0xFF74747A);
            g.fill(tx-8,ty-8,tx+8,ty+8,0xFFC3C6C5);
            if(i==0 && z==0) {
                g.fill(tx-8,ty-8,tx+8,ty+8,0xFF26242F);
                g.fill(tx-5,ty-5,tx+5,ty+5,0xFF70DCE2);
                g.fill(tx-2,ty-2,tx+2,ty+2,0xFFF5FEFF);
            }
        }
        for(int dz:new int[]{-2,2}) for(int dx:new int[]{-2,2}) {
            int px=cx+dx*s,pz=cy+dz*s;
            g.fill(px-10,pz-10,px+10,pz+10,0xFF292239);
            g.fill(px-8,pz-7,px+8,pz+7,0xFF642E7A);
            g.fill(px-5,pz-10,px+5,pz+10,0xFFB966EE);
            g.fill(px-3,pz-5,px+3,pz+5,0xFFE3BDFF);
        }
        g.drawCenteredString(font,"МАЯК В ЦЕНТРЕ",width/2,y+173,0xFF322331);
        g.drawCenteredString(font,"4 КРИСТАЛЛА ПО УГЛАМ",width/2,y+188,0xFF512C65);
        g.drawCenteredString(font,"НЕЗЕРИТ ПОД МАЯКОМ",width/2,y+201,0xFF382B32);
    }

    private void renderStory(GuiGraphics g,int x,int y) {
        g.drawString(font,"ПРОТОКОЛ / ЧАСТЬ 01",x+55,y+43,0xFF402738,false);
        line(g,x,y,67,"Маяк — центральный проводник.");
        line(g,x,y,86,"Под ним: незеритовый блок.");
        line(g,x,y,105,"Основание: 8 обычных блоков.");
        line(g,x,y,124,"Кристаллы Края: 4 угла.");
        line(g,x,y,143,"Обсидиан — опоры кристаллов.");
        line(g,x,y,162,"Плачущий обсидиан рядом.");
        g.drawString(font,"/// █ РАЗРЫВ █ ///",x+80,y+186,0xFF842E92,false);
    }

    private void renderCharge(GuiGraphics g,int x,int y) {
        g.drawString(font,"ПРОТОКОЛ / ЧАСТЬ 02",x+55,y+43,0xFF402738,false);
        line(g,x,y,68,"Кристаллы запитают луч.");
        line(g,x,y,87,"Разломы начнут затягиваться.");
        line(g,x,y,106,"5 осколков = перезарядка.");
        line(g,x,y,125,"Каждые 25% разлом сильнее.");
        line(g,x,y,144,"▓▓▓▓▓▒▒ / НЕИЗВЕСТНО");
        line(g,x,y,162,"Последний этап повреждён.");
        g.drawString(font,"╱╱ [ДАННЫЕ УТРАЧЕНЫ] ╱╱",x+70,y+187,0xFF782865,false);
    }

    private void line(GuiGraphics g,int x,int y,int line,String text) {
        g.drawString(font,text,x+48,y+line,0xFF332B30,false);
    }
}
'''.strip()+"\n",encoding="utf-8")

# Replace arbitrary quadrant search by EXACT 4 diagonally placed crystals
# on vanilla obsidian pedestals, one level below beacon block.
ritual="world/RitualSystem.java"
start='    /** One End Crystal in each corner-quadrant, never accidentally reuse a crystal. */'
path=JAVA/ritual
raw=path.read_text(encoding="utf-8")
assert raw.count(start)==1
begin=raw.index(start)
finish=raw.index('    private static void status(',begin)
replacement=r'''    /** EXACT arrangement: center beacon at (0,0), End Crystals at (+/-2,+/-2). */
    private static List<EndCrystal> findCrystals(ServerLevel level,BlockPos beacon) {
        List<EndCrystal> found=new ArrayList<>();
        for(int dx:new int[]{-2,2}) for(int dz:new int[]{-2,2}) {
            BlockPos support=beacon.offset(dx,-1,dz);
            if(!level.hasChunkAt(support)
                    || !level.getBlockState(support).is(Blocks.OBSIDIAN)) return List.of();
            double cx=beacon.getX()+dx+.5;
            double cz=beacon.getZ()+dz+.5;
            List<EndCrystal> there=level.getEntitiesOfClass(EndCrystal.class,
                    new AABB(cx-.55,beacon.getY()-.25,cz-.55,
                             cx+.55,beacon.getY()+2.1,cz+.55),EndCrystal::isAlive);
            if(there.size()!=1) return List.of();
            found.add(there.get(0));
        }
        return found;
    }
'''
path.write_text(raw[:begin]+replacement+raw[finish:],encoding="utf-8")
edit(ritual,'Вокруг маяка нужны четыре кристалла Края по разным сторонам',
            'Кристаллы нужны на обсидиане по углам: X/Z = ±2 относительно маяка')
edit(ritual,'"Нужны незерит под маяком, основание 3×3 и плачущий обсидиан рядом"',
            '"Нужны незерит под маяком, 8 блоков основания, обсидиан под кристаллами и плачущий обсидиан"')

props=ROOT/"gradle.properties"
p=props.read_text(encoding="utf-8")
assert p.count("0.21.1")==1
props.write_text(p.replace("0.21.1","0.22.0"),encoding="utf-8")
print("PASS v0.22: physical shadow model + PNG assets; opened ritual note; crystal corners exact +2/-2")
