#!/usr/bin/env python3
"""v0.16 release-QTE overlay applied AFTER the verified v0.15.1 overlay.
The server owns prompts and outcomes. Success ALWAYS flashes green before
the forced red screen fracture; it never prevents global rupture.
"""
from pathlib import Path

ROOT = Path("black-frequency/project")
JAVA = ROOT / "src/main/java/com/blackfrequency/hollowsignal"


def edit(path, old, new):
    s = path.read_text(encoding="utf-8")
    assert s.count(old) == 1, (path, old[:80], s.count(old))
    path.write_text(s.replace(old, new, 1), encoding="utf-8")


def replace_region(path, start, end, inserted):
    s = path.read_text(encoding="utf-8")
    assert s.count(start) == 1 and s.count(end) == 1, path
    i = s.index(start)
    j = s.index(end, i)
    path.write_text(s[:i] + inserted + s[j:], encoding="utf-8")


def write_java(relative_path, source):
    path = JAVA / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    assert not path.exists(), "Refusing to replace existing source: " + str(path)
    path.write_text(source.strip() + "\n", encoding="utf-8")


write_java("network/RuptureSequencePacket.java", r'''
package com.blackfrequency.hollowsignal.network;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.blackfrequency.hollowsignal.client.ClientRuptureQte;
import com.blackfrequency.hollowsignal.world.RuptureCinematicSystem;
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

/**
 * QTE of the release scene, not the regular survivable QTE.
 * kinds: 0=start; 1=prompt; 2=accepted/green->red; 3=failed/red; 4=stop.
 */
public record RuptureSequencePacket(int kind, int beat, int ticks) {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BlackFrequency.MODID, "rupture_sequence"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    public static void register() {
        CHANNEL.registerMessage(0, RuptureSequencePacket.class,
                (m,b) -> { b.writeVarInt(m.kind); b.writeVarInt(m.beat); b.writeVarInt(m.ticks); },
                b -> new RuptureSequencePacket(b.readVarInt(), b.readVarInt(), b.readVarInt()),
                RuptureSequencePacket::fromServer, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Reply.class,
                (m,b) -> b.writeVarInt(m.beat),
                b -> new Reply(b.readVarInt()),
                RuptureSequencePacket::fromClient, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static void fromServer(RuptureSequencePacket message, Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientRuptureQte.receive(message.kind(), message.beat(), message.ticks())));
        supplier.get().setPacketHandled(true);
    }

    private static void fromClient(Reply reply, Supplier<NetworkEvent.Context> supplier) {
        ServerPlayer sender = supplier.get().getSender();
        supplier.get().enqueueWork(() -> {
            if (sender != null) RuptureCinematicSystem.answer(sender, reply.beat());
        });
        supplier.get().setPacketHandled(true);
    }

    public static void send(ServerPlayer player, int kind, int beat, int ticks) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RuptureSequencePacket(kind, beat, ticks));
    }

    public static void answer(int beat) {
        CHANNEL.sendToServer(new Reply(beat));
    }

    public record Reply(int beat) {}
}
''')

