#!/usr/bin/env python3
"""BLACK FREQUENCY v0.21: plot-aware anomaly director without removing legacy events.

Order: restore source, v0.14, v0.15 ... v0.20, THEN this overlay.
Existing manual anomaly commands, IDs, packet channels and all private scenes
remain unchanged; only automatic scheduling and contextual whispers are updated.
"""
from pathlib import Path
ROOT=Path("black-frequency/project")
JAVA=ROOT/"src/main/java/com/blackfrequency/hollowsignal"

def edit(name, old, new):
    p=JAVA/name
    s=p.read_text(encoding="utf-8")
    n=s.count(old)
    if n==1:
        p.write_text(s.replace(old,new,1),encoding="utf-8")
        return
    # Source bundles sometimes change only Java indentation between releases.
    # Match the COMPLETE anchor, retaining literal tokens but ignoring line indent.
    import re
    parts=old.split("\n")
    pattern=r"(?m)^[ \\t]*"+r"\n[ \\t]*".join(re.escape(part.lstrip(" \\t")) for part in parts)
    matches=list(re.finditer(pattern,s))
    assert len(matches)==1, (name,"ambiguous/absent anchor",n,len(matches),repr(old[:95]))
    match=matches[0]
    p.write_text(s[:match.start()]+new+s[match.end():],encoding="utf-8")

def fresh(name, source):
    p=JAVA/name
    p.parent.mkdir(parents=True,exist_ok=True)
    assert not p.exists(), ("refusing overwrite",p)
    p.write_text(source.strip()+"\n",encoding="utf-8")

