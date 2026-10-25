#!/usr/bin/env python3
"""v0.21.1: ensure dreaming/host-cinematics never receive unrelated personal scares."""
from pathlib import Path
ROOT=Path("black-frequency/project")
JAVA=ROOT/"src/main/java/com/blackfrequency/hollowsignal"
def edit(file,old,new):
    path=JAVA/file
    text=path.read_text(encoding="utf-8")
    assert text.count(old)==1,(file,old[:100],text.count(old))
    path.write_text(text.replace(old,new,1),encoding="utf-8")
edit("world/HorrorDirector.java",
     '''        // Pending sound echoes run at tick accuracy without allocating new entities.''',
     '''        // Personal anomalies never interrupt a private dream or the globally
        // exclusive release cutscene. The existing world rupture continues elsewhere.
        var shared = PsychologyData.get(player.getServer());
        boolean releasing = shared.ruptureActive() && shared.ruptureStage == 0
                && player.getUUID().equals(shared.ruptureHost);
        if (player.isSleeping() || releasing) {
            state.pending.clear();
            if (PsychicScenes.isActive(player)) PsychicScenes.stop(player);
            if (MirageScenes.isActive(player)) MirageScenes.stop(player);
            if (DreamCaveScenes.isActive(player)) DreamCaveScenes.stop(player);
            if (MimicScenes.isActive(player)) MimicScenes.stop(player);
            if (RealityFractureScenes.isActive(player)) RealityFractureScenes.stop(player);
            state.nextEvent = now + 20L * 30L;
            return;
        }

        // Pending sound echoes run at tick accuracy without allocating new entities.''')
edit("world/PsychologySystem.java",
     '''        // Regular QTEs and mental effects cannot interrupt the host's release scene.''',
     '''        if (p.isSleeping()) {
            // A previously started normal QTE must not time out during dreams.
            if (m.pendingQte != 0) {
                m.pendingQte = 0;
                m.qteDeadline = 0;
                data.setDirty();
                QtePacket.send(p, 0, 0);
            }
            return;
        }
        // Regular QTEs and mental effects cannot interrupt the host's release scene.''')
edit("world/PsychologySystem.java",
     '''        if(!p.isAlive() || data.mind(p).pendingQte!=0 || p.isSpectator()''',
     '''        if(!p.isAlive() || p.isSleeping() || data.mind(p).pendingQte!=0 || p.isSpectator()''')
props=ROOT/"gradle.properties"
txt=props.read_text(encoding="utf-8")
assert txt.count("0.21.0")==1
props.write_text(txt.replace("0.21.0","0.21.1"),encoding="utf-8")
print("PASS: story anomaly scheduler and ordinary QTEs suspended for sleeping/cinematic players")
