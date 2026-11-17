"""다른 서버(GCB)에서 옮긴 총 세 자루의 팩 층과 표 항목: ref/gcb/extracted(tools/props/gcb_import.py 가 꺼낸 모델, 텍스처)를
게이브 총 꼴로 바꿔 build/gcb/pack_layer/ 와 build/gcb/props_patch.json 을 쓴다. ref/gcb/extracted 가 없으면 아무것도 하지 않고 끝낸다
(그 자료가 없는 PC 에서도 빌드가 그대로 돌게). 그때는 전에 만든 층도 지운다.

python -X utf8 tools/props/build_gcb.py                    층과 표 항목
python -X utf8 tools/props/build_gcb.py --props            + tools/props.json 에 표 항목을 맞춰 넣음(없으면 뺌). build_props 다음에
python -X utf8 tools/props/build_gcb.py --pack <폴더>      + 릴리스 팩(build/Gave-pack.zip, Gave-sound.zip, 읽기만)에 이 층을 더한 시험 팩
python -X utf8 tools/props/build_gcb.py --shots <폴더>     + 같은 종류 기존 총과 나란히 놓은 그림(build/notes/gunrender.py 가 있어야 함)

- 모델은 build_arms.build_arm 과 같은 길(가운데 (8,8,8), 손잡이 G 로 표시 변환, 모든 면 tintindex 0, 총구 불꽃 muzzle_<키>, 아이템 정의)로
  굽는다. 세 모델 모두 탄창이 따로 떨어지는 부품이 아니라 <키>_nomag 는 없다. 그쪽의 사격, 재장전 몸짓 모델(shoot, reload, zoom)은 옮기지 않는다.
- 볼트 피스톨은 캐릭터 모델의 왼손 뼈를 구운 것이라 총구가 아래(-Y)를 본다: x 로 90 도 돌려 총구를 앞(-Z)으로.
- 마스티프, 렘페이지 총소리 gun.<키>.fire 와 부품 소리는 tools/props/gcb_sounds.py 가 숫자 표로 새로 만든 소리다. 미리듣기는 그 스크립트로.
  볼트 피스톨 소리는 모두 그 팩의 파일을 그대로 옮긴다(ORIGINAL, 2026-11-16 서버 운영자 요청): 사격 fire(남), fire_1p(쏜 사람),
  탄창 mag_out, mag_in, 탄착 폭발 boom(맞힌 곳마다), boom_block(블록에 맞으면 더).
- 표 항목(종류, 이름, 성능 배율)은 아래 GUNS 한곳. arms_table.py 의 줄과 같은 뜻이고 props.json "arms" 로 들어간다. 숫자는 제안값.
- 상점 총 그림(items/weapon_icon.json)은 통째로 덮는 파일이라 소품 층(없으면 릴리스 팩)의 것에 세 키를 더해 이 층에 다시 쓴다.
"""
import argparse
import json
import shutil
import sys
import zipfile
from pathlib import Path

import numpy as np

import build_props as P
import build_arms as BA
import build_more as BM
import arms_table
import gcb_sounds
import vmodel as V

ROOT = P.ROOT
SRC = ROOT / "ref/gcb/extracted"
OUT = ROOT / "build/gcb"
LAYER = OUT / "pack_layer"
NS = P.NS
CREDIT = ("GCB 서버 리소스팩", "", "Minecraft 26.3", "없음(권리 미확인, ref/gcb/_info.json)")