fresh("world/StoryAnomalies.java", r'''
package com.blackfrequency.hollowsignal.world;

import com.blackfrequency.hollowsignal.HorrorConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical ten-day story clock and phase-aware anomaly selection.
 *
 * Phase 0: days 1-2, harmless uncertain sounds and omens.
 * Phase 1: days 3-5, dreams, false discoveries, helpful shadow at high morality.
 * Phase 2: days 6-9, increasingly hostile personal distortions.
 * Phase 3: day 10+, both stats are pressured separately; pre-rupture climax.
 * Phase 4: ONE real server-wide rupture; legacy global effects stay server-owned.
 *
 * Pre-rupture images MUST remain personal; no blocks/entities are generated here.
 */
public final class StoryAnomalies {
    private final Map<UUID, Long> lastVoice = new HashMap<>();
    private final Map<UUID, Integer> lineIndex = new HashMap<>();

    private static final String[] FRIENDLY = {
            "Тень: Не всякий звук принадлежит этому миру.",
            "Тень: Проверяй то, чему веришь.",
            "Тень: Я могу быть рядом, даже когда ты меня не видишь.",
            "Тень: Не позволяй страху принимать решения за тебя.",
            "Тень: Мы слышим друг друга не всегда."
    };
    private static final String[] GUILT = {
            "Сигнал: Ты помнишь, что сделал?",
            "Сигнал: Сколько ещё ты разрушишь?",
            "Сигнал: Они не забудут.",
            "Сигнал: Не смотри назад."
    };
    private static final String[] MALICE = {
            "█ ТЫ СДАШЬСЯ █",
            "НЕТ ВЫХОДА... НЕТ... ██",
            "С...Д...А...Й...С...Я",
            "УЖЕ ПОЗДНО",
            "█ ТЫ НЕ УДЕРЖИШЬ МИР █"
    };
    private static final String[] LEAKED = {
            "СДА... ██ ...СХЕМУ... ███",
            "ТЫ НЕ... ██ ...СДАВАЙСЯ...",
            "█ МА... ЯК... ██ ...НЕ СЛУШАЙ...",
            "УЖЕ ПО... █ ...ДО ПРАВДЫ...",
            "█ КР...СТ... ███ ...ВСПОМНИ..."
    };
    private static final String[] DAY_HINT = {
            "Сигнал найден. Мир выглядит привычным.",
            "Шум стал ближе.",
            "С третьего дня сны больше не безопасны.",
            "В знакомых местах появляются ошибки.",
            "Не все воспоминания принадлежат тебе.",
            "Что-то начинает отвечать.",
            "Границы личных видений истончаются.",
            "Чужие голоса используют знакомые слова.",
            "Внутри шума слышатся два разных голоса.",
            "ДЕНЬ 10. ТЕБЯ БОЛЬШЕ НЕ ОСТАВЯТ В ПОКОЕ."
    };

    public static long day(ServerPlayer player) {
        PsychologyData data=PsychologyData.get(player.getServer());
        long absoluteDay=Math.floorDiv(player.getServer().overworld().getDayTime(),24000L);
        if(data.storyStartDay<0) {
            // Existing long-lived worlds don't immediately jump to day 10.
            data.storyStartDay=absoluteDay;
            data.setDirty();
        }
        return Math.max(1L,absoluteDay-data.storyStartDay+1L);
    }

    public static int phase(ServerPlayer p) {
        if(PsychologyData.get(p.getServer()).ruptureActive()) return 4;
        long n=day(p);
        if(n<=2) return 0;
        if(n<=5) return 1;
        if(n<=9) return 2;
        return 3;
    }

    /** Used only by the automatic director: manual OP tests still work. */
    public static boolean allowed(ServerPlayer p,String id,int oldTension,boolean major) {
        int stage=phase(p);
        if(stage>=3) return true;
        if(stage==0) {
            // Day 1/2 cannot silently spawn fake villages, violent ghosts
            // or deceptive system/OS-like interruption screens.
            return !major && oldTension<=31 && !advanced(id);
        }
        if(stage==1) {
            return oldTension<=57 && !hardScreen(id)
                    && !"dream_cave".equals(id) && !"mimic_player".equals(id)
                    && !deepFracture(id);
        }
        return oldTension<=83 && !"fake_kick_sequence".equals(id);
    }

    private static boolean hardScreen(String id) {
        return id.startsWith("fake_") && (id.endsWith("screen")
                || id.equals("fake_disconnect") || id.equals("fake_main_menu")
                || id.equals("fake_world_loading") || id.equals("fake_kick_sequence"));
    }
    private static boolean deepFracture(String id) {
        return id.equals("no_way_out") || id.equals("stone_coffin")
                || id.equals("closing_room") || id.equals("corridor_collapse")
                || id.equals("system_echo") || id.equals("time_dislocation")
                || id.equals("memory_scars");
    }
    private static boolean advanced(String id) {
        return id.startsWith("fake_") || id.startsWith("phantom_")
                || id.startsWith("false_") || id.startsWith("watcher")
                || id.startsWith("closing_") || id.startsWith("breathing_")
                || id.startsWith("mirror_") || id.startsWith("mimic_")
                || id.contains("cave") || id.contains("corridor")
                || id.contains("screen") || id.equals("stone_coffin");
    }

    /**
     * Morality decides what kind of intention the distorted world conveys;
     * sanity continues to determine overall perception and threat frequency.
     */
    public static int weight(ServerPlayer player,String id,boolean major) {
        var mind=PsychologyData.get(player.getServer()).mind(player);
        int stage=phase(player);
        boolean ominous=major || id.contains("stalker") || id.contains("voice")
                || id.contains("mimic") || id.contains("coffin")
                || id.contains("pressure") || id.contains("dead_end")
                || id.contains("watcher") || id.contains("mirror");
        int multiplier=1;
        if(mind.morality>=75) multiplier=ominous?1:3;
        else if(mind.morality<=30) multiplier=ominous?4:1;
        else multiplier=ominous?2:2;
        if(stage>=3 && ominous) multiplier+=2;
        if(stage==4 && ominous) multiplier+=2;
        return Math.max(1,multiplier);
    }

    public static float frequency(ServerPlayer player) {
        return switch(phase(player)) {
            case 0 -> .52f;
            case 1 -> .78f;
            case 2 -> 1.05f;
            case 3 -> 1.65f;
            default -> 1.8f;
        };
    }

    /** Dramatic response after a REAL anomaly; never writes random hints to chat. */
    public void afterAnomaly(ServerPlayer p,String anomaly) {
        var mind=PsychologyData.get(p.getServer()).mind(p);
        long now=p.getServer().overworld().getGameTime();
        long cooldown=20L*110; // Sparse voice: every few minutes, not every scare.
        if(now-lastVoice.getOrDefault(p.getUUID(),-cooldown)>cooldown
                && p.getRandom().nextInt(3)==0) {
            int phase=phase(p);
            String voice=null;
            if(phase<4 && mind.sanity>=72 && mind.morality>=80) {
                voice=FRIENDLY[next(p)%FRIENDLY.length];
            } else if(phase<4 && mind.morality<=30) {
                voice=GUILT[next(p)%GUILT.length];
            } else if(phase>=3 || mind.sanity<=32) {
                boolean afterNote=phase==4 && RitualData.get(p.getServer()).noteDelivered;
                // Once the note is known, the good fragment's lines may bleed
                // into the hostile entity's corrupted words; no recipe spoiler.
                voice=(afterNote && next(p)%5==0)
                        ? LEAKED[next(p)%LEAKED.length]
                        : MALICE[next(p)%MALICE.length];
            }
            if(voice!=null) {
                lastVoice.put(p.getUUID(),now);
                p.displayClientMessage(Component.literal(voice).withStyle(
                        phase==4?ChatFormatting.DARK_RED:
                                mind.morality>=80?ChatFormatting.GRAY:ChatFormatting.DARK_PURPLE),true);
                if(phase<4 && mind.morality>=80)
                    p.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME,SoundSource.AMBIENT,.22f,.8f);
            }
        }
    }

    private int next(ServerPlayer player) {
        int value=lineIndex.getOrDefault(player.getUUID(),0);
        lineIndex.put(player.getUUID(),value+1);
        return value;
    }

    @SubscribeEvent
    public void tick(TickEvent.PlayerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || !(event.player instanceof ServerPlayer p)
                || !HorrorConfig.ENABLED.get() || !HorrorConfig.PSYCHOLOGY_SYSTEM.get()
                || p.isSpectator() || p.isCreative() || !p.isAlive()
                || p.isSleeping()) return;
        long now=p.getServer().overworld().getGameTime();
        if(now%20!=0) return;
        var saved=PsychologyData.get(p.getServer());
        var mind=saved.mind(p);
        long currentDay=day(p);
        int stage=phase(p);
        if(mind.lastStoryDay<currentDay) {
            mind.lastStoryDay=currentDay;
            saved.setDirty();
            if(stage<4) {
                String caption=DAY_HINT[(int)Math.min(currentDay,10L)-1];
                p.displayClientMessage(Component.literal(caption)
                        .withStyle(currentDay>=10?ChatFormatting.DARK_RED:ChatFormatting.GRAY),true);
            }
        }
        if(stage==3 && now%(20*12L)==0 && !saved.ruptureActive()) {
            // Separate causes: sanity describes hallucinations; morality is
            // ethical resistance that weakens when the entity is near.
            int fear=mind.sanity<=35?-3:-2;
            int guilt=mind.morality<=35?-2:-1;
            saved.change(p,fear,guilt);
            if(mind.morality<=30 && now%(20*48L)==0) {
                p.displayClientMessage(Component.literal("██ СДАЙСЯ ██")
                        .withStyle(ChatFormatting.DARK_RED),true);
            }
        }
    }

    @SubscribeEvent public void leave(PlayerEvent.PlayerLoggedOutEvent e) {
        if(e.getEntity() instanceof ServerPlayer p) {
            lastVoice.remove(p.getUUID());
            lineIndex.remove(p.getUUID());
        }
    }
}
''')

