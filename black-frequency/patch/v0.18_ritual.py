#!/usr/bin/env python3
"""v0.18: silent handoff, damaged note UI, beacon ritual and boss field.
Apply strictly after v0.17; no modifications to the main branch.
"""
from pathlib import Path
ROOT=Path("black-frequency/project")
JAVA=ROOT/"src/main/java/com/blackfrequency/hollowsignal"
RES=ROOT/"src/main/resources"

def edit(rel,old,new):
    p=JAVA/rel
    s=p.read_text(encoding="utf-8")
    assert s.count(old)==1,(rel,old[:100],s.count(old))
    p.write_text(s.replace(old,new,1),encoding="utf-8")

def fresh(rel,code):
    p=JAVA/rel
    p.parent.mkdir(parents=True,exist_ok=True)
    assert not p.exists(),str(p)
    p.write_text(code.strip()+"\n",encoding="utf-8")

fresh("registry/RitualItems.java",r'''
package com.blackfrequency.hollowsignal.registry;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.blackfrequency.hollowsignal.network.FragmentNotePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class RitualItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BlackFrequency.MODID);
    public static final RegistryObject<Item> FRACTURED_NOTE = ITEMS.register("fractured_note",
            () -> new Item(new Item.Properties().stacksTo(1)) {
                @Override public InteractionResultHolder<ItemStack> use(
                        Level level, Player player, InteractionHand hand) {
                    if (!level.isClientSide && player instanceof ServerPlayer serverPlayer)
                        FragmentNotePacket.send(serverPlayer);
                    return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide);
                }
            });
    public static final RegistryObject<Item> RIFT_FRAGMENT = ITEMS.register("rift_fragment",
            () -> new Item(new Item.Properties().stacksTo(64).fireResistant()));
    private RitualItems() {}
}
''')

fresh("network/FragmentNotePacket.java",r'''
package com.blackfrequency.hollowsignal.network;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.blackfrequency.hollowsignal.client.FragmentNoteScreen;
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

/** Server only opens note after a real item use. No client-origin packets. */
public record FragmentNotePacket() {
    private static final String VERSION="1";
    private static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BlackFrequency.MODID,"fractured_note"),
            ()->VERSION,VERSION::equals,VERSION::equals);
    public static void register() {
        CHANNEL.registerMessage(0,FragmentNotePacket.class,
                (message,buffer)->{},
                buffer->new FragmentNotePacket(), FragmentNotePacket::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    private static void receive(FragmentNotePacket message,Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                ()->()->FragmentNoteScreen.open()));
        context.get().setPacketHandled(true);
    }
    public static void send(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new FragmentNotePacket());
    }
}
''')