# 키: 종류(Guns.Kind), 이름, 모델 id(ref/gcb/extracted), rot(모델을 x 로 돌릴 각도), G(손잡이, 원본 좌표를 돌린 뒤 픽셀),
# len(손에 든 길이 블록), grip(1인칭 자리 종류), fp(1인칭 배율 / 3인칭 배율), 성능 배율(arms_table.py 와 같은 뜻, 제안값).
# dmg(한 발 피해 배율)도 다른 배율처럼 그대로 걸린다(모델마다 화력이 다름). 탄창, 재장전 반올림 몫만 Java(Guns.effective)가 한 발 피해로 갚는다
GUNS = {
    # 레버 액션 산탄총: 펌프 산탄총보다 조금 빨리 쏘고 탄이 적다(그쪽 값: 0.7초에 한 방, 6발, 한 발 넣기 8틱)
    "gcb_mastiff": dict(kind="SHOTGUN", name="마스티프", model="gcb:item/weapon/apexweapons/mastif/base", G=(8.0, 3.0, 19.8), len=1.15,
                        grip="two", fp=0.82, dmg=0.9, rps=1.19, magx=0.75, reload=0.89, spread=0.9),
    # 느리고 한 발이 무거운 경기관총(그쪽 값: 초당 5발, 40발, 재장전 80틱)
    "gcb_rampage": dict(kind="LMG", name="렘페이지", model="gcb:item/weapon/apexweapons/rampage/base", G=(8.0, 6.3, 12.8), len=1.35,
                        grip="two", fp=0.85, dmg=1.22, rps=0.82, magx=0.6, reload=0.89, spread=1.1),
    # 무거운 반자동 권총(그쪽 값: 초당 5발, 12발, 재장전 17틱): 대구경 권총 종류에서 빠르고 탄이 많은 쪽
    "gcb_boltpistol": dict(kind="REVOLVER", name="볼트 피스톨", model="modelengine:spacemarine/bolter", rot=90, G=(7.75, 8.8, 8.8), len=0.5,
                           grip="one", fp=0.9, dmg=0.85, rps=1.2, magx=1.6, reload=0.8, spread=1.15),
}
# 모델마다 다른 재장전 부품 소리(gun.<모델 키>.mag_out 같은 사건)를 팩에 넣을까. Java(Guns.partEvent)가 모델 키 사건을 먼저 찾고
# 없으면 종류 키 사건으로 물러나므로 켜 둔다(끄면 그 종류의 부품 소리가 난다)
PART_EVENTS = True
# 그 팩 파일을 그대로 옮기는 소리(볼트 피스톨 전부): 사건, 자막, ref/gcb/extracted 안 폴더(GCB_SND 아래), 개수, 들리는 거리, 음량.
# 거리 None 은 게이브 총 표의 값(_1p 는 사격과 같게). 탄착은 그쪽 sounds.json 의 256 그대로. 이 사건은 합성하지 않는다.
# 음량(sounds.json volume, 파일은 그대로): 원본이 게이브 다른 소리보다 커서 줄인다. 첫 250 ms 평균 크기를 재면 사격 3p -15.9, 1p -15.1 dBFS 로
# 다른 권총류(-22 ~ -24)와 전에 쓰던 합성음(-19, PACK)보다 커서 -19 에 맞춤. 블록 탄착 -10.5 는 로켓 폭발(-11)만큼이라 탄착(-15.4)에 맞춤.
# 탄창(-16 ~ -17)과 탄착(다른 탄착음 -13 ~ -16)은 그대로
GCB_SND = "assets/minecraft/sounds/char/spacemarine/"
ORIGINAL = {
    "gun.gcb_boltpistol.fire": ("볼트 피스톨 사격", "boltpistol/fire/3p", 6, None, 0.7),
    "gun.gcb_boltpistol.fire_1p": ("볼트 피스톨 사격", "boltpistol/fire/1p", 6, None, 0.64),
    "gun.gcb_boltpistol.mag_out": ("탄창 빠짐", "boltpistol/reload/out", 1, None, 1),
    "gun.gcb_boltpistol.mag_in": ("탄창 꽂음", "boltpistol/reload/in", 1, None, 1),
    "gun.gcb_boltpistol.boom": ("볼트 탄 폭발", "bolter/hit/entity", 6, 256, 1),
    "gun.gcb_boltpistol.boom_block": ("볼트 탄 폭발(블록)", "bolter/hit/block/rock", 5, 256, 0.57),
}
PART_KO = {"shell": "산탄 넣음", "reload_end": "산탄총 재장전 끝", "mag_out": "탄창 빠짐", "mag_in": "탄창 꽂음", "charge": "장전 손잡이 당김"}
# 층 맨 위 출처 파일(팩 맨 위로 들어간다: 이 층이 있을 때만 팩에 실림). 문구는 공개 전에 서버 운영자와 맞출 것
CREDITS = """게이브 리소스팩 GCB 총 층 출처(CREDITS-GCB.txt)
tools/props/build_gcb.py 가 만드는 파일이라 손으로 고치지 말 것. 팩 전체의 출처는 CREDITS.txt.

[GCB 1] 총 모델 셋: gcb_mastiff(마스티프), gcb_rampage(렘페이지), gcb_boltpistol(볼트 피스톨)
  서버 운영자가 운영하는 다른 마인크래프트 서버(GCB)의 리소스팩에서 가져온 Blockbench 직육면체 모델과 텍스처다.
  라이선스 표기가 없는 자료이고 누가 만들었는지는 그 팩의 기록에 없다.
  모양은 상용 게임의 총을 본뜬 팬 제작물이다: 마스티프와 렘페이지는 Apex Legends(Respawn Entertainment, Electronic Arts),
  볼트 피스톨은 Warhammer 40,000(Games Workshop). 그 회사들이 만들거나 승인한 것이 아니다. 권리자가 요청하면 내린다.
  바꾼 것(2026-11-14): 가운데와 앞 방향을 옮기고 표시 변환을 새로 적음, 볼트 피스톨은 총구가 앞을 보게 돌림,
  텍스처는 쓰는 조각만 다시 모음(픽셀 그대로), 총구 불꽃 모델(muzzle_*)과 아이템 정의를 더함.

[GCB 2] 마스티프, 렘페이지 총소리(assets/gave/sounds/gun/gcb/mastiff_*, rampage_*)
  게이브가 숫자 표로 새로 합성한 소리다(tools/props/gcb_sounds.py, 녹음 파일을 쓰지 않음, 2026-11-14).
  그쪽 서버에서 쓰던 총소리의 파형을 재서 층이 시작하는 시각과 감쇠 곡선의 숫자만 참고했다. 그 총소리 파일과 표본은 이 팩에 들어 있지 않다.

[GCB 3] 볼트 피스톨 소리 전부(assets/gave/sounds/gun/gcb/boltpistol_*.ogg)
  그 GCB 리소스팩의 소리 파일을 바이트 그대로 옮겼다(2026-11-16, 서버 운영자 요청): char/spacemarine/boltpistol/fire/3p 1~6(사격),
  fire/1p 1~6(쏜 사람이 듣는 사격), reload/out, reload/in(탄창), bolter/hit/entity 1~6, hit/block/rock 1~5(탄착 폭발).
  라이선스 표기가 없는 녹음 소리이고 누가 만들었는지는 그 팩의 기록에 없다. Warhammer 40,000(Games Workshop) 게임의 소리로 보인다.
  그 회사가 만들거나 승인한 것이 아니다. 권리자가 요청하면 내린다.
"""
MULS = (("dmg", "dmg"), ("rps", "rps"), ("magx", "mag"), ("reload", "reload"), ("spread", "spread"), ("range", "range"), ("pierce", "pierce"))


