# ОБНОВЛЕНИЕ 2026-10-12: v14.2.1 — Clear Screen + TPS Relief

**ЭТО АКТУАЛЬНЫЙ СТАТУС**. Дальнейший текст ниже сохраняет исторические сведения о v14 и НЕ является актуальным списком модов.

- Репозиторий: craftkot/horror, main, Minecraft 1.20.1, Forge 47.4.15.
- Текущий manifest: `EHTID Horror REBORN v14.2.1`, 107 файлов.
- В v14.1 снижена нагрузка от событий и добавлен spark (361579); 2 TPS в конкретном сохранении пока не подтверждено как исправленное.
- В v14.2.1 по просьбе пользователя **удалены** целиком 255389 (EnhancedVisuals), 1475092 (StalkerEntity), 1421612 (The Idol), 1484738 (do_not_join.jar). НЕ возвращать их без прямого разрешения пользователя.
- Дополнительно отключены Psychopath vignette/blur/heartbeat, Backrooms screen_shake/jumpscare/sanity_red_lines, Sound of Fear cave screens.
- В `tools/validate_manifest.py` есть `SCREEN_BLOCKING_EXCLUSIONS`: это часть стоп-листа, не обходить.
- `overrides/defaultconfigs/stalkerentity-server.toml` удалён из репозитория.
- Актуальный перечень — `manifest.json`, `overrides/MODLIST_RU.txt` перегенерирован.
- При установке поверх старого профиля необходимо удалить старые JAR 4 модов вручную; в мире сделать бэкап. Serverconfig существующего мира нужно проверить отдельно.
- Старые GitHub Actions «SUCCESS» для v14.1 не подтверждают TPS. Для v14.2.1 проверить три CI: validate, server, client.

---

## Исторический HANDOFF по v14 (неактуальные данные сохранены ниже)

# EHTID Horror — HANDOFF для нового чата
Дата: 2026-10-12

## ГЛАВНОЕ
Продолжать существующую сборку. НЕ начинать заново.

Репозиторий:
craftkot/horror

Текущая версия:
EHTID Horror REBORN v14 - All Encounters
Minecraft 1.20.1
Forge 47.4.15
manifest entries: 110

Перед любой работой:
1. Fresh-read ветки main.
2. Полностью прочитать этот файл.
3. Прочитать manifest.json.
4. Проверить последние GitHub Actions:
   - Validate Horror Pack
   - Forge Server Smoke Test
   - Forge Client Smoke Test
5. Только потом менять сборку.

## ТЕКУЩИЙ СТАТУС ПРОВЕРОК
Актуальные проверки прошли:
- Validate Horror Pack: SUCCESS
- Forge Server Smoke Test: SUCCESS
- Forge Client Smoke Test: SUCCESS

Клиентский smoke-test реально:
- ставит Java 17;
- поднимает Xvfb 1280x720;
- использует llvmpipe/software OpenGL;
- скачивает полный client mod set из manifest;
- ставит Minecraft 1.20.1 + Forge 47.4.15;
- копирует overrides;
- запускает настоящий Forge client;
- проверяет живое окно Minecraft + загрузку ресурсов/звука;
- сохраняет latest.log/crash-reports/console/screenshot как artifact.

Последний успешный клиентский run:
38283499415
SHA:
4967acfffc77e5db19c871f6a3d46616c2682d40

Это означает: текущая v14 проходит именно старт КЛИЕНТА до рабочего окна/инициализации.
Это не заменяет длительную игровую проверку мира/FPS.

## ВАЖНЫЕ ИСПРАВЛЕНИЯ, УЖЕ СДЕЛАННЫЕ
1. Mobtimizations требовал CoroUtil.
   Добавлен:
   projectID 237749
   fileID 5096038

2. Sweet Dreams конфликтовал с Do Not Hear Them...
   Причина:
   оба экспортировали Java package com.example.examplemod.entity,
   ModLauncher падал с ResolutionException.
   Sweet Dreams projectID 1589743 УДАЛЕН.
   Do Not Hear Them... оставлен.

3. Oculus удалён по желанию пользователя.
   projectID 581495 отсутствует.

4. ImmediatelyFast возвращён после удаления Oculus.
   projectID 686911
   fileID 4725250