fresh("client/FragmentNoteScreen.java",r'''
package com.blackfrequency.hollowsignal.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Story item's on-use readable pages. Corrupted sections are tiny dynamic
 * fissures in the paper; the missing solution is NOT inserted as lore yet.
 */
public final class FragmentNoteScreen extends Screen {
    private static final String[][] PAGES = {
            {
                "ПРОТОКОЛ ВОССТАНОВЛЕНИЯ",
                "Центральная конструкция: МАЯК.",
                "Непосредственно под маяком:",
                "один НЕЗЕРИТОВЫЙ БЛОК.",
                "Вокруг него — блоки основания маяка.",
                "",
                "                 [ФРАГМЕНТ УТРАЧЕН]",
                "█████╱╱███     ╱█████",
                "Место разрыва:   [???]"
            },
            {
                "ИСТОЧНИК ПИТАНИЯ",
                "Четыре кристалла Края",
                "по разным сторонам основания.",
                "Напитать луч и направить в небо.",
                "███╱██╱███████",
                "",
                "Когда структура будет атакована,",
                "поле удержит существо внутри.",
                "...[ЧАСТЬ СХЕМЫ ПОГЛОЩЕНА]..."
            },
            {
                "ВОССТАНОВЛЕНИЕ ЗАРЯДА",
                "После повреждения сущности",
                "из её разломов выпадут осколки.",
                "ПЯТЬ осколков = следующий заряд.",
                "Каждые 25% её здоровье искажается.",
                "██╱╱██▌██╱██▌",
                "",
                "ПОСЛЕДНИЙ ШАГ: [НЕЧИТАЕМО]",
                "МАЛЫЕ РАЗЛОМЫ НЕ ДАЮТ ПРОЧИТАТЬ."
            }
    };
    private int page=0;
    private int frames=0;
    private FragmentNoteScreen() { super(Component.literal("Повреждённая записка")); }
    public static void open() { Minecraft.getInstance().setScreen(new FragmentNoteScreen()); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { frames++; }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if (key==GLFW.GLFW_KEY_RIGHT || key==GLFW.GLFW_KEY_D) {
            page=(page+1)%PAGES.length; return true;
        }
        if (key==GLFW.GLFW_KEY_LEFT || key==GLFW.GLFW_KEY_A) {
            page=(page+PAGES.length-1)%PAGES.length; return true;
        }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseScrolled(double x,double y,double delta) {
        page=(page+(delta<0?1:PAGES.length-1))%PAGES.length;
        return true;
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick) {
        renderBackground(g);
        int left=width/2-150, top=height/2-115;
        g.fill(left-4,top-4,left+304,top+232,0xE5110C17);
        g.fill(left,top,left+300,top+224,0xF5C7BEAB);
        g.fill(left+8,top+8,left+292,top+216,0xFFCFC4AE);
        g.drawCenteredString(font,"██ ПОВРЕЖДЁННАЯ СХЕМА ██",width/2,top+14,0xFF331326);
        for(int line=0;line<PAGES[page].length;line++) {
            String value=PAGES[page][line];
            int textColor=value.contains("████")||value.contains("РАЗЛОМ")?
                    0xFF8D234F:0xFF292026;
            g.drawString(font,value,left+16,top+37+line*16,textColor,false);
        }
        // Animated micro-ruptures on the paper itself, never on real blocks.
        for(int i=0;i<9;i++) {
            int x=left+29+(i*47+frames/7)%246;
            int y=top+36+(i*59)%161;
            int col=(i+frames/5)%4==0?0xD9A42B8C:0xB537172C;
            g.fill(x,y,x+2,y+10,col);
            g.fill(x+2,y+7,x+6,y+9,col);
        }
        g.drawCenteredString(font,"ЛИСТ "+(page+1)+"/"+PAGES.length+"  ◀  ▶  /  ESC",
                width/2,top+198,0xFF4C2538);
        super.render(g,mouseX,mouseY,partialTick);
    }
}
''')

fresh("world/RitualData.java",r'''
package com.blackfrequency.hollowsignal.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.UUID;

/** All ritual/progression data authoritative in a SINGLE overworld save. */
public final class RitualData extends SavedData {
    public static final String KEY="hollowsignal_ritual_v18";
    public boolean noteDelivered;
    public boolean fightActive;
    public boolean ritualFinished;
    public UUID bossId;
    public long beaconPosition;
    public int phase; // 0 .. 3, boss increasingly corrupted as rifts close.
    public int recharge;
    public int shardsIssued;
    public long lastShot;
    public int shots;
    public int bossHealth=200;

    public static RitualData get(MinecraftServer server) {
        return server.getLevel(Level.OVERWORLD).getDataStorage().computeIfAbsent(
                RitualData::load, RitualData::new, KEY);
    }
    public static RitualData load(CompoundTag nbt) {
        RitualData data=new RitualData();
        data.noteDelivered=nbt.getBoolean("noteDelivered");
        data.fightActive=nbt.getBoolean("fightActive");
        data.ritualFinished=nbt.getBoolean("ritualFinished");
        if(nbt.hasUUID("boss")) data.bossId=nbt.getUUID("boss");
        data.beaconPosition=nbt.getLong("beacon");
        data.phase=nbt.getInt("phase");
        data.recharge=nbt.getInt("recharge");
        data.shardsIssued=nbt.getInt("shardsIssued");
        data.lastShot=nbt.getLong("lastShot");
        data.shots=nbt.getInt("shots");
        data.bossHealth=nbt.getInt("bossHealth");
        return data;
    }
    @Override public CompoundTag save(CompoundTag nbt) {
        nbt.putBoolean("noteDelivered",noteDelivered);
        nbt.putBoolean("fightActive",fightActive);
        nbt.putBoolean("ritualFinished",ritualFinished);
        if(bossId!=null) nbt.putUUID("boss",bossId);
        nbt.putLong("beacon",beaconPosition);
        nbt.putInt("phase",phase);
        nbt.putInt("recharge",recharge);
        nbt.putInt("shardsIssued",shardsIssued);
        nbt.putLong("lastShot",lastShot);
        nbt.putInt("shots",shots);
        nbt.putInt("bossHealth",bossHealth);
        return nbt;
    }
}
''')

