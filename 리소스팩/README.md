# 리소스팩

`Gave-pack.zip` 은 게이브 서버 리소스팩이다. 모습(모델, 텍스처, 셰이더, 글꼴, 언어)과 소리, 음악이 한 파일에 들어 있다.

- 빌드한 커밋: `c771daa`(브랜치 `overhaul`). 이 폴더는 그다음 커밋으로 들어간다. 2026-11-16 볼트 피스톨 소리 전부(사격, 1인칭 사격, 탄창, 탄착 폭발, 파일 25개)를 GCB 원본 파일로 바꿨다(`tools/props/build_gcb.py` 층에서 볼트 피스톨 소리 파일과 사건, 자막, `CREDITS-GCB.txt` 만 옮김).
- 크기 95,535,931 바이트, sha1 `fff5f1b120132fbcd34e69a8cb50fc25478e4009`(볼트 피스톨 원본 소리는 `sounds.json` volume 으로 다른 소리 크기에 맞춤)

## 쓰는 법

- 서버를 `-Dgave.packDir=<이 폴더>` 로 켜면 플러그인이 이 팩을 읽고 팩 포트(`-Dgave.packPort`, 기본 8290)로 접속자에게 보낸다. 깃허브 릴리스에서 받게 하려면 `python -X utf8 tools/upload_pack.py --public --dir 리소스팩` 으로 올린 뒤 `-Dgave.packRepo=<저장소>` 를 더한다.
- 팩이 하나라서 옛 꼴의 소리 팩 `Gave-sound.zip` 은 같은 폴더에 두지 않는다. 소리 팩이 없으면 플러그인은 이 팩 하나만 보낸다.
- 짝이 되는 플러그인은 이 브랜치 소스로 빌드한다(`bash tools/build_plugin.sh` 또는 `python -X utf8 tools/build_all.py`). HUD 글리프 표 `HudSpec.java` 가 팩과 짝이라 다른 커밋의 jar 와 섞지 않는다.

## 빌드 환경

- 팩 생성기는 Pillow 11.1.0 으로 돌렸다.
- 총 소리 48개(와 받은 효과음으로 굽는 크리처 소리)는 빌드한 PC 에 원본이 없어 gave-pack 릴리스 소리 팩의 같은 파일을 그대로 넣었다. 그래서 `CREDITS.txt` 의 소리 출처 글도 그 릴리스와 같다.
- 새 총 셋(마스티프, 렘페이지, 볼트 피스톨)의 모델 원본 `ref/gcb/extracted` 는 저장소에 없다. 그것 없이 다시 빌드하면 이 셋이 빠진다(`README.md` 'ref 다시 받기').