# Persist a shared story baseline and a player-specific daily milestone.
edit("world/PsychologyData.java",
     '    public long ruptureCooldownUntil;\n',
     '    public long ruptureCooldownUntil;\n    public long storyStartDay = -1;\n')
edit("world/PsychologyData.java",
     '        public long lastDreamDay = -1;\n',
     '        public long lastDreamDay = -1;\n        public long lastStoryDay = -1;\n')
edit("world/PsychologyData.java",
     '        d.ruptureCooldownUntil = tag.getLong("cooldownUntil");\n',
     '        d.ruptureCooldownUntil = tag.getLong("cooldownUntil");\n'
     '        d.storyStartDay = tag.contains("storyStartDay") ? tag.getLong("storyStartDay") : -1;\n')
edit("world/PsychologyData.java",
     '        tag.putLong("cooldownUntil", ruptureCooldownUntil);\n',
     '        tag.putLong("cooldownUntil", ruptureCooldownUntil);\n'
     '        tag.putLong("storyStartDay", storyStartDay);\n')
edit("world/PsychologyData.java",
     '            m.lastDreamDay = t.contains("lastDreamDay") ? t.getLong("lastDreamDay") : -1;\n',
     '            m.lastDreamDay = t.contains("lastDreamDay") ? t.getLong("lastDreamDay") : -1;\n'
     '            m.lastStoryDay = t.contains("lastStoryDay") ? t.getLong("lastStoryDay") : -1;\n')