fresh("world/RitualSystem.java",r'''
package com.blackfrequency.hollowsignal.world;

import com.blackfrequency.hollowsignal.HorrorConfig;
import com.blackfrequency.hollowsignal.entity.WatcherEntity;
import com.blackfrequency.hollowsignal.registry.ModEntities;
import com.blackfrequency.hollowsignal.registry.RitualItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Silent good-fragment encounter, crystal-powered beacon, field and recharge. */
public final class RitualSystem {
    private UUID helper;
    private long helperAppeared;
    private final static String HELPER_TAG="bf_silent_fragment";

    @SubscribeEvent public void onTick(TickEvent.PlayerTickEvent e) {
        if(e.phase!=TickEvent.Phase.END || !(e.player instanceof ServerPlayer p)
                || !HorrorConfig.ENABLED.get() || !p.isAlive()) return;
        ServerLevel level=p.serverLevel();
        if(level.dimension()!=Level.OVERWORLD || level.getGameTime()%20!=0) return;
        if(!PsychologyData.get(p.getServer()).ruptureActive()
                || PsychologyData.get(p.getServer()).ruptureStage==0) return;
        RitualData d=RitualData.get(p.getServer());
        if(d.noteDelivered || d.ritualFinished) return;
        // Not on every 0 of the map: exactly the world center, X=0 Z=0.
        if(Math.abs(p.getX())>14 || Math.abs(p.getZ())>14) return;
        if(!level.hasChunkAt(new BlockPos(0,p.blockPosition().getY(),0))) return;
        BlockPos at=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(0,0,0));
        if(p.distanceToSqr(at.getX()+.5,at.getY(),at.getZ()+.5)>14*14) return;
        WatcherEntity watcher=helper==null?null:
                (level.getEntity(helper) instanceof WatcherEntity w ? w:null);
        if(watcher==null) {
            watcher=ModEntities.WATCHER.get().create(level);
            if(watcher==null) return;
            watcher.moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);
            watcher.setNoAi(true);
            watcher.setSilent(true);
            watcher.setInvulnerable(true);
            watcher.setCustomName(Component.literal("░▒▓").withStyle(ChatFormatting.GRAY));
            watcher.setCustomNameVisible(false);
            watcher.addTag(HELPER_TAG);
            if(!level.addFreshEntity(watcher)) return;
            helper=watcher.getUUID();
            helperAppeared=level.getGameTime();
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL,watcher.getX(),
                watcher.getY()+1,watcher.getZ(),3,.4,.7,.4,.02);
        if(level.getGameTime()-helperAppeared < 40 || p.distanceToSqr(watcher)>4*4) return;
        // SILENT handoff: no monologue, no chat dialogue, no spoken hint.
        ItemStack note=new ItemStack(RitualItems.FRACTURED_NOTE.get());
        if(!p.getInventory().add(note)) p.drop(note,false);
        level.sendParticles(ParticleTypes.SQUID_INK,watcher.getX(),
                watcher.getY()+1,watcher.getZ(),48,.7,.9,.7,.12);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL,watcher.getX(),
                watcher.getY()+1,watcher.getZ(),48,.7,.9,.7,.12);
        watcher.discard();
        helper=null;
        d.noteDelivered=true;
        d.setDirty();
        // Damage is already part of the item, caused by the interruption.
    }

    @SubscribeEvent public void onInteract(PlayerInteractEvent.RightClickBlock e) {
        if(e.getHand()!=InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)
                || !(e.getLevel() instanceof ServerLevel level)
                || level.dimension()!=Level.OVERWORLD || !HorrorConfig.ENABLED.get()) return;
        BlockPos pos=e.getPos();
        if(!level.getBlockState(pos).is(Blocks.BEACON)) return;
        RitualData d=RitualData.get(p.getServer());
        if(!PsychologyData.get(p.getServer()).ruptureActive()) return;
        // Right-click beacon with charged fragment; don't accidentally consume a vanilla item.
        if(d.fightActive && d.beaconPosition==pos.asLong()
                && p.getMainHandItem().is(RitualItems.RIFT_FRAGMENT.get())) {
            e.setCanceled(true); e.setCancellationResult(InteractionResult.SUCCESS);
            if(d.recharge>=5) {
                status(p,"Маяк уже заряжен");
                return;
            }
            p.getMainHandItem().shrink(1);
            d.recharge++;
            d.setDirty();
            status(p,"Заряд маяка: "+d.recharge+"/5");
            if(d.recharge==5) RitualBattle.shoot(level,d,pos);
            return;
        }
        if(d.fightActive || d.ritualFinished || !p.isShiftKeyDown()
                || !p.getMainHandItem().isEmpty()) return;
        e.setCanceled(true); e.setCancellationResult(InteractionResult.SUCCESS);
        if(!validBase(level,pos)) {
            status(p,"Требуется незеритовый блок прямо под маяком и полный слой основания 3×3");
            return;
        }
        List<EndCrystal> crystals=findCrystals(level,pos);
        if(crystals.size()!=4) {
            status(p,"Вокруг маяка нужны четыре кристалла Края по разным сторонам");
            return;
        }
        crystals.forEach(EndCrystal::discard); // consumed into the initial power supply.
        d.beaconPosition=pos.asLong();
        d.fightActive=true;
        d.bossHealth=200;
        d.shardsIssued=0;
        d.recharge=0;
        d.phase=0;
        d.shots=0;
        d.setDirty();
        RitualBattle.start(level,d,pos);
        status(p,"Маяк запитан. Луч закрывает разломы. Но поле нарушено...");
    }

    public static boolean validBase(ServerLevel level,BlockPos beacon) {
        if(!level.getBlockState(beacon).is(Blocks.BEACON)
                || !level.getBlockState(beacon.below()).is(Blocks.NETHERITE_BLOCK)) return false;
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) {
            BlockPos b=beacon.offset(x,-1,z);
            if(!level.hasChunkAt(b) || !level.getBlockState(b).is(BlockTags.BEACON_BASE_BLOCKS))
                return false;
        }
        return true;
    }
    /** One End Crystal in each corner-quadrant, never accidentally reuse a crystal. */
    private static List<EndCrystal> findCrystals(ServerLevel level,BlockPos beacon) {
        List<EndCrystal> found=new ArrayList<>();
        List<EndCrystal> around=level.getEntitiesOfClass(EndCrystal.class,
                new AABB(beacon).inflate(6),EndCrystal::isAlive);
        for(int sx:new int[]{-1,1}) for(int sz:new int[]{-1,1}) {
            EndCrystal selected=null;
            for(EndCrystal c:around) {
                double dx=c.getX()-(beacon.getX()+.5);
                double dz=c.getZ()-(beacon.getZ()+.5);
                if(dx*sx>1.5 && dz*sz>1.5 && Math.abs(dx)<=5 && Math.abs(dz)<=5) {
                    selected=c; break;
                }
            }
            if(selected!=null && !found.contains(selected)) found.add(selected);
        }
        return found;
    }
    private static void status(ServerPlayer player,String text) {
        player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.DARK_PURPLE),true);
    }
}
''')