def setup():
    """build_props, build_arms 의 굽는 함수들을 이 층에 쓰게 돌린다(build_props.main 이 run(globals()) 로 하는 일)"""
    if LAYER.exists():
        shutil.rmtree(LAYER)
    P.OUT, P.A, P.REF = LAYER, LAYER / f"assets/{NS}", ROOT / "ref"
    BA.G.update(vars(P))
    BM.G.update(vars(P))


def build_gun(key, row):
    els = P.parts([row["model"]])
    if row.get("rot"):
        els = V.rotate_all(els, V.rx(P.D(row["rot"])), (8, 8, 8))
    lo, hi = V.vbounds(els)
    s_tp = round(row["len"] * 16 / (hi[2] - lo[2]), 4)
    s_fp = round(s_tp * row["fp"], 4)
    els, off = P.recenter(els)
    els, grip, k = BA.fit_span(els, np.array(row["G"], float) + off)
    disp = P.gun_display(els, grip, round(s_tp / k, 4), round(s_fp / k, 4), BA.AT[row["grip"]])
    P.write_model(key, els, disp, names=BA.tex_names(els))
    P.src(key, *CREDIT, f"{row['model']}, 요소 {len(els)}", real=row["name"], grip=P.r(grip, 3))
    m = BA.tint_all(key)
    flash = BA.FLASH[row["kind"]]
    BA.gun_item(key, flash)
    mz = BA.muzzle_point(m)
    return dict(len=row["len"], height=None, grip=row["grip"], G=P.r(grip, 2), muzzle=[round(float(x), 2) for x in mz], scale_tp=s_tp,
                shrink=round(k, 3), elements=len(els), flash=flash, dual=False)


