# Cursed_Inventory

`Cursed_Inventory`는 Paper 1.21 서버용 플러그인으로, **인벤토리 슬롯 잠금/해금 상태와 아이템 상태를 서버 접속자 전체가 공유**하도록 만드는 협동형 진행 플러그인입니다.

## 참고한 자료 (YouTube)

[![불편한 동거](https://img.youtube.com/vi/VIDEO_ID/hqdefault.jpg)](https://www.youtube.com/playlist?list=PLAfXl9hcOzqDS-7l76dcBPzkM8hQ_aUm6)

## 주요 기능

- **공유 인벤토리 진행도**
  - 일반 인벤토리 36칸 + 방어구 4칸 + 보조손 1칸(총 41칸)을 추적합니다.
  - 어떤 플레이어가 슬롯을 해금하면 모든 플레이어에게 동일하게 적용됩니다.
- **슬롯 잠금 시스템**
  - 시작 시 모든 추적 슬롯이 잠기며, 잠긴 슬롯은 배리어 아이템으로 표시됩니다.
  - 잠긴 슬롯의 아이템 이동/드래그/핫바 스왑/드롭/우클릭 사용/설치 등을 차단합니다.
- **블록 채굴 기반 해금**
  - 슬롯마다 해금에 필요한 블록(Material)이 배정됩니다.
  - 플레이어가 해당 블록을 채굴하면 슬롯이 해금되고 연출(메시지/폭죽)이 발생합니다.
- **해금 방식 커스터마이징**
  - 해금 블록 목록을 `materials`로 직접 지정하거나, `tags` 기반으로 자동 구성할 수 있습니다.
  - 랜덤 풀(`random`) 또는 복합 소스(`combined`)로도 구성 가능합니다.
  - 슬롯 배정 방식은 `shuffled`(중복 최소) / `random`(중복 허용) 중 선택할 수 있습니다.
- **진행도 영속화(SQLite)**
  - 플러그인 데이터 폴더의 `progress.db`에 진행도가 저장됩니다.
  - 서버 재시작 후에도 해금 상태 및 공유 아이템 상태를 복원합니다.

## config에서 수정 가능한 항목

플러그인 첫 실행 후 기본 `config.yml`이 생성되며, 아래 항목을 수정할 수 있습니다.

```yml
display:
  show-unlock-requirements-on-wall: false

unlock-blocks:
  source: tags
  assignment-mode: shuffled
  materials: []
  tags:
    - minecraft:mineable/pickaxe
    - minecraft:mineable/axe
    - minecraft:mineable/shovel
    - minecraft:mineable/hoe
  random:
    seed: -1
    pool-size: 64
    include-non-solid: false
    include-interactable: false
    exclude:
      - AIR
      - CAVE_AIR
      - VOID_AIR
      - BARRIER
      - BEDROCK
```

### 1) `display.show-unlock-requirements-on-wall`

- 타입: `boolean`
- 기본값: `false`
- 설명:
  - `true`: 잠긴 배리어 아이템 설명에 `Mine <블록명> to unlock.` 형태로 해금 조건을 표시합니다.
  - `false`: 잠금 문구(`잠김`)만 표시합니다.

### 2) `unlock-blocks.source`

- 타입: `string`
- 기본값: `tags`
- 지원값:
  - `configured`: `unlock-blocks.materials` 목록만 사용
  - `tags`: `unlock-blocks.tags` 태그로 블록 풀 구성
  - `random`: 랜덤 후보 풀에서 생성
  - `combined`: `materials + tags + random` 결과를 합쳐 사용
- 참고:
  - 잘못된 값은 자동으로 `tags`로 폴백됩니다.

### 3) `unlock-blocks.assignment-mode`

- 타입: `string`
- 기본값: `shuffled`
- 지원값:
  - `shuffled`: 준비된 블록 풀을 셔플해 슬롯에 순차 배정
  - `random`: 슬롯마다 블록을 독립 랜덤 배정(중복 가능)
- 참고:
  - 잘못된 값은 자동으로 `shuffled`로 폴백됩니다.

### 4) `unlock-blocks.materials`

- 타입: `list<string>`
- 기본값: `[]`
- 설명:
  - `configured` 또는 `combined` 소스에서 사용하는 직접 지정 블록 목록입니다.
  - 유효한 Bukkit `Material` 이름(블록이면서 아이템이어야 함)만 반영됩니다.

### 5) `unlock-blocks.tags`

- 타입: `list<string>`
- 기본값:
  - `minecraft:mineable/pickaxe`
  - `minecraft:mineable/axe`
  - `minecraft:mineable/shovel`
  - `minecraft:mineable/hoe`
- 설명:
  - `tags` 또는 `combined` 소스에서 사용하는 블록 태그 목록입니다.
  - 형식이 잘못되었거나 존재하지 않는 태그는 무시됩니다.

### 6) `unlock-blocks.random.*`

#### `unlock-blocks.random.seed`
- 타입: `long`
- 기본값: `-1`
- 설명:
  - `-1`이면 서버 시점 기반 시드(매번 달라짐)를 사용합니다.
  - 특정 숫자를 넣으면 랜덤 결과를 재현하기 쉬워집니다.

#### `unlock-blocks.random.pool-size`
- 타입: `int`
- 기본값: `64`
- 설명:
  - 랜덤 후보 풀에서 샘플링할 블록 개수입니다.
  - 최소 1 이상으로 보정됩니다.

#### `unlock-blocks.random.include-non-solid`
- 타입: `boolean`
- 기본값: `false`
- 설명:
  - `false`면 비고체 블록 제외
  - `true`면 비고체 블록 포함 가능

#### `unlock-blocks.random.include-interactable`
- 타입: `boolean`
- 기본값: `false`
- 설명:
  - `false`면 상호작용 가능한 블록 제외
  - `true`면 상호작용 블록 포함 가능

#### `unlock-blocks.random.exclude`
- 타입: `list<string>`
- 기본값: `[AIR, CAVE_AIR, VOID_AIR, BARRIER, BEDROCK]`
- 설명:
  - 랜덤 풀에서 강제로 제외할 Material 목록입니다.

## 주의 사항

- 해금 블록 풀이 비어버리면 플러그인은 내부 폴백 순서(`default tags` → `random pool`)로 자동 복구를 시도합니다.
- 잠금 상태 배리어에는 내부 태그가 들어가므로, 일반 배리어와 구분되어 이동/설치 제한이 동작합니다.
