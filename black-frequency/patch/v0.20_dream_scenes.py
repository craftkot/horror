#!/usr/bin/env python3
"""v0.20 dream scene diversity, scene-derived effects, fixed lighting.
Run after verified v0.19 source overlay. Do not alter main.
"""
from pathlib import Path

ROOT = Path("black-frequency/project")
JAVA = ROOT/"src/main/java/com/blackfrequency/hollowsignal"

def edit(rel, old, new):
    p=JAVA/rel
    src=p.read_text(encoding="utf-8")
    matches=src.count(old)
    assert matches==1,(rel,matches,old[:100])
    p.write_text(src.replace(old,new,1),encoding="utf-8")

def region(rel,begin,end,body):
    p=JAVA/rel
    src=p.read_text(encoding="utf-8")
    assert src.count(begin)==1 and src.count(end)==1,(rel,begin,end)
    i=src.index(begin)
    j=src.index(end,i)
    p.write_text(src[:i]+body+src[j:],encoding="utf-8")

def fresh(rel,source):
    p=JAVA/rel
    p.parent.mkdir(parents=True,exist_ok=True)
    assert not p.exists(),p
    p.write_text(source.strip()+"\n",encoding="utf-8")

fresh("world/DreamScenes.java",r'''
package com.blackfrequency.hollowsignal.world;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Location is distinct from mood. Always choose outcome by SCENE plus mood. */
public final class DreamScenes {
    public static final int MINE=0, GROVE=1, RUINS=2, COUNT=3;
    private DreamScenes() {}

    public static String name(int scene) {
        return switch(scene) {
            case MINE -> "шахта";
            case GROVE -> "роща";
            case RUINS -> "руины";
            default -> "неизвестное место";
        };
    }

    /** User-defined: bright mine -> Haste I exactly 300 s. Other entries provisional. */
    public static String onCalm(ServerPlayer player,int scene) {
        return switch(scene) {
            case MINE -> {
                player.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED,20*300,0,false,true));
                yield "Спешка I на 5 минут";
            }
            case GROVE -> {
                player.addEffect(new MobEffectInstance(MobEffects.REGENERATION,20*120,0,false,true));
                yield "Регенерация I на 2 минуты";
            }
            case RUINS -> {
                player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE,20*180,0,false,true));
                yield "Сопротивление I на 3 минуты";
            }
            default -> "Отдых";
        };
    }

    /** User-defined: dark mine -> Mining Fatigue I exactly 180 s. */
    public static String onNightmare(ServerPlayer player,int scene) {
        return switch(scene) {
            case MINE -> {
                player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN,20*180,0,false,true));
                yield "Утомление I на 3 минуты";
            }
            case GROVE -> {
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,20*120,0,false,true));
                yield "Замедление I на 2 минуты";
            }
            case RUINS -> {
                player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,20*120,0,false,true));
                yield "Слабость I на 2 минуты";
            }
            default -> "Беспокойство";
        };
    }
}
''')

# A variant counter persisted per player ensures consecutive dreams have
# different environment silhouettes even if they select the same scene.
edit("world/PsychologyData.java",
     '        public long lastDreamDay = -1;\n',
     '        public long lastDreamDay = -1;\n'
     '        public int dreamAppearanceIndex;\n')
edit("world/PsychologyData.java",
     '            m.lastDreamDay = t.contains("lastDreamDay") ? t.getLong("lastDreamDay") : -1;\n',
     '            m.lastDreamDay = t.contains("lastDreamDay") ? t.getLong("lastDreamDay") : -1;\n'
     '            m.dreamAppearanceIndex = t.getInt("dreamAppearanceIndex");\n')
edit("world/PsychologyData.java",
     '            t.putLong("lastDreamDay", m.lastDreamDay);\n',
     '            t.putLong("lastDreamDay", m.lastDreamDay);\n'
     '            t.putInt("dreamAppearanceIndex", m.dreamAppearanceIndex);\n')

# Packet carries stable scene/variant chosen ON SERVER; client never chooses rewards.
edit("network/DreamPacket.java",
     'public record DreamPacket(int event, int kind, int duration, int morality) {',
     'public record DreamPacket(int event, int kind, int duration, int morality, int scene, int variant) {')
edit("network/DreamPacket.java",
     '                    b.writeVarInt(msg.morality());\n',
     '                    b.writeVarInt(msg.morality());\n'
     '                    b.writeVarInt(msg.scene());\n'
     '                    b.writeVarInt(msg.variant());\n')
edit("network/DreamPacket.java",
     '                b->new DreamPacket(b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt()),',
     '                b->new DreamPacket(b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),\n'
     '                        b.readVarInt(),b.readVarInt()),')