write_java("world/RuptureCinematicSystem.java", r'''
package com.blackfrequency.hollowsignal.world;

import com.blackfrequency.hollowsignal.HorrorConfig;
import com.blackfrequency.hollowsignal.network.QtePacket;
import com.blackfrequency.hollowsignal.network.RuptureSequencePacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * A globally exclusive prelude to world rupture.
 * All QTE "successes" are real, server-validated responses but can only
 * modify feedback. The story outcome is intentionally locked.
 */
public final class RuptureCinematicSystem {
    public static final int DURATION = 260;
    public static final int FIRST_BEAT = 20;
    public static final int BEAT_SPACING = 36;
    public static final int BEAT_WINDOW = 22;
    public static final int BEATS = 6;

    public static void begin(ServerPlayer host, PsychologyData d) {
        MinecraftServer server = host.getServer();
        if (server == null || d.ruptureActive()) return;
        d.ruptureHost = host.getUUID(); // Reserve singleton BEFORE any effects/packets.
        d.ruptureStage = 0;             // 0 means cinematic; 1-4 means world phase.
        d.ruptureCinematicStart = server.overworld().getGameTime();
        d.ruptureCinematicBeat = -1;
        d.ruptureCinematicAnswered = false;
        d.ruptureStart = 0;
        d.ruptureLastTick = 0;
        var mind = d.mind(host);
        mind.pendingQte = 0;
        mind.qteDeadline = 0;
        d.setDirty();

        QtePacket.send(host, 0, 0);
        RuptureSequencePacket.send(host, 0, 0, DURATION);
        // Player is NOT killed. First physical sign of the escape is levitation.
        if (!host.isCreative() && !host.isSpectator()) {
            host.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 60, 0, false, false));
            host.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, DURATION + 60, 0, false, false));
        }
        host.serverLevel().sendParticles(ParticleTypes.REVERSE_PORTAL,
                host.getX(), host.getY()+1, host.getZ(), 60, .7, 1, .7, .08);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal("Тело носителя теряет контроль над реальностью: "
                    + host.getGameProfile().getName()).withStyle(ChatFormatting.DARK_RED));
            player.playNotifySound(SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, .55f, .72f);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !HorrorConfig.ENABLED.get()) return;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        PsychologyData d = PsychologyData.get(server);
        if (!d.ruptureActive() || d.ruptureStage != 0) return;

        long now = server.overworld().getGameTime();
        long elapsed = now - d.ruptureCinematicStart;
        if (elapsed < 0) {
            // Defensive handling for restored saves with shifted world time.
            d.ruptureCinematicStart = now;
            d.setDirty();
            return;
        }
        if (elapsed >= DURATION) {
            finish(server, d, now);
            return;
        }

        int index = (int)((elapsed - FIRST_BEAT) / BEAT_SPACING);
        if (elapsed >= FIRST_BEAT && index >= 0 && index < BEATS) {
            long beatStart = FIRST_BEAT + (long)index * BEAT_SPACING;
            if (d.ruptureCinematicBeat != index) {
                d.ruptureCinematicBeat = index;
                d.ruptureCinematicAnswered = false;
                d.setDirty();
                sendHost(server, d, 1, index, BEAT_WINDOW);
            }
            if (elapsed >= beatStart + BEAT_WINDOW && !d.ruptureCinematicAnswered) {
                d.ruptureCinematicAnswered = true;
                d.setDirty();
                sendHost(server, d, 3, index, 0); // Missed the window: immediate red flash.
            }
        }
    }

    public static void answer(ServerPlayer player, int beat) {
        MinecraftServer server = player.getServer();
        if (server == null || !HorrorConfig.ENABLED.get()) return;
        PsychologyData d = PsychologyData.get(server);
        if (!d.ruptureActive() || d.ruptureStage != 0
                || !player.getUUID().equals(d.ruptureHost)
                || d.ruptureCinematicAnswered || beat != d.ruptureCinematicBeat
                || beat < 0 || beat >= BEATS) return;

        long elapsed = server.overworld().getGameTime() - d.ruptureCinematicStart;
        long windowStart = FIRST_BEAT + (long)beat * BEAT_SPACING;
        boolean onTime = elapsed >= windowStart && elapsed < windowStart + BEAT_WINDOW;
        d.ruptureCinematicAnswered = true;
        d.setDirty();
        // Successful reaction always goes green FIRST, then breaks into red on client.
        // Failure is immediately red. Neither branch can cancel the release.
        RuptureSequencePacket.send(player, onTime ? 2 : 3, beat, 0);
    }

    @SubscribeEvent
    public void reconnect(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        MinecraftServer server = p.getServer();
        if (server == null) return;
        PsychologyData d = PsychologyData.get(server);
        if (d.ruptureStage != 0 || !p.getUUID().equals(d.ruptureHost)) return;
        int remaining = (int)Math.max(1, DURATION
                - (server.overworld().getGameTime() - d.ruptureCinematicStart));
        RuptureSequencePacket.send(p, 0, 0, remaining);
        if (d.ruptureCinematicBeat >= 0 && !d.ruptureCinematicAnswered) {
            long now = server.overworld().getGameTime() - d.ruptureCinematicStart;
            long begin = FIRST_BEAT + (long)d.ruptureCinematicBeat * BEAT_SPACING;
            int windowRemaining = (int)(begin + BEAT_WINDOW - now);
            if (windowRemaining > 0)
                RuptureSequencePacket.send(p, 1, d.ruptureCinematicBeat, windowRemaining);
        }
    }

    private static void finish(MinecraftServer server, PsychologyData d, long now) {
        d.ruptureStage = 1;
        d.ruptureStart = now;
        d.ruptureLastTick = now;
        d.ruptureCinematicBeat = -1;
        d.ruptureCinematicAnswered = true;
        d.setDirty();
        sendHost(server, d, 4, 0, 0);
        ServerPlayer host = server.getPlayerList().getPlayer(d.ruptureHost);
        if (host != null) host.removeEffect(MobEffects.LEVITATION);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal("РАЗРЫВ РЕАЛЬНОСТИ. Мир больше не прежний.")
                    .withStyle(ChatFormatting.DARK_RED));
            player.playNotifySound(SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, .7f, .5f);
        }
    }

    private static void sendHost(MinecraftServer server, PsychologyData d,
                                 int kind, int beat, int ticks) {
        if (d.ruptureHost == null) return;
        ServerPlayer host = server.getPlayerList().getPlayer(d.ruptureHost);
        if (host != null) RuptureSequencePacket.send(host, kind, beat, ticks);
    }
}
''')

