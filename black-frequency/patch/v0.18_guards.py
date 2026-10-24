#!/usr/bin/env python3
"""Final v0.18 guard fixes and operator diagnostics, run after both overlays."""
from pathlib import Path
ROOT=Path("black-frequency/project")
JAVA=ROOT/"src/main/java/com/blackfrequency/hollowsignal"
def edit(rel,old,new):
    p=JAVA/rel
    s=p.read_text(encoding="utf-8")
    assert s.count(old)==1,(rel,old[:100],s.count(old))
    p.write_text(s.replace(old,new,1),encoding="utf-8")

# Important: EntityJoinLevelEvent runs inside addFreshEntity; register UUID first.
edit("world/RitualBattle.java",
     '''        if(!level.addFreshEntity(boss)) return;
        d.bossId=boss.getUUID();
        d.bossDefeated=false;
        d.setDirty();
        shoot(level,d,pos);''',
     '''        d.bossId=boss.getUUID();
        d.bossDefeated=false;
        d.setDirty();
        if(!level.addFreshEntity(boss)) {
            d.fightActive=false;
            d.bossId=null;
            d.setDirty();
            return;
        }
        shoot(level,d,pos);''')
# Ensure unavailable chunks never cause hidden spawns during a cinematic.
edit("world/RitualSystem.java",
     '''        if(!PsychologyData.get(p.getServer()).ruptureActive()) return;
        // Right-click beacon with charged fragment;''',
     '''        if(!PsychologyData.get(p.getServer()).ruptureActive()
                || PsychologyData.get(p.getServer()).ruptureStage==0) return;
        // Right-click beacon with charged fragment;''')
# Visible attack scene: second entity is not invented here; sharp rupture/sound at handoff.
edit("world/RitualSystem.java",
     '''        level.sendParticles(ParticleTypes.SQUID_INK,watcher.getX(),
                watcher.getY()+1,watcher.getZ(),48,.7,.9,.7,.12);''',
     '''        level.playSound(null,watcher.blockPosition(),
                net.minecraft.sounds.SoundEvents.WARDEN_SONIC_BOOM,
                net.minecraft.sounds.SoundSource.HOSTILE,.8f,.45f);
        level.sendParticles(ParticleTypes.EXPLOSION,watcher.getX(),
                watcher.getY()+1,watcher.getZ(),8,.7,.6,.7,.1);
        level.sendParticles(ParticleTypes.SQUID_INK,watcher.getX(),
                watcher.getY()+1,watcher.getZ(),48,.7,.9,.7,.12);''')

# Operator recovery commands so note loss cannot softlock the world.
edit("command/HorrorCommands.java",
     '                .then(Commands.literal("tension")',
     '''                .then(Commands.literal("ritual")
                        .then(Commands.literal("status").executes(ctx -> {
                            var p=ctx.getSource().getPlayerOrException();
                            var r=com.blackfrequency.hollowsignal.world.RitualData.get(p.getServer());
                            ctx.getSource().sendSuccess(()->Component.literal(
                                    "Записка передана: "+r.noteDelivered+
                                    " | бой: "+r.fightActive+
                                    " | фаза: "+r.phase+
                                    " | здоровье: "+r.bossHealth+
                                    " | заряд: "+r.recharge+"/5"+
                                    " | выстрелы: "+r.shots+
                                    " | побеждён: "+r.bossDefeated),false);
                            return 1;
                        }))
                        .then(Commands.literal("note").executes(ctx -> {
                            var p=ctx.getSource().getPlayerOrException();
                            var note=new net.minecraft.world.item.ItemStack(
                                    com.blackfrequency.hollowsignal.registry.RitualItems.FRACTURED_NOTE.get());
                            if(!p.getInventory().add(note)) p.drop(note,false);
                            ctx.getSource().sendSuccess(()->Component.literal(
                                    "Выдана резервная копия повреждённой записки"),false);
                            return 1;
                        })))
                .then(Commands.literal("tension")''')

# Record removed stale block entries even if no crack could be fully restored.
edit("world/PsychologyData.java",
     '''    public int sealSome(MinecraftServer server,int budget) {
        int fixed=0;''',
     '''    public int sealSome(MinecraftServer server,int budget) {
        int fixed=0;
        boolean changed=false;''')
edit("world/PsychologyData.java",
     '''                    iterator.remove(); continue;
                }
                var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                        new net.minecraft.resources.ResourceLocation(entry.getValue()));
                if(level.setBlock(pos,block.defaultBlockState(),3)) {
                    fixed++;
                    iterator.remove();
                }
            } catch(RuntimeException bad) {
                iterator.remove();
            }
        }
        if(fixed>0) setDirty();''',
     '''                    iterator.remove(); changed=true; continue;
                }
                var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                        new net.minecraft.resources.ResourceLocation(entry.getValue()));
                if(level.setBlock(pos,block.defaultBlockState(),3)) {
                    fixed++;
                    iterator.remove();
                    changed=true;
                }
            } catch(RuntimeException bad) {
                iterator.remove();
                changed=true;
            }
        }
        if(changed) setDirty();''')
print("PASS v0.18 guard fixes, note recovery diagnostics and safe server-owned boss UUID")
