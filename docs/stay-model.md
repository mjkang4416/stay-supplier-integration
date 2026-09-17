# 통합 모델 설계

> 확정된 설계를 명세해요. 결정의 근거는 [README.md](../README.md) 4.1·4.2, 결정 과정은 [JOURNAL.md](../JOURNAL.md)에 있어요.

## 1. 표준 숙박 상품 모델

단위는 숙소 → 객실 타입 → 날짜별 재고·요금이에요. 어댑터가 공급사 응답을 `stay` 패키지의 아래 형태로 바꾸고, 식별자는 공급사 코드 그대로 두며 내부 식별자는 3장의 매핑이 붙여요. 요금은 세금 포함 총액, 재고는 날짜별 예약 가능 객실 수예요. 무엇을 살리고 버렸는지와 그 이유는 [README 4.1](../README.md)에, 검색 응답 형태는 [stay-search-api.md](stay-search-api.md)에 있어요.

| 표준 형태 | 필드 | 타입 | 뜻 |
|---|---|---|---|
| `SupplierHotel` · ① 숙소 목록 | `supplier` | `A` 또는 `B` | 출처 공급사 |
| | `hotelCode` | string | 공급사 숙소 코드. 공급사 안에서만 유일 |
| | `hotelName` | string | 숙소명 |
| | `roomTypes` | list | 객실 타입 목록 |
| `SupplierRoomType` | `roomTypeCode` | string | 공급사 객실 타입 코드. 숙소 안에서만 유일 |
| | `roomTypeName` | string | 객실 타입명 |
| | `maxOccupancy` | int, null 허용 | 최대 수용 인원, 성인과 아동 합산. 공급사가 주지 않으면 null로 미상 |
| `SupplierRoomOffer` · ② 기간 조회, 검색 응답 | `totalPrice` | long | 요청 기간 총액, 세금 포함, 통화 최소 단위 정수 |
| | `availableRooms` | int | 요청 기간 전체를 예약할 수 있는 객실 수. 날짜별 재고의 최솟값이고 0이면 예약 불가 |
| | `currency` | string | ISO 4217 |
| | `breakfastIncluded` | boolean | 요금에 조식이 포함되는지 |
| | `maxOccupancy` | int, null 허용 | 재고·요금 응답이 준 최대 인원 |
| `SupplierDailyOffer` · ② 날짜별 조회, 캐시 채우기 | `date` | date | 숙박일 |
| | `remainingRooms` | int | 그날 예약 가능한 객실 수 |
| | `nightlyTotal` | long | 그날 1박 요금, 세금 포함 |
| `CachedRate` · Redis 필드 값 | 재고, 세금 포함 1박 요금, 통화, 조식, 최대 인원 | 세로 막대로 이은 문자열 | 캐시 필드 하나의 값. 형식은 architecture 4.8 |

## 2. 공급사 필드 대응

① 숙소 목록의 필드 대응은 아래 표예요. ② 재고·요금의 필드 대응은 1장의 요금·재고 표에 있어요.

### 2.1 숙소 목록 (①) → `SupplierHotel` · `SupplierRoomType` → 매핑 테이블

| 표준 필드 | 저장 컬럼 | Supplier A `GET /a/v1/hotels` | Supplier B `GET /b/api/properties` | 변환 규칙 |
|---|---|---|---|---|
| `supplier` | `hotel_mapping.supplier` | 상수 `A` | 상수 `B` | 어댑터가 자기 값을 넣어요 |
| `hotelCode` | `hotel_mapping.supplier_hotel_code` | `items[].hotelCode` | `data.items[].propertyId` | 필수. 없으면 그 항목 격리 |
| `hotelName` | `hotel_mapping.hotel_name` | `items[].hotelName` | `data.items[].propertyName` | 필수. 하루 1회 덮어써요 |
| 없음 | `room_type_mapping.hotel_id` | 숙소 upsert가 돌려준 `id` | 같음 | 숙소를 먼저 저장해야 하므로 두 단계 |
| `roomTypeCode` | `room_type_mapping.supplier_room_type_code` | `roomTypes[].roomTypeCode` | `rooms[].roomId` | 필수. 숙소 안에서만 유일 |
| `roomTypeName` | `room_type_mapping.room_type_name` | `roomTypes[].roomTypeName` | `rooms[].roomName` | 필수 |
| `maxOccupancy` | `room_type_mapping.max_occupancy` | `roomTypes[].maxOccupancy` | `rooms[].maxOccupancy` | 없거나 1 미만이면 기본값 대신 NULL로 미상 처리해 저장하고 로그. 검색 응답에서는 계약상 필수이므로 ② 응답 값 → 매핑 값 순으로 채우고, 둘 다 없으면 그 객실 타입을 응답에서 제외해요 |
| 없음 | `room_type_mapping.active`, `hotel_mapping.active` | 응답에 없음 | 응답에 없음 | 3.2 델타 계산 결과 |
| 버리는 것 | 없음 | 없음 | `resultCode`·`resultMessage`, 판정에만 써요 | 공급사 원본은 저장하지 않아요 |