write_java("client/ClientRuptureQte.java", r'''
package com.blackfrequency.hollowsignal.client;

import com.blackfrequency.hollowsignal.BlackFrequency;
import com.blackfrequency.hollowsignal.network.RuptureSequencePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Release-QTE intentionally plays a false recovery:
 * acknowledged press -> 7 client ticks of GREEN -> sudden RED fracture.
 * The server, not the HUD, decides whether the press was on time.
 */
@Mod.EventBusSubscriber(modid=BlackFrequency.MODID, bus=Mod.EventBusSubscriber.Bus.FORGE, value=Dist.CLIENT)
public final class ClientRuptureQte {
    private static boolean active;
    private static int age;
    private static int remaining;
    private static int currentBeat = -1;
    private static int promptEndsAt;
    private static int greenTicks;
    private static int redTicks;
    private static int fractureDepth;

    public static boolean isActive() { return active; }

    public static void receive(int kind, int beat, int ticks) {
        switch (kind) {
            case 0 -> {
                active = true;
                age = 0;
                remaining = Math.max(1, ticks);
                currentBeat = -1;
                greenTicks = redTicks = fractureDepth = 0;
                ClientQte.start(0, 0); // Never overlay the regular QTE.
            }
            case 1 -> {
                if (!active) return;
                currentBeat = beat;
                promptEndsAt = age + Math.max(1, ticks);
            }
            case 2 -> {
                if (!active) return;
                currentBeat = -1;
                greenTicks = 7; // Visible recovery for ~350 ms at 20 TPS.
                redTicks = 0;
            }
            case 3 -> {
                if (!active) return;
                currentBeat = -1;
                greenTicks = 0;
                redTicks = 17;
                fractureDepth = Math.min(12, fractureDepth + 2);
            }
            case 4 -> reset();
            default -> { }
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { reset(); return; }
        if (mc.isPaused()) return;
        age++;
        remaining--;
        if (greenTicks > 0) {
            greenTicks--;
            if (greenTicks == 0) {
                // The dramatic snap is mandatory after EVERY successful keypress.
                redTicks = 17;
                fractureDepth = Math.min(12, fractureDepth + 3);
            }
        } else if (redTicks > 0) {
            redTicks--;
        }
        if (currentBeat >= 0 && age <= promptEndsAt && mc.screen == null
                && ClientQte.consumeRuptureKey()) {
            RuptureSequencePacket.answer(currentBeat);
            currentBeat = -1; // At most one reply per prompt.
        }
        if (age > 320 || remaining < -60) reset(); // Failsafe on packet loss.
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        if (!active || Minecraft.getInstance().player == null) return;
        GuiGraphics g = event.getGuiGraphics();
        int w = event.getWindow().getGuiScaledWidth();
        int h = event.getWindow().getGuiScaledHeight();
        int centerX = w / 2;
        int centerY = h / 2;

        int baseDepth = Math.min(9, age / 30);
        int depth = Math.min(18, baseDepth + fractureDepth);
        int pulse = (redTicks > 0 ? 0x77 : 0x38);
        int fissureColor = (pulse << 24) | 0xAD1726;
        int blackRed = (0xB0 << 24) | 0x23080D;

        // Branches expand from the center without taking focus or faking a disconnect.
        for (int arm = 0; arm < 8; arm++) {
            int x = centerX, y = centerY;
            int sx = ((arm & 1) == 0 ? 1 : -1);
            int sy = ((arm & 2) == 0 ? 1 : -1);
            for (int part = 0; part < depth + 3; part++) {
                int deltaX = sx * (9 + (part * 7 + arm * 3) % 12);
                int deltaY = sy * (5 + (part * 3 + arm * 5) % 9);
                g.fill(Math.min(x,x+deltaX), y, Math.max(x,x+deltaX)+2, y+2, fissureColor);
                x += deltaX;
                g.fill(x, Math.min(y,y+deltaY), x+2, Math.max(y,y+deltaY)+2, blackRed);
                y += deltaY;
            }
        }

        int occlusion = Math.min(h / 3, depth * 7);
        g.fill(0, 0, w, occlusion, 0x95200812);
        g.fill(0, h-occlusion, w, h, 0x95200812);

        if (greenTicks > 0) {
            g.fill(0, 0, w, h, 0x5500C955);
            g.drawCenteredString(Minecraft.getInstance().font,
                    "СТАБИЛИЗАЦИЯ...", centerX, centerY - 33, 0xFF89FFC1);
        } else if (redTicks > 0) {
            g.fill(0, 0, w, h, 0x66EE112D);
            g.drawCenteredString(Minecraft.getInstance().font,
                    "ТРЕЩИНЫ НЕ ОСТАНОВИТЬ", centerX, centerY - 33, 0xFFFF6B75);
        }

        if (currentBeat >= 0 && age < promptEndsAt) {
            String hotkey = ClientQte.ruptureKeyName();
            g.fill(centerX-98, centerY+34, centerX+98, centerY+63, 0xCB16090D);
            g.drawCenteredString(Minecraft.getInstance().font,
                    "СОПРОТИВЛЯЙСЯ — " + hotkey, centerX, centerY+42, 0xFFFFE8E8);
            int width = Math.max(0, Math.min(188, (promptEndsAt-age) * 188 / 22));
            g.fill(centerX-94,centerY+56,centerX-94+width,centerY+59,0xFFE13F4A);
        }
    }

    @SubscribeEvent
    public static void disconnect(ClientPlayerNetworkEvent.LoggingOut e) { reset(); }

    private static void reset() {
        active = false;
        currentBeat = -1;
        greenTicks = redTicks = fractureDepth = 0;
        remaining = age = 0;
    }

    private ClientRuptureQte() {}
}
''')