5. Некоторые horror-моды client-heavy и не подходят для dedicated-server теста,
   но сохранены в клиентской сборке:
   - The Idol
   - do_not_join.jar
   - The Unseen
   - The Red Grin
   - Nostalgophobia
   Они исключаются только из server smoke test при необходимости.

## ПОЛИТИКА ПОЛЬЗОВАТЕЛЯ ПО МОДАМ
Пользователь хочет:
- очень много horror-модов;
- obscure/малозаметные/MCreator/китайские/русские/старые страницы/GitHub;
- можно копать буквально по задворкам интернета;
- качество/малое число загрузок само по себе НЕ причина отбрасывать;
- но Forge 1.20.1 и реальная совместимость обязательны;
- новые моды сначала добавлять небольшими пачками;
- после каждой пачки гонять Validate + Server Smoke + Client Smoke;
- не заставлять пользователя вручную ловить каждый ранний краш.

## СТОП-ЛИСТ — НЕ ВОЗВРАЩАТЬ БЕЗ ЯВНОГО РАЗРЕШЕНИЯ
46 модов были сознательно удалены пользователем:
1 The Man From The Fog
2 Born in Chaos
3 The One Who Watches
4 In Your World
5 The Origin
6 Distant Friends
7 Apollyon
8 From The Fog
9 Nightmare Horror
10 Whisperwoods
11 The Graveyard
12 The End of Herobrine
13 The Cave Crawler
14 The Wendigo: Rebirth
15 The Knocker
16 The Midnight Lurker
17 The Mimicer
18 Scopophobia (Restored)
19 Eyes in the Darkness
20 The Anomaly
21 Boiled One
22 Wretched Stalker
23 The Hollow Stalker
24 Something Watches
25 Paranoia (Horror)
26 The Skinwalkers
27 Siren Head: The Last Alarms
28 The Mimic [HORROR]
29 Analog Horror
30 The Grave Stalker
31 The Woodstalker
32 The Hash-Slinging Slasher
33 Krampus: Unleashed
34 Siren Head: Stranded
35 Weeping Angels!
36 Sculk Horde
37 From Another World
38 Sons Of Sins
39 Zombie Awareness
40 Creeper Overhaul
41 Spooky Mobs
42 DontCry
43 Zombie Stalker
44 Horror Elements
45 Spooky Structures
46 Scary Structures

CurseForge project IDs стоп-листа:
890565,686437,940557,1082195,1454646,839197,971482,852533,1279376,349597,
531188,695021,1455237,1380564,1001299,923133,970277,1495006,304719,1043571,
1426040,1558499,1197678,1563349,1515056,1337928,1492061,1680016,1275792,
1123475,1305685,1475234,1394764,1565240,1672412,501766,829752,538529,
237754,561625,1225054,1566054,1240540,496520,1111374,1085414

Не возвращать также AzureLib / FromAnotherLibrary только ради удалённого From Another World,
если они больше ничему не нужны.

## ОСНОВНЫЕ HORROR-МОДЫ, КОТОРЫЕ СЕЙЧАС ЕСТЬ
В v14 присутствуют в том числе:
- Long Horse / creepyhorse.jar
- Peeping Tom
- The Nightmare 000
- The Starved Stalker
- Backrooms.
- Curse of Grudge / 记仇
- The Skinwalker Hunt
- The Skin Walker Remake
- The Stalker Horror
- _Viostt.jar
- Error 404 Not Found
- Do Not Hear Them...
- Nexor's Stalkers
- Urban Terrors
- ROT
- DO NOT LOOK AT THEM
- DNGO - Do Not Go Outside
- gotham.jar / Gotham Terror
- Don't look away
- Horror by Death + MineZero
- Mirror Horror Project
- The Idol
- Paranormal Activity: Horror Reborn
- StalkerEntity
- The Harasser [Horror]
- Horror - The Stalker
- The Stalker - Horror
- THE HUNTER (HORROR)
- The Unseen Entity
- The Pale Watcher
- do_not_join.jar
- Entity 303 (Horror)
- HeWantsYou
- Corner Horror Entity
- They are Watching
- Legend of The Dwerden
- Otherworld [Horror]
- Cataclysms [By TheOman]
- The Red Grin
- DISTURBED / The Masked
- Psychopath
- fluffy
- THE_MAN_INSIDE_THE_SYTEM.jar
- The Cursed Smile
- The Dark Clown
- Sound of Fear
- Extra Horror Sounds
- Horror Messages
- Nostalgophobia