edit("world/PsychologyData.java",
     '            t.putLong("lastDreamDay", m.lastDreamDay);\n',
     '            t.putLong("lastDreamDay", m.lastDreamDay);\n'
     '            t.putLong("lastStoryDay", m.lastStoryDay);\n')

director="world/HorrorDirector.java"
edit(director,
     '''        float change = 0.45f; // Ambient tension also builds slowly in daylight above ground.''',
     '''        float change = 0.45f; // Ambient tension builds slowly, even in daylight.
        // Ten-day plot escalates subtle anomalies into invasive and global ones.
        int storyPhase = StoryAnomalies.phase(player);''')
edit(director,
     '''        state.tension = Mth.clamp(state.tension + change, 0f, 100f);''',
     '''        if (storyPhase >= 3 && change > 0) change *= 1.4f;
        state.tension = Mth.clamp(state.tension + change, 0f, 100f);''')
edit(director,
     '''        float chance = Math.min(.96f,(HorrorConfig.INTENSITY.get() / 100f)
                 * (0.50f + state.tension / 100f) * (1f + (100-psyche)/80f));''',
     '''        float chance = Math.min(.96f,(HorrorConfig.INTENSITY.get() / 100f)
                 * (0.50f + state.tension / 100f) * (1f + (100-psyche)/80f)
                 * StoryAnomalies.frequency(player));''')
edit(director,
     '''                state.performed++;
                if (selected.major()) state.lastMajorAt = now;''',
     '''                state.performed++;
                BlackFrequencyStory.INSTANCE.afterAnomaly(player, selected.name());
                if (selected.major()) state.lastMajorAt = now;''')
edit(director,
     '''        state.nextEvent = now + 20L * Math.max(8, Math.round(delay
                 * (1.20f - state.tension / 260f) * (0.45f + psyche / 150f)));''',
     '''        state.nextEvent = now + 20L * Math.max(8, Math.round(delay
                 * (1.20f - state.tension / 260f) * (0.45f + psyche / 150f)
                 / StoryAnomalies.frequency(player)));''')
edit(director,
     '''            if (available(player, state, def)) total += effectiveWeight(def);''',
     '''            if (available(player, state, def)) total += effectiveWeight(player, def);''')
edit(director,
     '''            roll -= effectiveWeight(def);''',
     '''            roll -= effectiveWeight(player, def);''')
