#!/usr/bin/env python3
"""Post-v0.14 source overlay. Never mutates main or the source ZIP."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "project" if False else Path("black-frequency/project")
JAVA = ROOT / "src/main/java/com/blackfrequency/hollowsignal"


def edit(path, old, new):
    content = path.read_text(encoding="utf-8")
    if content.count(old) != 1:
        raise AssertionError(f"{path}: expected once, found {content.count(old)}: {old[:90]!r}")
    path.write_text(content.replace(old, new, 1), encoding="utf-8")


def replace_region(path, start, end, new):
    content = path.read_text(encoding="utf-8")
    assert content.count(start) == content.count(end) == 1, path
    i = content.index(start)
    j = content.index(end, i)
    path.write_text(content[:i] + new + content[j:], encoding="utf-8")


data = JAVA / "world/PsychologyData.java"
edit(data, "    public long ruptureLastTick;\n", "    public long ruptureLastTick;\n    public long ruptureCooldownUntil;\n")
replace_region(data, "    public int restore(net.minecraft.server.MinecraftServer server) {", "    public boolean ruptureActive()", """    /**
     * Admin-only emergency recovery. Unloaded chunks are retained in the journal so
     * another /hollowsignal rupture reset can restore them after they are loaded.
     */
    public int restore(net.minecraft.server.MinecraftServer server) {
        int count = 0;
        var iterator = cracks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            try {
                String[] parts = entry.getKey().split(";", 2);
                if (parts.length != 2) { iterator.remove(); continue; }
                var dimension = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        new net.minecraft.resources.ResourceLocation(parts[0]));
                var level = server.getLevel(dimension);
                if (level == null) continue; // A modded dimension might return later.
                var pos = net.minecraft.core.BlockPos.of(Long.parseLong(parts[1]));
                if (!level.hasChunkAt(pos)) continue; // Never force-load chunks.
                if (!level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.SCULK)) {
                    iterator.remove(); // A player already replaced the crack.
                    continue;
                }
                var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                        new net.minecraft.resources.ResourceLocation(entry.getValue()));
                if (level.setBlock(pos, block.defaultBlockState(), 3)) {
                    count++;
                    iterator.remove();
                }
            } catch (RuntimeException malformed) {
                iterator.remove(); // Invalid journal entry must not block recovery.
            }
        }
        // Riftlings are marked explicitly; never remove unrelated vanilla mobs.
        for (var level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity.getTags().contains("hollowsignal_rupture")) entity.discard();
            }
        }
        ruptureHost = null;
        ruptureStage = 0;
        ruptureStart = 0;
        ruptureLastTick = 0;
        // Prevent immediate automatic re-trigger from the same critical player.
        ruptureCooldownUntil = Math.max(ruptureCooldownUntil,
                server.overworld().getGameTime() + 20L * 60 * 5);
        setDirty();
        return count;
    }
