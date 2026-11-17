"""다른 서버(GCB) 리소스팩에서 총 세 자루의 모델과 텍스처만 ref/gcb/extracted/ 로 꺼내고 ref/gcb/_info.json 에 적는다.
바이트 그대로 복사하고 sha256 을 남긴다(변환은 tools/props/build_gcb.py 몫). 그 팩은 읽기만 한다.

python -I -X utf8 tools/props/gcb_import.py <GCB 리소스팩 폴더(맨 위에 assets/)>
마스티프, 렘페이지 총소리는 꺼내지 않는다(tools/props/gcb_sounds.py 가 숫자 표로 새로 만든다). 볼트 피스톨 소리(사격, 탄창, 탄착 폭발)는 그 팩의 파일을 그대로 꺼낸다
(2026-11-16 서버 운영자 "볼트피스톨 폭발음은 gcb 프로젝트에서 그대로 가져오기"). ref/ 는 저장소에 올리지 않는다.
"""
import hashlib
import json
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "ref/gcb"
# 팩 안 경로(그대로 ref/gcb/extracted/ 아래에). 모델과 그 텍스처 한 장씩
FILES = [
    "assets/gcb/models/item/weapon/apexweapons/mastif/base.json",
    "assets/gcb/textures/item/weapon/apexweapons/mastif/mastif.png",
    "assets/gcb/models/item/weapon/apexweapons/rampage/base.json",
    "assets/gcb/textures/item/weapon/apexweapons/rampage/rampage.png",
    "assets/modelengine/models/spacemarine/bolter.json",
    "assets/modelengine/textures/entity/models/spacemarine_boltpistol.png",
]
# 볼트 피스톨 소리(그쪽 사건 char.spacemarine.boltpistol.fire.3p, .fire.1p, .reload.out, .reload.in, char.spacemarine.bolter.hit.entity, .hit.block.rock)
SND = "assets/minecraft/sounds/char/spacemarine/"
FILES += [f"{SND}boltpistol/fire/{v}/{i}.ogg" for v in ("3p", "1p") for i in range(1, 7)]
FILES += [f"{SND}boltpistol/reload/{v}/1.ogg" for v in ("out", "in")]
FILES += [f"{SND}bolter/hit/entity/{i}.ogg" for i in range(1, 7)] + [f"{SND}bolter/hit/block/rock/{i}.ogg" for i in range(1, 6)]


def main():
    if len(sys.argv) != 2 or not (Path(sys.argv[1]) / "assets").is_dir():
        sys.exit(__doc__)
    src = Path(sys.argv[1])
    files = {}
    for rel in FILES:
        data = (src / rel).read_bytes()
        dst = OUT / "extracted" / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(data)
        files[rel] = hashlib.sha256(data).hexdigest()
    info = {
        "title": "GCB 서버 리소스팩(Minecraft 26.3)의 총 모델 셋",
        "from": "의뢰인이 운영하는 다른 마인크래프트 서버(GCB)의 리소스팩. 공개 받는 주소 없음: 의뢰인에게서 ref/gcb/extracted 를 받거나 "
                "그 팩 폴더로 tools/props/gcb_import.py 를 돌린다",
        "date": date.today().isoformat(),
        "license": "없음(권리 미확인)",
        "license_note": "라이선스 표기가 없는 자료다. 모델 셋 모두 Blockbench 로 만든 직육면체 모델이고 모양은 상용 게임의 총을 본떴다"
                        "(mastif, rampage: Apex Legends 의 Mastiff, Rampage. bolter: Warhammer 40,000 의 볼트 피스톨). "
                        "누가 만들었는지는 그 팩의 기록에 없다. 공개 팩에 넣을지는 의뢰인이 정한다",
        "items": {
            "gcb_mastiff": "assets/gcb/models/item/weapon/apexweapons/mastif/base.json (요소 45, 텍스처 64x64)",
            "gcb_rampage": "assets/gcb/models/item/weapon/apexweapons/rampage/base.json (요소 271, 텍스처 128x128)",
            "gcb_boltpistol": "assets/modelengine/models/spacemarine/bolter.json (요소 56, 텍스처 128x128. ModelEngine 이 캐릭터 모델의 "
                              "왼손 권총 뼈를 구운 것, 총구가 아래를 봄)",
        },
        "sounds": "마스티프, 렘페이지 총소리는 꺼내지 않음(tools/props/gcb_sounds.py 가 새로 합성). 볼트 피스톨 소리(char/spacemarine/boltpistol/fire/3p, 1p 6개씩, reload/out, in, bolter/hit/entity 6개, hit/block/rock 5개)는 파일 그대로. 녹음 소리이고 출처는 그 팩의 기록에 없다(Warhammer 40,000 게임에서 온 것으로 보임)",
        "files": files,
    }
    (OUT / "_info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(files)} 파일 → {OUT / 'extracted'}")


if __name__ == "__main__":
    main()
