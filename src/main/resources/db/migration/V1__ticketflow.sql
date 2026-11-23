CREATE TABLE orders (
    id VARCHAR(48) PRIMARY KEY,
    plan_code VARCHAR(32) NOT NULL,
    payment_status VARCHAR(16) NOT NULL,
    paid_at TIMESTAMP(6)
);
CREATE TABLE entitlements (
    order_id VARCHAR(48) NOT NULL,
    benefit VARCHAR(32) NOT NULL,
    granted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (order_id, benefit),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE TABLE tickets (
    id CHAR(36) PRIMARY KEY,
    order_id VARCHAR(48) NOT NULL,
    description VARCHAR(1200) NOT NULL,
    status VARCHAR(24) NOT NULL,
    investigation JSON,
    decision VARCHAR(8),
    decided_by VARCHAR(80),
    decision_reason VARCHAR(300),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE operations (
    run_id CHAR(36) PRIMARY KEY,
    order_id VARCHAR(48) NOT NULL,
    benefit VARCHAR(32) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (run_id) REFERENCES tickets(id)
);