edit(director,
     '''    private static int effectiveWeight(EventDef def) {
        // Location-specific anomalies should be noticeable while their context lasts.
        return def.weight() * switch (def.context()) {
            case WATER -> 6;
            case RAIN -> 4;
            case CAVE -> 2;
            default -> 1;
        };
    }''',
     '''    private static int effectiveWeight(ServerPlayer p, EventDef def) {
        // Preserve the legacy geography weights; add narrative and moral intent.
        int geographic = switch (def.context()) {
            case WATER -> 6;
            case RAIN -> 4;
            case CAVE -> 2;
            default -> 1;
        };
        return def.weight() * geographic
                * StoryAnomalies.weight(p,def.name(),def.major());
    }''')
edit(director,
     '''        int psyche=PsychologyData.get(player.getServer()).mind(player).sanity;
        if (state.tension + (100-psyche)*.55f < def.minTension()''',
     '''        int psyche=PsychologyData.get(player.getServer()).mind(player).sanity;
        if (!StoryAnomalies.allowed(player, def.name(),def.minTension(),def.major())) return false;
        if (state.tension + (100-psyche)*.55f < def.minTension()''')

# One shared instance is registered and called from the director (do NOT register two).
fresh("world/BlackFrequencyStory.java",r'''
package com.blackfrequency.hollowsignal.world;

/** Single event-bus instance shared with the old horror director. */
public final class BlackFrequencyStory {
    public static final StoryAnomalies INSTANCE=new StoryAnomalies();
    private BlackFrequencyStory() {}
}
''')
edit("BlackFrequency.java",
     '''        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.DreamSystem());''',
     '''        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.DreamSystem());
        MinecraftForge.EVENT_BUS.register(com.blackfrequency.hollowsignal.world.BlackFrequencyStory.INSTANCE);''')

# Make the day-3 dream unlock use the SAME server-wide ten-day narrative clock.
edit("world/DreamSystem.java",
     '''        long day=p.getServer().overworld().getDayTime()/24000L+1;''',
     '''        long day=StoryAnomalies.day(p);''')
edit("command/HorrorCommands.java",
     '''                            long day=p.getServer().overworld().getDayTime()/24000L+1;''',
     '''                            long day=com.blackfrequency.hollowsignal.world.StoryAnomalies.day(p);''')

# Day 10 pressure also makes pre-rupture QTEs meaningful, but not guaranteed.
edit("world/PsychologySystem.java",
     '''            if(data.ruptureActive()) probability*=2;''',
     '''            if(data.ruptureActive()) probability*=2;
            else if(StoryAnomalies.day(p)>=10) probability*=1.7f;''')

# Operator diagnostics: clear explanation of four story stages.
edit("command/HorrorCommands.java",
     '''                .then(Commands.literal("dream")''',
     '''                .then(Commands.literal("story")
                        .then(Commands.literal("status").executes(ctx -> {
                            var p=ctx.getSource().getPlayerOrException();
                            var d=PsychologyData.get(p.getServer());
                            var m=d.mind(p);
                            long day=com.blackfrequency.hollowsignal.world.StoryAnomalies.day(p);
                            int phase=com.blackfrequency.hollowsignal.world.StoryAnomalies.phase(p);
                            ctx.getSource().sendSuccess(()->Component.literal(
                                    "Сюжетный день "+day+" | фаза "+phase+
                                    " | психика "+m.sanity+
                                    " | мораль "+m.morality+
                                    " | мировой разрыв "+d.ruptureActive()),false);
                            return 1;
                        })))
                .then(Commands.literal("dream")''')

p=ROOT/"gradle.properties"
properties=p.read_text(encoding="utf-8")
assert properties.count("0.20.0")==1
p.write_text(properties.replace("0.20.0","0.21.0"),encoding="utf-8")
print("v0.21: legacy anomaly IDs preserved; story phases 1-10, independent morality, private voice, global rupture")