edit("network/DreamPacket.java",
     '                        packet.duration(),packet.morality())));',
     '                        packet.duration(),packet.morality(),packet.scene(),packet.variant())));')
edit("network/DreamPacket.java",
     '    public static void send(ServerPlayer player,int event,int kind,int ticks,int morality) {',
     '    public static void send(ServerPlayer player,int event,int kind,int ticks,int morality,int scene,int variant) {')
edit("network/DreamPacket.java",
     '                new DreamPacket(event,kind,ticks,morality));',
     '                new DreamPacket(event,kind,ticks,morality,scene,variant));')

dream="world/DreamSystem.java"
edit(dream,
     '        final int kind, sanityAtSleep, moralityAtSleep;\n',
     '        final int kind, sanityAtSleep, moralityAtSleep, scene, variant;\n')
edit(dream,
     '        Dream(ServerPlayer p, int kind,int sanity,int morality,long began) {\n',
     '        Dream(ServerPlayer p, int kind,int sanity,int morality,long began,int scene,int variant) {\n')
edit(dream,
     '            moralityAtSleep=morality;\n',
     '            moralityAtSleep=morality;\n            this.scene=scene;\n            this.variant=variant;\n')
edit(dream,
     '''        int sanity=mind.sanity, morality=mind.morality;
        int kind=classify(sanity,morality);
        mind.lastDreamDay=day;''',
     '''        int sanity=mind.sanity, morality=mind.morality;
        int kind=classify(sanity,morality);
        // Location is chosen at sleep, independent of good/uneasy/nightmare type.
        int scene=p.getRandom().nextInt(DreamScenes.COUNT);
        int variant=Math.floorMod(mind.dreamAppearanceIndex++,8);
        mind.lastDreamDay=day;''')
edit(dream,
     '        active.put(id,new Dream(p,kind,sanity,morality,now));\n',
     '        active.put(id,new Dream(p,kind,sanity,morality,now,scene,variant));\n')
edit(dream,
     '''        DreamPacket.send(p,0,kind,
                kind==CALM?CALM_TICKS:kind==NIGHTMARE?NIGHTMARE_TICKS:UNEASY_TICKS,morality);''',
     '''        DreamPacket.send(p,0,kind,
                kind==CALM?CALM_TICKS:kind==NIGHTMARE?NIGHTMARE_TICKS:UNEASY_TICKS,
                morality,scene,variant);''')
edit(dream,
     '''        PsychologyData.get(p.getServer()).change(p,-4,0);
        DreamPacket.send(p,2,NIGHTMARE,26,dream.moralityAtSleep);''',
     '''        PsychologyData.get(p.getServer()).change(p,-4,0);
        String consequence=DreamScenes.onNightmare(p,dream.scene);
        DreamPacket.send(p,2,NIGHTMARE,26,dream.moralityAtSleep,dream.scene,dream.variant);
        p.displayClientMessage(Component.literal(
                "Кошмар: "+DreamScenes.name(dream.scene)+". "+consequence).withStyle(ChatFormatting.RED),true);''')
edit(dream,
     '''        DreamPacket.send(p,1,CALM,28,dream.moralityAtSleep);
        PsychologyData.get(p.getServer()).change(p,5,0);
        // Good morality colors the shadow; the buff is a real reward for rest.
        int roll=p.getRandom().nextInt(4);
        switch(roll) {
            case 0 -> p.addEffect(new MobEffectInstance(MobEffects.REGENERATION,20*35,0,false,true));
            case 1 -> p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE,20*95,0,false,true));
            case 2 -> p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,20*150,0,false,true));
            default -> p.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED,20*150,0,false,true));
        }''',
     '''        DreamPacket.send(p,1,CALM,28,dream.moralityAtSleep,dream.scene,dream.variant);
        PsychologyData.get(p.getServer()).change(p,5,0);
        String reward=DreamScenes.onCalm(p,dream.scene);''')
edit(dream,
     '''        p.displayClientMessage(Component.literal("Тихая тень исчезла. Психика +5; тело отдохнуло.")
                .withStyle(ChatFormatting.GREEN),true);''',
     '''        p.displayClientMessage(Component.literal(
                "Светлый сон: "+DreamScenes.name(dream.scene)+". "+reward+"; психика +5")
                .withStyle(ChatFormatting.GREEN),true);''')
edit(dream,
     '        DreamPacket.send(p,1,UNEASY,18,dream.moralityAtSleep);',
     '        DreamPacket.send(p,1,UNEASY,18,dream.moralityAtSleep,dream.scene,dream.variant);')
edit(dream,
     '        if(old!=null) DreamPacket.send(p,3,old.kind,0,old.moralityAtSleep);',
     '        if(old!=null) DreamPacket.send(p,3,old.kind,0,old.moralityAtSleep,old.scene,old.variant);')