Sweet Dreams НЕ установлен.

## v14 ALL ENCOUNTERS — ЗАЧЕМ
Пользователь заметил, что некоторые horror-моды могут лежать мёртвым грузом:
моб/ивент почти никогда не появляется и контент мода не виден.

Поэтому v14 настроена так, чтобы каждый мод по возможности проявлялся через
СВОЮ родную механику, а не через насильственное добавление всех entity в vanilla biome spawn.

Это важно:
многие horror-моды используют director/event/sanity/stalking mechanics.
Не добавлять такие сущности насильно в vanilla spawn tables — это ломает задуманный AI/ивенты.

Настроены:
- DISTURBED:
  все Masked/Shadow/Intruder/Event варианты включены;
  кулдауны сокращены.

- Psychopath:
  guaranteedStalker = true
  guaranteedNightPsycho = true
  firstSpawnDay = 0

- Otherworld:
  все intruder'ы включены;
  shuffleIntruders = true;
  intruderFrequency повышен;
  dreadMoon включён.

- Backrooms:
  актуальные Partygoer/Hound/Smiler/Skinstealer/Bone Thief включены;
  legacy lifeform/lifeform_stalker/heartbeat НЕ включать —
  сам мод помечает их удалёнными/legacy.

- Gotham Terror:
  увеличена частота собственного Knight -> Stalk encounter chain.

- StalkerEntity:
  увеличены director chances;
  подняты шансы Invisible Watcher / Clone / Siren / Shadow / Stalker warning /
  atmospheric events;
  кулдауны сохранены, чтобы не было бесконечного спама.

Конфиги лежат в:
overrides/config/
overrides/defaultconfigs/

При изменении server-config помнить:
defaultconfigs копируются в world/serverconfig при СОЗДАНИИ НОВОГО МИРА.
Старый мир может продолжать использовать старые world/serverconfig.

## КОНФИГ-ФИЛОСОФИЯ
Цель сейчас:
"весь установленный horror-контент должен реально встречаться".

Но не превращать мир в зоопарк:
- использовать родные spawn/event/director системы;
- желательно ротация разных модов;
- редкие крупные встречи;
- между ними звуки/паранойя/малые события;
- избегать одновременного наложения 5-10 stalker-модов.

Если у мода есть флаг enable/disable сущности — включить все актуальные сущности.
Если есть guaranteed encounter — можно использовать разумно.
Если есть legacy/removed entity — не включать насильно.

## GITHUB CI
Есть:
.github/workflows/validate-pack.yml
.github/workflows/server-smoke-test.yml
.github/workflows/client-smoke-test.yml

Вспомогательные скрипты:
ci/download_server_mods.py
ci/run_server_smoke.py
ci/server_skip_ids.txt
ci/dump_generated_configs.py
ci/download_client_mods.py
ci/run_client_smoke.py

После изменений:
1. дождаться Validate;
2. дождаться Server Smoke;
3. обязательно дождаться Client Smoke;
4. при failure вытащить job logs и исправить конкретный конфликт;
5. не объявлять сборку стабильной до успешного client smoke.

## ЧТО ДЕЛАТЬ ДАЛЬШЕ
Приоритет пользователя:
1. Продолжать искать ещё horror-моды на задворках интернета:
   CurseForge deep pages, Modrinth для discovery, GitHub releases, MC百科,
   MCreator, китайские/русские страницы.
2. Не возвращать стоп-лист.
3. Добавлять найденное небольшими пачками.
4. Настраивать реальные конфиги, чтобы новый контент не лежал мёртвым грузом.
5. После каждой пачки гонять полный CI, включая реальный Forge client.
6. При успешном client CI собирать следующий CurseForge manifest ZIP пользователю.

## ПРИМЕЧАНИЯ
- Пользователь убрал Oculus, шейдеры не нужны.
- Essential используется.
- Для реального ПК рекомендовать 8–10 ГБ RAM.
- GitHub client smoke использует llvmpipe, поэтому подходит для проверки загрузки,
  но НЕ для FPS/шейдеров/долгой игровой стабильности.