def release(name):
    """릴리스 팩 zip 의 파일 하나(없으면 None)"""
    for z in ("Gave-pack.zip", "Gave-sound.zip"):
        p = ROOT / "build" / z
        if p.is_file():
            with zipfile.ZipFile(p) as f:
                if name in f.namelist():
                    return f.read(name)
    return None


def build_icon():
    """상점 총 그림: 소품 층(없으면 릴리스 팩)의 weapon_icon.json 에 세 키를 더한다"""
    rel = f"assets/{NS}/items/weapon_icon.json"
    base = ROOT / "build/props/pack_layer" / rel
    data = base.read_bytes() if base.is_file() else release(rel)
    if data is None:
        print("weapon_icon.json 을 못 찾음(소품 층도 릴리스 팩도 없음): 상점 그림은 권총으로 나옴")
        return
    icon = json.loads(data.decode("utf-8"))
    cases = icon["model"]["models"][1]["cases"]
    have = {c["when"] for c in cases}
    cases += [{"when": k, "model": {"type": "minecraft:model", "model": f"{NS}:item/{k}",
                                    "tints": [{"type": "minecraft:constant", "value": BA.WHITE}]}} for k in GUNS if k not in have]
    P.put_json(rel, icon)


def build_sounds():
    """소리: gcb_sounds 가 만든 표본을 모노 ogg 로, sounds.json 과 자막(팩 묶기가 다른 층 것과 키를 합침). props.json 조각을 돌려준다.
    총소리 gun.<키>.fire 는 변형 셋. 부품 소리 gun.<키>.<부품> 은 PART_EVENTS 가 켜졌을 때만 넣는다"""
    sj, ko, en, sounds, files = {}, {}, {}, {}, []

    def event(ev, sub, waves):
        entries, names = [], []
        for name, x in waves:
            dst = P.A / "sounds" / f"{name}.ogg"
            dst.parent.mkdir(parents=True, exist_ok=True)
            gcb_sounds.write_ogg(x, dst)
            e = {"name": f"{NS}:{name}"}
            if BM.attenuation(ev):
                e["attenuation_distance"] = BM.attenuation(ev)
            entries.append(e)
            names.append(f"{name}.ogg")
            files.append({"file": f"{name}.ogg", "from": "tools/props/gcb_sounds.py", "source": "게이브(직접 합성)",
                          "process": f"숫자 표로 합성한 소리(씨앗 고정), 모노 {gcb_sounds.RATE} Hz, libvorbis q7"})
        skey = f"subtitles.{NS}.{ev}"
        sj[ev] = {"sounds": entries, "subtitle": skey}
        ko[skey], en[skey] = sub, ev.replace(".", " ")
        sounds[f"{NS}:{ev}"] = {"files": names, "subtitle_ko": sub}
        if BM.attenuation(ev):
            sounds[f"{NS}:{ev}"]["attenuation_distance"] = BM.attenuation(ev)

    for key, row in GUNS.items():
        BM.MODEL_KIND[key] = arms_table.KINDS[row["kind"]][0]  # 들리는 거리 = 그 종류의 총소리 반경
        if f"gun.{key}.fire" not in ORIGINAL:
            event(f"gun.{key}.fire", f"{row['name']} 사격", [(f"gun/gcb/{key[4:]}_fire{i}", x) for i, x in enumerate(gcb_sounds.fire(key), 1)])
        if PART_EVENTS:
            for part, x in gcb_sounds.parts(key).items():
                if f"gun.{key}.{part}" not in ORIGINAL:
                    event(f"gun.{key}.{part}", PART_KO[part], [(f"gun/gcb/{key[4:]}_{part}", x)])
    for ev, (sub, folder, n, att, vol) in ORIGINAL.items():
        att = att or BM.attenuation(ev.removesuffix("_1p"))
        entries, names = [], []
        for i in range(1, n + 1):
            name = f"gun/gcb/boltpistol_{ev.split('.')[-1]}{i if n > 1 else ''}"
            dst = P.A / "sounds" / f"{name}.ogg"
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(SRC / GCB_SND / folder / f"{i}.ogg", dst)
            entries.append({"name": f"{NS}:{name}"} | ({"volume": vol} if vol != 1 else {}) | ({"attenuation_distance": att} if att else {}))
            names.append(f"{name}.ogg")
            files.append({"file": f"{name}.ogg", "from": "tools/props/gcb_import.py", "source": "GCB 서버 리소스팩(권리 미확인)",
                          "process": f"{GCB_SND}{folder}/{i}.ogg 바이트 그대로"})
        skey = f"subtitles.{NS}.{ev}"
        sj[ev] = {"sounds": entries, "subtitle": skey}
        ko[skey], en[skey] = sub, ev.replace(".", " ")
        sounds[f"{NS}:{ev}"] = {"files": names, "subtitle_ko": sub} | ({"attenuation_distance": att} if att else {})
    P.put_json(f"assets/{NS}/sounds.json", sj)
    P.put_json(f"assets/{NS}/lang/ko_kr.json", ko)
    P.put_json(f"assets/{NS}/lang/en_us.json", en)
    return sounds, files