정상 0건은 A는 `items: []`, B는 `data.items: []`이고, 그 구조 자체가 없으면 깨진 응답 `INVALID_RESPONSE`로 봐요. 판정 규칙은 [architecture 4.2](architecture.md).

## 3. 매핑

공급사 코드와 자사 내부 식별자의 대응만 저장해요. 요금·재고는 저장하지 않아요.

### 3.1 테이블 스키마

`src/main/resources/schema.sql`로 적용하고 테스트도 같은 파일을 써요.

**hotel_mapping**, 숙소

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK, AUTO_INCREMENT | 내부 숙소 식별자 |
| `supplier` | VARCHAR(20) | 공급사 코드 `A`·`B` |
| `supplier_hotel_code` | VARCHAR(100) | 공급사 숙소 코드, `hotelCode` 또는 `propertyId` |
| `hotel_name` | VARCHAR(255) | 숙소명. 매핑 관리·병합 대비용이고 검색 응답은 재고·요금 응답 값을 우선해요 |
| `active` | BOOLEAN | 최근 갱신 목록에 있으면 true, 사라졌으면 false. false로 바뀐 시각은 `updated_at`으로 확인 |
| `created_at`, `updated_at` | DATETIME | |
| UNIQUE `uk_supplier_hotel` | `supplier` + `supplier_hotel_code` | 같은 공급사 코드는 항상 같은 행 |

**room_type_mapping**, 객실 타입

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK, AUTO_INCREMENT | 내부 객실 타입 식별자 |
| `hotel_id` | BIGINT FK → hotel_mapping.id | 소속 숙소 |
| `supplier_room_type_code` | VARCHAR(100) | 공급사 객실 타입 코드, `roomTypeCode` 또는 `roomId` |
| `room_type_name` | VARCHAR(255) | 객실 타입명 |
| `max_occupancy` | INT NULL | 최대 수용 인원. 공급사가 주지 않으면 NULL로 미상 |
| `active` | BOOLEAN | |
| `created_at`, `updated_at` | DATETIME | |
| UNIQUE `uk_hotel_room` | `hotel_id` + `supplier_room_type_code` | 객실 타입 코드는 숙소 안에서만 유일 |

예시, Mock 데이터 기준

| hotel_mapping.id | supplier | supplier_hotel_code | room_type_mapping.id | supplier_room_type_code |
|---|---|---|---|---|
| 1 | A | A-10023 | 11 | DLX-TWN |
| 2 | A | A-10044 | 12 | STD-DBL |
| 3 | B | B77120 | 13 | R-401 |

A-10023과 B77120은 실제로 같은 숙소이지만 공급사가 다르므로 별도 식별자를 가져요.

### 3.2 생성 방식과 시점

쓰는 갱신 잡과 읽는 웹 앱을 프로세스 수준에서 분리해요. 같은 애플리케이션 이미지를 프로필로 나눠 써요.

| 역할 | 실행 | 동작 |
|---|---|---|
| 갱신 잡, `sync` 프로필 | K8s CronJob이 매일 04:00 Asia/Seoul에 별도 팟으로 실행. 로컬은 `./gradlew :bootRun --args='--spring.profiles.active=sync'` | 웹 서버 없이 기동 → 공급사 숙소 목록 호출, 일시 장애는 고정 30초 × 3회 재시도 → 델타 계산 → DB 반영 → 종료 코드, 전부 성공 0·실패 1 |
| 웹 앱, 기본 프로필 | Deployment | 기동 시 웹 서버가 뜨기 전에 DB의 active 숙소·객실 타입 매핑을 인메모리에 동기 로드하고, DB 연결 실패면 기동 실패예요. 매핑은 하루 1회만 바뀌므로 매일 04:30 Asia/Seoul, 즉 크론잡 30분 뒤에 설정값대로 한 번 더 읽어 교체하고, 실패하면 기존 인메모리를 유지한 채 5분 뒤 한 번 더 시도해요. 크론잡을 수동으로 즉시 돌린 경우는 웹 팟 롤링 재시작으로 반영해요. 목표 구조에서는 Redis pub/sub 알림으로 대체할 수 있어요. 공급사 숙소 목록 API는 호출하지 않음 |
| 수동 갱신 | 엔드포인트 없음 | 필요하면 CronJob을 즉시 실행해요. `kubectl create job --from=cronjob/...` |