data = JAVA / "world/PsychologyData.java"
edit(data, "    public long ruptureCooldownUntil;\n",
     "    public long ruptureCooldownUntil;\n"
     "    public long ruptureCinematicStart;\n"
     "    public int ruptureCinematicBeat = -1;\n"
     "    public boolean ruptureCinematicAnswered;\n")
edit(data, '        d.ruptureCooldownUntil = tag.getLong("cooldownUntil");\n',
     '        d.ruptureCooldownUntil = tag.getLong("cooldownUntil");\n'
     '        d.ruptureCinematicStart = tag.getLong("cinematicStart");\n'
     '        d.ruptureCinematicBeat = tag.contains("cinematicBeat") ? tag.getInt("cinematicBeat") : -1;\n'
     '        d.ruptureCinematicAnswered = tag.getBoolean("cinematicAnswered");\n')
edit(data, '        tag.putLong("cooldownUntil", ruptureCooldownUntil);\n',
     '        tag.putLong("cooldownUntil", ruptureCooldownUntil);\n'
     '        tag.putLong("cinematicStart", ruptureCinematicStart);\n'
     '        tag.putInt("cinematicBeat", ruptureCinematicBeat);\n'
     '        tag.putBoolean("cinematicAnswered", ruptureCinematicAnswered);\n')