def build():
    setup()
    info = {key: build_gun(key, row) for key, row in GUNS.items()}
    build_icon()
    sounds, files = build_sounds()
    arms, items = {}, {}
    for key, row in GUNS.items():
        a = {"kind": row["kind"], "name": row["name"], "src": "gcb"}
        a.update({j: row[f] for f, j in MULS if f in row})
        a["sound"] = f"gun.{key}.fire"
        a.update(info[key])
        arms[key] = a
        s = P.SOURCES[key]
        items[key] = {"item_model": f"{NS}:{key}", "source": f"{s['mod']} {s['version']}", "license": s["license"], "notes": s["detail"],
                      "real": s["real"], "grip": s["grip"]}
    (LAYER / "CREDITS-GCB.txt").write_text(CREDITS, encoding="utf-8")
    patch = {"items": items, "arms": arms, "sounds": sounds, "sound_files": files}
    (OUT / "props_patch.json").write_text(json.dumps(patch, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"GCB 총 {len(arms)}(요소 {sum(a['elements'] for a in arms.values())}) → {LAYER}")
    return patch


def apply_props(patch):
    """tools/props.json 에 GCB 항목을 맞춘다: 전에 넣은 것(src gcb)을 빼고 patch(없으면 None)를 넣는다"""
    p = ROOT / "tools/props.json"
    obj = json.loads(p.read_text(encoding="utf-8"))
    old = {k for k, a in obj.get("arms", {}).items() if a.get("src") == "gcb"}
    for k in old:
        obj["arms"].pop(k)
        obj["items"].pop(k, None)
        for ev in [e for e in obj["sounds"] if e.startswith(f"{NS}:gun.{k}.")]:
            obj["sounds"].pop(ev)
    for kj in obj.get("arms_kinds", {}).values():
        kj["models"] = [m for m in kj["models"] if m not in old]
    obj["sound_files"] = [f for f in obj.get("sound_files", []) if f.get("from") != "tools/props/gcb_sounds.py"]
    if patch:
        obj["items"].update(patch["items"])
        obj["arms"].update(patch["arms"])
        obj["sounds"].update(patch["sounds"])
        obj["sound_files"] += patch["sound_files"]
        for k, a in patch["arms"].items():
            obj["arms_kinds"][a["kind"]]["models"].append(k)
    p.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"tools/props.json: GCB 항목 {len(patch['arms']) if patch else 0}(뺀 것 {len(old)})")


