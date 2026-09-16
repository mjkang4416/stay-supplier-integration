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
<!-- 표준 모델 필드 | Supplier A | Supplier B | 변환 규칙 -->

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
| `max_occupancy` | INT | 최대 수용 인원 |
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

| 트리거 | 동작 |
|---|---|
| 앱 기동 시 | 갱신 잡 실행 후 인메모리 로드 |
| 매일 1회 (`@Scheduled`, 기본 04:00 **Asia/Seoul**, 설정값) | 갱신 잡 실행 후 인메모리 교체. 한국 시간 03~05시 창 안에 둔다. 국내 숙박 앱 시간대별 검색량은 공개 자료가 없어 일반 웹 트래픽 최저 시간대를 대신 기준으로 잡은 값이며, 운영에서 시간대별 검색량 지표로 조정. DB·컨테이너는 UTC를 쓰므로 스케줄에 타임존을 명시한다 |
| 수동 엔드포인트 | 갱신 잡 실행 후 인메모리 교체 |

갱신 잡 처리 순서 (공급사별로 델타를 계산해 반영한다)
1. 공급사 숙소 목록 API를 호출한다. 실패한 공급사는 건너뛰고 로그를 남긴다. 실패한 공급사의 매핑은 건드리지 않는다.
2. 델타 계산: `fetched` = 이번 목록의 코드 집합, `current` = DB에서 `supplier` 기준 `active=true`인 코드 집합.
   - 추가 = `fetched − current` → 삽입. 같은 코드의 비활성 행이 있으면 새로 만들지 않고 `active=true`로 되돌린다.
   - 사라짐 = `current − fetched` → `active=false`. 행은 삭제하지 않는다.
   - 변경 = 교집합 중 이름·최대 인원이 다른 것 → 갱신.
3. 숙소 델타를 반영한 뒤 객실 타입도 숙소별로 같은 방식으로 반영한다 (`(hotel_id, supplier_room_type_code)` 기준).
4. 인메모리 매핑을 교체하고, 사라진 숙소·객실 타입은 인메모리와 (있다면) 캐시에서 제거한다.
5. 공급사별 추가·사라짐·변경 건수와 실패 여부를 로그에 남긴다.

읽기
- 앱은 `active = true`인 매핑을 인메모리에 올려 두고, 검색은 인메모리에서 공급사별 숙소 코드 묶음을 만든다.
- 다중 인스턴스 환경 설계: 갱신 잡을 별도 프로세스(CronJob)로 분리하고, 각 인스턴스는 갱신 시각을 주기적으로 확인해 바뀌었을 때 리로드한다.

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