edit(data, '        ruptureHost = null;\n',
     '''        if (ruptureHost != null) {
            var host = server.getPlayerList().getPlayer(ruptureHost);
            if (host != null)
                com.blackfrequency.hollowsignal.network.RuptureSequencePacket.send(host, 4, 0, 0);
        }
        ruptureHost = null;
        ruptureCinematicStart = 0;
        ruptureCinematicBeat = -1;
        ruptureCinematicAnswered = false;
''')

rupture = JAVA / "world/RuptureSystem.java"
replace_region(rupture,
               "        data.ruptureHost=host.getUUID();",
               "        return true;\n    }\n",
               "        RuptureCinematicSystem.begin(host, data);\n")
# The original 'return true' and brace are preserved by replace_region.
edit(rupture,
     '        if(!data.ruptureActive()) return;\n',
     '        if(!data.ruptureActive() || data.ruptureStage==0) return;\n')

psy = JAVA / "world/PsychologySystem.java"
edit(psy, '        PsychologyData.Mind m=data.mind(p);\n',
     '''        PsychologyData.Mind m=data.mind(p);
        // Regular QTEs and mental effects cannot interrupt the host's release scene.
        if (data.ruptureActive() && data.ruptureStage == 0
                && p.getUUID().equals(data.ruptureHost)) return;
''')
edit(psy,
     '        if(!p.isAlive() || data.mind(p).pendingQte!=0 || p.isSpectator()) return;',
     '        if(!p.isAlive() || data.mind(p).pendingQte!=0 || p.isSpectator()\n'
     '                || (data.ruptureActive() && data.ruptureStage==0\n'
     '                    && p.getUUID().equals(data.ruptureHost))) return;')

qte = JAVA / "client/ClientQte.java"
edit(qte, "    private ClientQte(){}",
     '''    public static boolean consumeRuptureKey() { return REACT.consumeClick(); }
    public static String ruptureKeyName() { return REACT.getTranslatedKeyMessage().getString(); }
    private ClientQte(){}''')

mod = JAVA / "BlackFrequency.java"
edit(mod, 'import com.blackfrequency.hollowsignal.network.QtePacket;\n',
     'import com.blackfrequency.hollowsignal.network.QtePacket;\n'
     'import com.blackfrequency.hollowsignal.network.RuptureSequencePacket;\n')
edit(mod, '        QtePacket.register();\n',
     '        QtePacket.register();\n        RuptureSequencePacket.register();\n')
edit(mod, '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.RuptureSystem());\n',
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.RuptureSystem());\n'
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.RuptureCinematicSystem());\n')

cmd = JAVA / "command/HorrorCommands.java"
edit(cmd, 'd.ruptureActive()?"Фаза " + d.ruptureStage:"нет"',
     'd.ruptureActive()?(d.ruptureStage==0?"Высвобождение":"Фаза " + d.ruptureStage):"нет"')

properties = ROOT / "gradle.properties"
edit(properties, "0.15.1", "0.16.0")
print("PASS: release QTE v0.16 green confirmation -> forced red fracture + single global scene lock")