edit("BlackFrequency.java",
     'import com.blackfrequency.hollowsignal.registry.ModSounds;\n',
     'import com.blackfrequency.hollowsignal.registry.ModSounds;\n'
     'import com.blackfrequency.hollowsignal.registry.RitualItems;\n'
     'import com.blackfrequency.hollowsignal.network.FragmentNotePacket;\n')
edit("BlackFrequency.java",
     '        ModSounds.SOUNDS.register(modBus);\n',
     '        ModSounds.SOUNDS.register(modBus);\n        RitualItems.ITEMS.register(modBus);\n        FragmentNotePacket.register();\n')
edit("BlackFrequency.java",
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.EntityEncounters());\n',
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.EntityEncounters());\n'
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.RitualSystem());\n'
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.RitualBattle());\n')

# Rendered paper is intentionally vanilla-based until dedicated art is decided.
import json
assets=RES/"assets/hollowsignal"
for name,texture in {"fractured_note":"minecraft:item/paper",
                      "rift_fragment":"minecraft:item/echo_shard"}.items():
    path=assets/"models/item"/(name+".json")
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps({"parent":"minecraft:item/generated",
                               "textures":{"layer0":texture}},indent=2)+"\n")
for lang,names in {
    "ru_ru":{"item.hollowsignal.fractured_note":"Записка с микроразломами",
             "item.hollowsignal.rift_fragment":"Осколок разлома"},
    "en_us":{"item.hollowsignal.fractured_note":"Fractured note",
             "item.hollowsignal.rift_fragment":"Rift fragment"}
}.items():
    path=assets/"lang"/(lang+".json")
    entries=json.loads(path.read_text(encoding="utf-8-sig")) if path.exists() else {}
    entries.update(names)
    path.write_text(json.dumps(entries,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")

properties=ROOT/"gradle.properties"
edit("BlackFrequency.java","NOT_AN_EXISTING_ANCHOR", "NEVER") if False else None
s=properties.read_text()
assert s.count("0.17.0")==1
properties.write_text(s.replace("0.17.0","0.18.0"))
print("PASS v0.18 ritual: silent note, readable glitch pages, four end crystals, 3x3 beacon base")
