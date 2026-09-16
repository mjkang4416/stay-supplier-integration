# 통합 모델 설계

> 확정된 설계를 명세한다. 결정의 근거는 [README.md](../README.md) 4.1·4.2, 결정 과정은 [JOURNAL.md](../JOURNAL.md)에 있다.

## 1. 표준 숙박 상품 모델

### 1.1 숙소
<!-- 필드, 타입, 의미 -->

### 1.2 객실 타입
<!-- 필드, 타입, 의미 -->

### 1.3 요금
<!-- 필드, 타입, 의미, 계산 규칙 -->

### 1.4 재고
<!-- 필드, 타입, 의미 -->

## 2. 공급사 필드 대응

구현 상태: ① 숙소 목록까지. ② 재고·요금은 검색 API와 함께 추가한다.

### 2.1 숙소 목록 (①) → `SupplierHotel` · `SupplierRoomType` → 매핑 테이블

| 표준 형태 · 컬럼 | Supplier A (`GET /a/v1/hotels`) | Supplier B (`GET /b/api/properties`) | 변환 규칙 |
|---|---|---|---|
| `supplier` · `hotel_mapping.supplier` | 상수 `A` | 상수 `B` | 어댑터가 자기 값을 넣는다 |
| `hotelCode` · `hotel_mapping.supplier_hotel_code` | `items[].hotelCode` | `data.items[].propertyId` | 필수. 없으면 그 항목 격리 |
| `hotelName` · `hotel_mapping.hotel_name` | `items[].hotelName` | `data.items[].propertyName` | 필수. 하루 1회 덮어쓴다 |
| `room_type_mapping.hotel_id` | 숙소 upsert가 돌려준 `id` | 같음 | 숙소를 먼저 저장해야 하므로 두 단계 |
| `roomTypeCode` · `room_type_mapping.supplier_room_type_code` | `roomTypes[].roomTypeCode` | `rooms[].roomId` | 필수. 숙소 안에서만 유일 |
| `roomTypeName` · `room_type_mapping.room_type_name` | `roomTypes[].roomTypeName` | `rooms[].roomName` | 필수 |
| `maxOccupancy` · `room_type_mapping.max_occupancy` | `roomTypes[].maxOccupancy` | `rooms[].maxOccupancy` | 없거나 1 미만이면 기본값 대신 NULL(미상)로 저장하고 로그. 검색 응답에서는 계약상 필수이므로 ② 응답 값 → 매핑 값 순으로 채우고, 둘 다 없으면 그 객실 타입을 응답에서 제외한다 |
| `active` | 응답에 없음 | 응답에 없음 | 델타 계산 결과(3.2) |
| 버리는 것 | 없음 | `resultCode`, `resultMessage` (판정에만 사용) | 공급사 원본은 저장하지 않는다 |

정상 0건은 `items: []`(A) / `data.items: []`(B)이고, 그 구조 자체가 없으면 깨진 응답(`INVALID_RESPONSE`)으로 본다. 판정 규칙은 [architecture 4.2](architecture.md).

## 3. 매핑

공급사 코드와 자사 내부 식별자의 대응만 저장한다. 요금·재고는 저장하지 않는다.

### 3.1 테이블 스키마

`src/main/resources/schema.sql`로 적용한다 (테스트도 같은 파일 사용).

**hotel_mapping** (숙소)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK, AUTO_INCREMENT | 내부 숙소 식별자 |
| `supplier` | VARCHAR(20) | 공급사 코드 (`A`, `B`) |
| `supplier_hotel_code` | VARCHAR(100) | 공급사 숙소 코드 (`hotelCode` / `propertyId`) |
| `hotel_name` | VARCHAR(255) | 숙소명 (매핑 관리·병합 대비용. 검색 응답은 재고·요금 응답 값을 우선) |
| `active` | BOOLEAN | 최근 갱신 목록에 있으면 true, 사라졌으면 false. false로 바뀐 시각은 `updated_at`으로 확인 |
| `created_at`, `updated_at` | DATETIME | |
| UNIQUE `uk_supplier_hotel` | (`supplier`, `supplier_hotel_code`) | 같은 공급사 코드는 항상 같은 행 |

**room_type_mapping** (객실 타입)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK, AUTO_INCREMENT | 내부 객실 타입 식별자 |
| `hotel_id` | BIGINT FK → hotel_mapping.id | 소속 숙소 |
| `supplier_room_type_code` | VARCHAR(100) | 공급사 객실 타입 코드 (`roomTypeCode` / `roomId`) |
| `room_type_name` | VARCHAR(255) | 객실 타입명 |
| `max_occupancy` | INT NULL | 최대 수용 인원. 공급사가 주지 않으면 NULL(미상) |
| `active` | BOOLEAN | |
| `created_at`, `updated_at` | DATETIME | |
| UNIQUE `uk_hotel_room` | (`hotel_id`, `supplier_room_type_code`) | 객실 타입 코드는 숙소 안에서만 유일 |

예시 (Mock 데이터 기준)

| hotel_mapping.id | supplier | supplier_hotel_code | room_type_mapping.id | supplier_room_type_code |
|---|---|---|---|---|
| 1 | A | A-10023 | 11 | DLX-TWN |
| 2 | A | A-10044 | 12 | STD-DBL |
| 3 | B | B77120 | 13 | R-401 |