시각은 한국 시간 03\~05시 사이에 둬요. 국내 숙박 앱 시간대별 검색량은 공개 자료가 없어 일반 웹 트래픽 최저 시간대를 대신 기준으로 잡은 값이며, 운영에서 시간대별 검색량 지표로 조정해요. DB·컨테이너는 UTC를 쓰므로 CronJob 스케줄에 타임존을 명시해요.

갱신 잡 처리 순서. 공급사별로 델타를 계산해 반영해요.
1. 공급사 숙소 목록 API를 공급사별로 병렬 호출해요. 연결 실패·타임아웃·5xx·429는 설정값인 고정 30초 간격 3회로 다시 시도하고, 잘못된 요청·인증·깨진 응답은 재시도하지 않아요. 그래도 실패한 공급사는 건너뛰고 로그를 남기며, 그 공급사의 매핑은 건드리지 않아요.
2. 델타 계산: `fetched` = 이번 목록의 코드 집합, `current` = DB에서 `supplier` 기준 `active=true`인 코드 집합.
   - 추가 = `fetched − current` → 삽입. 같은 코드의 비활성 행이 있으면 새로 만들지 않고 `active=true`로 되돌려요.
   - 사라짐 = `current − fetched` → `active=false`. 행은 삭제하지 않아요. 사라짐이 `current`의 절반, 설정값 `mapping.sync.max-disappear-ratio`를 넘으면 목록이 잘려 온 공급사 쪽 장애로 보고 비활성화만 보류하고 error 로그를 남겨요. 추가·변경은 반영해요.
   - 변경 = 교집합 중 이름·최대 인원이 다른 것 → 갱신.
3. 숙소 델타를 반영한 뒤 객실 타입도 숙소별로 같은 방식으로 반영해요. 기준은 `hotel_id`와 `supplier_room_type_code`예요.
4. 웹 앱 인메모리와 Redis 캐시는 크론잡이 건드리지 않아요. 웹 앱이 매일 04:30에 DB를 다시 읽어 반영하고, 사라진 숙소의 캐시 키는 인메모리에서 빠져 읽히지 않다가 15분 TTL로 사라져요.
5. 공급사별 추가·사라짐·변경 건수와 실패 여부를 로그에 남기고 종료해요.

읽기
- 웹 앱은 `active = true`인 매핑을 인메모리에 올려 두고, 검색은 인메모리에서 공급사별 숙소 코드 묶음을 만들어요.
- 인스턴스가 여러 대여도 갱신 잡은 CronJob 하나만 돌고, 각 인스턴스는 같은 시각에 같은 DB를 읽으므로 값이 같아져요. 인메모리 캐시는 매핑 하나뿐이고, 요금·재고는 `cache` 패키지의 Redis 캐시가 맡아요.

### 3.3 식별자 규칙

- 내부 식별자는 BIGINT 자동 증가 값이에요. 검색 응답에는 공급사 코드가 아니라 이 값이 나가요.
- 같은 공급사 코드는 유니크 키와 upsert로 항상 같은 행에 대응해요. 동시 실행도 유니크 키가 막아요.
- 행은 삭제하지 않아요. 목록에서 사라진 숙소는 `active=false`가 되고, 다시 나타나면 같은 행이 `active=true`로 돌아와요.
- 서로 다른 공급사의 같은 숙소는 별도 식별자를 가져요. 하나로 합치는 것은 중복 상품 병합이라는 선택 항목이에요.

### 3.4 공급사가 판매를 종료했을 때

| 대상 | 처리 |
|---|---|
| 매핑 행 | 유지. `active=false`, 바뀐 시각은 `updated_at` |
| 내부 식별자를 참조하는 우리 데이터, 예를 들어 찜·예약 내역·리뷰 | 유지 |
| 검색 대상 | 제외, `active=true`만 조회 |
| 캐시의 재고·요금 값 | 삭제 |
| 다시 판매를 시작하면 | 갱신 잡의 upsert가 같은 행을 `active=true`로 되돌려요. 식별자와 우리 데이터가 그대로 이어져요 |

## 4. 제약과 주의점

- 매핑 테이블에 요금·재고를 넣지 않아요.
- 매핑 행을 삭제하는 코드를 두지 않아요. 식별자 안정성이 깨져요.
- DB를 초기화하면 식별자가 달라져요. 운영에서는 초기화하지 않아요.