client="client/ClientDream.java"
edit(client,
     '    private static int active=-1, age, duration, finish=0, morality;\n',
     '    private static int active=-1, age, duration, finish=0, morality;\n'
     '    private static int scene, variant;\n')
edit(client,
     '    public static void receive(int event,int dream,int ticks,int atSleepMorality) {',
     '    public static void receive(int event,int dream,int ticks,int atSleepMorality,int location,int appearance) {')
edit(client,
     '            eventType=0;\n            morality=atSleepMorality;\n',
     '            eventType=0;\n            morality=atSleepMorality;\n'
     '            scene=location;\n            variant=appearance;\n')
edit(client,
     '''            eventType=event;
            morality=atSleepMorality;''',
     '''            eventType=event;
            morality=atSleepMorality;
            scene=location;
            variant=appearance;''')
region(client,
     '    private static void paint(GuiGraphics g,int w,int h) {',
     '    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e)',
     r'''    private static void paint(GuiGraphics g,int w,int h) {
        if(active<0 || Minecraft.getInstance().player==null) return;
        boolean nightmare=active==1;
        boolean uneasy=active==2;
        boolean jumpscare=eventType==2;
        int mid=w/2, horizon=h*56/100;
        // Lighting is defined by the type, NOT world daytime or moral value:
        // peaceful BRIGHT daylight; nightmare DARK NIGHT; uneasy SUNSET.
        int skyTop=nightmare?0xF4050610:uneasy?0xFFF0A367:0xFF84CAE9;
        int skyBottom=nightmare?0xFF101126:uneasy?0xFFE4A17A:0xFFDDF2DE;
        int ground=nightmare?0xFF08090D:uneasy?0xFF47364E:0xFF687A78;
        int steps=12;
        for(int i=0;i<steps;i++) {
            float fraction=i/(float)(steps-1);
            int mix=mix(skyTop,skyBottom,fraction);
            g.fill(0,i*horizon/steps,w,(i+1)*horizon/steps+1,mix);
        }
        g.fill(0,horizon,w,h,ground);

        if(scene==0) paintMine(g,w,h,horizon,nightmare,uneasy);
        else if(scene==1) paintGrove(g,w,h,horizon,nightmare,uneasy);
        else paintRuins(g,w,h,horizon,nightmare,uneasy);

        // The same shadow can be comforting or threatening. It does NOT
        // override the distinct atmosphere of the place visited.
        int x=mid+(age/8)%5-2, y=horizon-48;
        int body=nightmare?0xF3000004:uneasy?0xD83F2A3D:0xA64B6670;
        g.fill(x-12,y,x+12,y+29,body);
        g.fill(x-17,y+29,x+17,y+67,body);
        g.fill(x-25,y+31,x-17,y+62,body);
        g.fill(x+17,y+31,x+25,y+62,body);
        if(nightmare) {
            g.fill(x-9,y+12,x-4,y+16,0xFFFF3750);
            g.fill(x+4,y+12,x+9,y+16,0xFFFF3750);
            for(int i=0;i<8;i++) {
                int gx=(i*71+age*17+variant*29)%Math.max(1,w);
                int gy=(i*43+age*9+variant*13)%Math.max(1,h);
                g.fill(gx,gy,gx+10+i%4*7,gy+2,0xA5EE315A);
            }
        }

        // Scene title doubles as clear feedback that reward depends on CONTENT.
        String label=scene==0?"ШАХТА":scene==1?"РОЩА":"РУИНЫ";
        int labelColor=nightmare?0xFFDBA0A6:uneasy?0xFFFFD2A5:0xFF223638;
        g.drawCenteredString(Minecraft.getInstance().font,label,mid,17,labelColor);
        if(uneasy) {
            g.drawCenteredString(Minecraft.getInstance().font,
                    "ЗАКАТНЫЙ СОН",mid,h-34,0xFFEED4C0);
        } else if(!nightmare) {
            g.drawCenteredString(Minecraft.getInstance().font,
                    "ТИХАЯ ТЕНЬ",mid,h-39,0xFFDCF9E7);
            if(eventType==0) g.drawCenteredString(Minecraft.getInstance().font,
                    "ОТДЫХ • "+Math.max(0,(duration-age+19)/20)+" с",
                    mid,h-23,0xFFD5F8E6);
        } else if(jumpscare) {
            g.fill(0,0,w,h,(age%4<2)?0xD1E60024:0xF0000105);
            g.drawCenteredString(Minecraft.getInstance().font,
                    "ПРОСНИСЬ!",mid,h/2,0xFFFFB1BC);
        } else {
            g.drawCenteredString(Minecraft.getInstance().font,
                    "ОН ЗДЕСЬ",mid,h-30,0xFFFF8795);
        }
    }

    private static void paintMine(GuiGraphics g,int w,int h,int horizon,
                                  boolean dark,boolean dusk) {
        int center=w/2;
        int frame=dark?0xFF29202D:dusk?0xFF69494B:0xFF6E6454;
        int stone=dark?0xFF101019:dusk?0xFF67495B:0xFFB3A89C;
        int accent=dark?0xFFB73361:dusk?0xFFE8A267:0xFFFFE7AB;
        // Tunnel perspective, with eight different support/ore layouts.
        int spread=Math.max(30,w/5);
        g.fill(0,horizon/4,spread,h,stone);
        g.fill(w-spread,horizon/4,w,h,stone);
        g.fill(0,0,w,horizon/5,frame);
        for(int i=0;i<4;i++) {
            int depth=3-i;
            int offset=depth*(12+variant%4*4);
            int left=center-(w/8+offset);
            int right=center+(w/8+offset);
            int top=horizon/4-depth*7;
            int bottom=Math.min(h,horizon+depth*18);
            g.fill(left-6,top,left,Math.min(h,bottom),frame);
            g.fill(right,top,right+6,Math.min(h,bottom),frame);
            g.fill(left-6,top,right+6,top+6,frame);
        }
        // Randomized but stable veins and lantern placements per dream variant.
        for(int i=0;i<12;i++) {
            int x=(variant*37+i*97)%Math.max(1,w);
            int y=horizon/6+(i*47+variant*29)%Math.max(1,horizon-horizon/6);
            g.fill(x,y,x+3+(i%4)*2,y+4,accent);
        }
        if(!dark) {
            int lx=Math.max(12,w/5+variant*7);
            g.fill(lx,horizon/3,lx+7,horizon/3+14,0xFFFFEAD2);
        }
        int track=dark?0xFF45303B:dusk?0xFFBC8B70:0xFFC6C4AE;
        for(int i=0;i<8;i++) {
            int y=horizon+(i*i*(h-horizon))/64;
            g.fill(center-20-i*5,y,center+20+i*5,y+2,track);
        }
    }

    private static void paintGrove(GuiGraphics g,int w,int h,int horizon,
                                   boolean dark,boolean dusk) {
        int trunk=dark?0xFF100C16:dusk?0xFF50343E:0xFF73594C;
        int leaf=dark?0xFF1C1522:dusk?0xFF8C5C66:0xFF7CB098;
        for(int i=0;i<10;i++) {
            int px=((i*127+variant*39)%Math.max(1,w));
            int y=horizon-22-(i%3)*31;
            int width=6+(i+variant)%5*3;
            g.fill(px,y,px+width,horizon+45,trunk);
            g.fill(px-18,y-33,px+width+18,y+21,leaf);
        }
        if(!dark) {
            int pool=dusk?0xFFEAB385:0xFFE0F8C1;
            g.fill(w/2-33,horizon+14,w/2+31,horizon+17,pool);
        }
    }

    private static void paintRuins(GuiGraphics g,int w,int h,int horizon,
                                   boolean dark,boolean dusk) {
        int column=dark?0xFF101019:dusk?0xFF695161:0xFFB3B8AE;
        int edge=dark?0xFF3F3444:dusk?0xFFBC918E:0xFFDBDACE;
        for(int i=0;i<8;i++) {
            int x=(i*123+variant*31)%Math.max(1,w);
            int y=horizon/5+(i+variant)%4*25;
            int wide=15+(i+variant)%4*9;
            g.fill(x,y,x+wide,horizon+39,column);
            g.fill(x-4,y,x+wide+4,y+5,edge);
            if(i%3==variant%3)g.fill(x+4,y+16,x+wide-4,y+19,edge);
        }
    }

    private static int mix(int from,int to,float factor) {
        int a=(int)(((from>>>24)&255)*(1-factor)+((to>>>24)&255)*factor);
        int r=(int)(((from>>>16)&255)*(1-factor)+((to>>>16)&255)*factor);
        int g=(int)(((from>>>8)&255)*(1-factor)+((to>>>8)&255)*factor);
        int b=(int)((from&255)*(1-factor)+(to&255)*factor);
        return (a<<24)|(r<<16)|(g<<8)|b;
    }
''')
edit(client,
     '        active=-1; age=0; duration=0; finish=0; eventType=0; morality=0;',
     '        active=-1; age=0; duration=0; finish=0; eventType=0; morality=0;\n'
     '        scene=0; variant=0;')

props=ROOT/"gradle.properties"
src=props.read_text(encoding="utf-8")
assert src.count("0.19.0")==1
props.write_text(src.replace("0.19.0","0.20.0"),encoding="utf-8")
print("PASS v0.20: dark nightmare, bright pleasant, sunset uneasy; randomized scenes; mine-specific 300/180s")