def layer_files():
    return {f.relative_to(LAYER).as_posix(): f.read_bytes() for f in sorted(LAYER.rglob("*")) if f.is_file()}


def test_pack(folder):
    """릴리스 팩 둘에 이 층을 더한 시험 팩(sounds.json, 자막은 키를 합침). 릴리스 zip 은 읽기만"""
    sys.path.insert(0, str(ROOT / "tools"))
    import build_pack as BP
    folder.mkdir(parents=True, exist_ok=True)
    add = layer_files()
    for name in ("Gave-pack.zip", "Gave-sound.zip"):
        with zipfile.ZipFile(ROOT / "build" / name) as z:
            files = {n: z.read(n) for n in z.namelist() if not n.endswith("/")}
        want = [n for n in add if "/" not in n or bool(BP.SOUND_PATH.fullmatch(n)) == (name == "Gave-sound.zip")]  # 맨 위 파일은 두 팩 모두에
        for n in want:
            if n in files and BP.MERGE.fullmatch(n):
                both = json.loads(files[n].decode("utf-8"))
                both.update(json.loads(add[n].decode("utf-8")))
                files[n] = json.dumps(both, ensure_ascii=False, indent=1).encode("utf-8")
            else:
                files[n] = add[n]
        BP.pack_zip(folder / name, files).replace(folder / name)
        print(f"시험 팩 {folder / name}: 더한 파일 {len(want)}")


def shots(folder):
    """세 자루를 같은 종류 기존 총과 나란히: 옆모습, 비스듬한 모습, 1인칭, 인벤토리 칸(릴리스 팩 위에 이 층을 얹어 그림)"""
    sys.path.insert(0, str(ROOT / "build/notes"))
    import gunrender as R
    from PIL import Image
    folder.mkdir(parents=True, exist_ok=True)
    pack = R.Pack(overlay=layer_files())
    table = json.loads((ROOT / "tools/props.json").read_text(encoding="utf-8"))["arms"]
    table.update(json.loads((OUT / "props_patch.json").read_text(encoding="utf-8"))["arms"])
    near = {"gcb_mastiff": ("shotgun", "sg_killer_23"), "gcb_rampage": ("lmg", "sg_cogloader"), "gcb_boltpistol": ("revolver", "sg_bruiser")}
    stats = {}
    for key, others in near.items():
        rows = []
        for k in (key,) + others:
            row, stats[k] = R.gun_row(pack, k, table[k])
            rows.append(row)
        im = Image.new("RGB", (rows[0].width, sum(r.height for r in rows)), (28, 30, 36))
        for i, r in enumerate(rows):
            im.paste(r, (0, i * r.height))
        im.save(folder / f"{key}.png")
        R.one(key, folder / f"{key}_1인칭.png", pack)
    (folder / "stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=1), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--props", action="store_true")
    ap.add_argument("--pack")
    ap.add_argument("--shots")
    a = ap.parse_args()
    if not SRC.is_dir():
        shutil.rmtree(LAYER, ignore_errors=True)
        print("ref/gcb/extracted 없음: GCB 총은 건너뜀")
        if a.props:
            apply_props(None)
        return
    patch = build()
    if a.props:
        apply_props(patch)
    if a.pack:
        test_pack(Path(a.pack).resolve())
    if a.shots:
        shots(Path(a.shots).resolve())


if __name__ == "__main__":
    main()