""")
edit(data, '        d.ruptureLastTick = tag.getLong("lastTick");\n',
     '        d.ruptureLastTick = tag.getLong("lastTick");\n        d.ruptureCooldownUntil = tag.getLong("cooldownUntil");\n')
edit(data, '        tag.putLong("lastTick", ruptureLastTick);\n',
     '        tag.putLong("lastTick", ruptureLastTick);\n        tag.putLong("cooldownUntil", ruptureCooldownUntil);\n')

rupture = JAVA / "world/RuptureSystem.java"
edit(rupture,
     '        if(data.ruptureActive() || !HorrorConfig.WORLD_RUPTURE.get()) return false;',
     '        if(data.ruptureActive() || !HorrorConfig.WORLD_RUPTURE.get()\n'
     '                || host.getServer().overworld().getGameTime() < data.ruptureCooldownUntil) return false;')
edit(rupture, '        data.ruptureStart=host.serverLevel().getGameTime();',
     '        data.ruptureStart=host.getServer().overworld().getGameTime();')
edit(rupture, '            if(now%100==0) distortMob(p,stage);',
     """            if(now%100==0) distortMob(p,stage);
            // A real shared event: every player, in every dimension, witnesses it.
            if(now%600==0) {
                level.sendParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY()+1, p.getZ(),
                        6 + stage * 8, 2.5, 1.4, 2.5, .035);
                p.playNotifySound(SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE,
                        .24f + stage * .09f, .72f - stage * .055f);
                if(stage>=2 && !p.isCreative())
                    p.addEffect(new MobEffectInstance(MobEffects.CONFUSION,
                            25 + stage * 12, 0, false, false));
            }""")
edit(rupture,
     '        level.addFreshEntity(e);\n        level.sendParticles',
     '        e.addTag("hollowsignal_rupture");\n        level.addFreshEntity(e);\n        level.sendParticles')
edit(rupture,
     '        if(!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir()) return;',
     '        if(!level.hasChunkAt(pos) || !level.hasChunkAt(pos.below())\n'
     '                || !level.getBlockState(pos).isAir()\n'
     '                || !level.getBlockState(pos.below()).isSolidRender(level,pos.below())) return;')
# Do not silently replace known player-made structures while drawing cracks.
edit(rupture,
     '            if(!level.hasChunkAt(pos)) continue;',
     '            if(!level.hasChunkAt(pos) || !level.isInWorldBounds(pos)) continue;')

psy = JAVA / "world/PsychologySystem.java"
edit(psy,
     '        if(!data.ruptureActive() && m.pendingQte==0 && m.sanity<=HorrorConfig.RUPTURE_SANITY_THRESHOLD.get()',
     '        if(!data.ruptureActive() && !p.isCreative() && !p.isSpectator()\n'
     '                && p.getServer().overworld().getGameTime()>=data.ruptureCooldownUntil\n'
     '                && m.pendingQte==0 && m.sanity<=HorrorConfig.RUPTURE_SANITY_THRESHOLD.get()')
edit(psy,
     '    public static void beginQte(ServerPlayer p, PsychologyData data) {',
     """    private static void clearQte(ServerPlayer p, boolean notifyClient) {
        PsychologyData data = PsychologyData.get(p.getServer());
        PsychologyData.Mind mind = data.mind(p);
        if (mind.pendingQte == 0) return;
        mind.pendingQte = 0;
        mind.qteDeadline = 0;
        data.setDirty();
        if (notifyClient) QtePacket.send(p, 0, 0);
    }
    @SubscribeEvent public void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) clearQte(p, false);
    }
    @SubscribeEvent public void onDimension(net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) clearQte(p, true);
    }
    @SubscribeEvent public void onRespawn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) clearQte(p, true);
    }

    public static void beginQte(ServerPlayer p, PsychologyData data) {""")
edit(psy, '        if(data.mind(p).pendingQte!=0 || p.isSpectator()) return;',
     '        if(!p.isAlive() || data.mind(p).pendingQte!=0 || p.isSpectator()) return;')
edit(psy,
     '        if(challenge<=0 || m.pendingQte!=challenge || now>m.qteDeadline) return;',
     '        if(!p.isAlive() || p.isSpectator() || !HorrorConfig.PSYCHOLOGY_SYSTEM.get()\n'
     '                || challenge<=0 || m.pendingQte!=challenge || now>m.qteDeadline) return;')

qte = JAVA / "client/ClientQte.java"
edit(qte, 'import net.minecraft.client.Minecraft;\n',
     'import net.minecraft.client.Minecraft;\nimport net.minecraft.client.KeyMapping;\n')
edit(qte, 'import net.minecraftforge.client.event.RenderGuiEvent;\n',
     'import net.minecraftforge.client.event.RenderGuiEvent;\n'
     'import net.minecraftforge.client.event.RegisterKeyMappingsEvent;\n'
     'import net.minecraftforge.client.event.ClientPlayerNetworkEvent;\n')
edit(qte, '    private static int challenge, remaining;\n    private static boolean keyWasDown;',
     """    private static int challenge, remaining, total = 85;
    private static final KeyMapping REACT = new KeyMapping(
            "key.hollowsignal.react", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R, "key.categories.hollowsignal");
    @Mod.EventBusSubscriber(modid=BlackFrequency.MODID, bus=Mod.EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
    public static final class KeyRegistration {
        @SubscribeEvent public static void register(RegisterKeyMappingsEvent e) { e.register(REACT); }
    }""")
edit(qte,
     '        challenge=code;\n        remaining=Math.min(160, Math.max(1,ticks));\n        keyWasDown=true;',
     """        challenge=code;
        remaining=code<=0 ? 0 : Math.min(160,Math.max(1,ticks));
        total=Math.max(1,remaining);""")
edit(qte,
     '        boolean down=InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_R);\n'
     '        if(down && !keyWasDown) {',
     '        if(REACT.consumeClick()) {')
edit(qte, '        keyWasDown=down;\n', '')
edit(qte, 'remaining*224/85', 'remaining*224/total')
edit(qte,
     '    private ClientQte(){}',
     """    @SubscribeEvent public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut e) {
        challenge=0; remaining=0;
    }
    private ClientQte(){}""")

cmd = JAVA / "command/HorrorCommands.java"
edit(cmd, '                                    + " | реальные изменения: " + d.cracks.size()',
     '                                    + " | записей для восстановления: " + d.cracks.size()')
edit(cmd,
     '                            int fixed=PsychologyData.get(p.getServer()).restore(p.getServer());\n'
     '                            ctx.getSource().sendSuccess(() -> Component.literal("Прорыв остановлен; восстановлено блоков: " + fixed),true);',
     """                            var d=PsychologyData.get(p.getServer());
                            int fixed=d.restore(p.getServer());
                            int pending=d.cracks.size();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Прорыв остановлен; восстановлено: " + fixed
                                    + "; ожидают загрузки чанков: " + pending
                                    + ". Для повторного восстановления загрузите чанки и повторите /hollowsignal rupture reset"),true);""")
edit(cmd, '                            if(!ok) ctx.getSource().sendFailure(Component.literal("Прорыв уже существует или отключён"));',
     """                            if(!ok) ctx.getSource().sendFailure(Component.literal(
                                    "Прорыв уже активен, выключен в конфигурации или действует 5-минутная защита после сброса"));""")


# Tagged riftlings must not reappear after loading chunks restored on an admin reset.
edit(rupture,
     '    private static void spawnRiftling(ServerPlayer p,int stage) {',
     """    @SubscribeEvent public void onRiftlingJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)
                || !e.getEntity().getTags().contains("hollowsignal_rupture")) return;
        if (!PsychologyData.get(level.getServer()).ruptureActive()) e.setCanceled(true);
    }
    private static void spawnRiftling(ServerPlayer p,int stage) {""")
edit(psy,
     '                || !HorrorConfig.PSYCHOLOGY_SYSTEM.get()) return;',
     '                || !HorrorConfig.ENABLED.get() || !HorrorConfig.PSYCHOLOGY_SYSTEM.get()) return;')
edit(psy,
     '        if(!(e.getPlayer() instanceof ServerPlayer p) || e.isCanceled() || !HorrorConfig.PSYCHOLOGY_SYSTEM.get()) return;',
     '        if(!(e.getPlayer() instanceof ServerPlayer p) || e.isCanceled() || !HorrorConfig.ENABLED.get() || !HorrorConfig.PSYCHOLOGY_SYSTEM.get()) return;')

import json
for language, labels in {
    "ru_ru": {"key.hollowsignal.react": "Реакция на аномалию",
              "key.categories.hollowsignal": "BLACK FREQUENCY"},
    "en_us": {"key.hollowsignal.react": "React to anomaly",
              "key.categories.hollowsignal": "BLACK FREQUENCY"}
}.items():
    path = ROOT / "src/main/resources/assets/hollowsignal/lang" / f"{language}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    catalog = json.loads(path.read_text(encoding="utf-8-sig")) if path.exists() else {}
    catalog.update(labels)
    path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

properties=ROOT / "gradle.properties"
edit(properties, "0.14.0", "0.15.1")
print("v0.15.1 overlay applied: persistent safe recovery, QTE lifecycle + remapping, shared pulses, tagged riftlings")
