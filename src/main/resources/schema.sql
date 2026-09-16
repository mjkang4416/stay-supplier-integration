-- 공급사 코드 <-> 내부 식별자 매핑. 요금·재고는 저장하지 않는다.
-- 행은 삭제하지 않고 active 로만 관리한다 (같은 공급사 코드는 항상 같은 내부 식별자).

CREATE TABLE IF NOT EXISTS hotel_mapping (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    supplier            VARCHAR(20)  NOT NULL,
    supplier_hotel_code VARCHAR(100) NOT NULL,
    hotel_name          VARCHAR(255) NOT NULL,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hotel_supplier_code (supplier, supplier_hotel_code)
);

CREATE TABLE IF NOT EXISTS room_type_mapping (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    hotel_id                BIGINT       NOT NULL,
    supplier_room_type_code VARCHAR(100) NOT NULL,
    room_type_name          VARCHAR(255) NOT NULL,
    max_occupancy           INT          NULL,      -- 공급사가 주지 않으면 NULL (미상)
    active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at              DATETIME     NOT NULL,
    updated_at              DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_type_hotel_code (hotel_id, supplier_room_type_code),
    CONSTRAINT fk_room_type_hotel FOREIGN KEY (hotel_id) REFERENCES hotel_mapping (id)
);