A-10023과 B77120은 실제로 같은 숙소이지만 공급사가 다르므로 별도 식별자를 갖는다.

### 3.2 생성 방식과 시점

쓰는 쪽(갱신 잡)과 읽는 쪽(웹 앱)을 프로세스 수준에서 분리한다. 같은 애플리케이션 이미지를 프로필로 나눠 쓴다.

| 역할 | 실행 | 동작 |
|---|---|---|
| 갱신 잡 (`sync` 프로필) | K8s CronJob이 매일 04:00 Asia/Seoul에 별도 팟으로 실행. 로컬은 `./gradlew bootRun --args='--spring.profiles.active=sync'` | 웹 서버 없이 기동 → 공급사 숙소 목록 호출 → 델타 계산 → DB 반영 → 종료 |
| 웹 앱 (기본 프로필) | Deployment | 기동 시 DB의 active 매핑(숙소·객실 타입)을 인메모리에 동기 로드. 매핑은 하루 1회만 바뀌므로 매일 04:30 Asia/Seoul(크론잡 30분 뒤, 설정값)에 한 번 더 읽어 교체한다. 크론잡을 수동으로 즉시 돌린 경우는 웹 팟 롤링 재시작으로 반영한다(목표 구조에서는 Redis pub/sub 알림으로 대체 가능). 공급사 숙소 목록 API는 호출하지 않음 |
| 수동 갱신 | 엔드포인트 없음 | 필요하면 CronJob을 즉시 실행(`kubectl create job --from=cronjob/...`) |

시각은 한국 시간 03~05시 창 안에 둔다. 국내 숙박 앱 시간대별 검색량은 공개 자료가 없어 일반 웹 트래픽 최저 시간대를 대신 기준으로 잡은 값이며, 운영에서 시간대별 검색량 지표로 조정한다. DB·컨테이너는 UTC를 쓰므로 CronJob 스케줄에 타임존을 명시한다.

갱신 잡 처리 순서 (공급사별로 델타를 계산해 반영한다)
1. 공급사 숙소 목록 API를 호출한다. 실패한 공급사는 건너뛰고 로그를 남긴다. 실패한 공급사의 매핑은 건드리지 않는다.
2. 델타 계산: `fetched` = 이번 목록의 코드 집합, `current` = DB에서 `supplier` 기준 `active=true`인 코드 집합.
   - 추가 = `fetched − current` → 삽입. 같은 코드의 비활성 행이 있으면 새로 만들지 않고 `active=true`로 되돌린다.
   - 사라짐 = `current − fetched` → `active=false`. 행은 삭제하지 않는다.
   - 변경 = 교집합 중 이름·최대 인원이 다른 것 → 갱신.
3. 숙소 델타를 반영한 뒤 객실 타입도 숙소별로 같은 방식으로 반영한다 (`(hotel_id, supplier_room_type_code)` 기준).
4. (요금·재고 캐시가 있다면) 사라진 숙소·객실 타입의 캐시 키를 삭제한다. 웹 앱 인메모리는 크론잡이 건드리지 않고, 웹 앱이 매일 04:30에 DB를 다시 읽어 반영한다.
5. 공급사별 추가·사라짐·변경 건수와 실패 여부를 로그에 남기고 종료한다.

읽기
- 웹 앱은 `active = true`인 매핑을 인메모리에 올려 두고, 검색은 인메모리에서 공급사별 숙소 코드 묶음을 만든다.
- 인스턴스가 여러 대여도 갱신 잡은 CronJob 하나만 돌고, 각 인스턴스는 같은 시각에 같은 DB를 읽으므로 값이 같아진다. 인메모리 캐시는 매핑 하나뿐이고, 요금·재고는 Redis(설계만)가 맡는다.

### 3.3 식별자 규칙

- 내부 식별자는 BIGINT 자동 증가 값이다. 검색 응답에는 공급사 코드가 아니라 이 값이 나간다.
- 같은 공급사 코드는 유니크 키와 upsert로 항상 같은 행에 대응한다. 동시 실행도 유니크 키가 막는다.
- 행은 삭제하지 않는다. 목록에서 사라진 숙소는 `active=false`가 되고, 다시 나타나면 같은 행이 `active=true`로 돌아온다.
- 서로 다른 공급사의 같은 숙소는 별도 식별자를 갖는다. 하나로 합치는 것은 선택 항목(중복 상품 병합)이다.

### 3.4 공급사가 판매를 종료했을 때

| 대상 | 처리 |
|---|---|
| 매핑 행 | 유지. `active=false` (바뀐 시각은 `updated_at`) |
| 내부 식별자를 참조하는 우리 데이터 (찜, 예약 내역, 리뷰 등) | 유지 |
| 검색 대상 | 제외 (`active=true`만 조회) |
| 캐시(선택)의 재고·요금 값 | 삭제 |
| 다시 판매를 시작하면 | 갱신 잡의 upsert가 같은 행을 `active=true`로 되돌린다. 식별자와 우리 데이터가 그대로 이어진다 |

## 4. 제약과 주의점

- 매핑 테이블에 요금·재고를 넣지 않는다.
- 매핑 행을 삭제하는 코드를 두지 않는다. 식별자 안정성이 깨진다.
- DB를 초기화하면 식별자가 달라진다. 운영에서는 초기화하지 않는다.
