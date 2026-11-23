package dev.ticketflow;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class Business {
    static final String BENEFIT = "REPORT_EXPORT";
    static final Duration SYNC_WINDOW = Duration.ofMinutes(15);

    record OrderFacts(String orderId, boolean found, String plan, String payment, Instant paidAt,
                      int syncWindowMinutes) {}
    record EntitlementFacts(String orderId, String benefit, boolean granted, String eligibility) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;

    Business(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    void create(String id, String order, String description) {
        db.update("""
                INSERT INTO tickets (id, order_id, description, status)
                VALUES (?, ?, ?, 'INVESTIGATING')
                """, id, order, description);
    }

    List<Map<String, Object>> tickets() {
        return db.queryForList("""
                SELECT id, order_id, description, status, created_at FROM tickets
                ORDER BY created_at DESC LIMIT 100
                """);
    }

    Map<String, Object> ticket(String id) {
        var rows = db.queryForList("""
                SELECT id, order_id, description, status, investigation, decision, decided_by,
                       decision_reason, created_at FROM tickets WHERE id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }
        var ticket = rows.getFirst();
        if (ticket.get("investigation") instanceof String raw) {
            try {
                ticket.put("investigation", json.readTree(raw));
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Could not read persisted investigation", error);
            }
        }
        ticket.put("operation", operation(id));
        return ticket;
    }

    void updateFromAgent(String id, String previous, Desk.Investigation result) {
        try {
            db.update("UPDATE tickets SET status = ?, investigation = ? WHERE id = ? AND status = ?",
                    result.status(), json.writeValueAsString(result), id, previous);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not store investigation", error);
        }
    }

    void decide(String id, Desk.Review review, String user) {
        String next = review.decision() == Desk.Decision.APPROVE ? "APPROVED" : "DECLINED";
        if (db.update("""
                UPDATE tickets SET status = ?, decision = ?, decided_by = ?, decision_reason = ?
                WHERE id = ? AND status = 'AWAITING_APPROVAL'
                """, next, review.decision().name(), user, review.reason(), id) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该工单当前无法审批");
        }
    }

    OrderFacts orderFacts(String runId) {
        var ids = db.queryForList("SELECT order_id FROM tickets WHERE id = ?", String.class, runId);
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }
        return readOrder(ids.getFirst(), false);
    }

    EntitlementFacts entitlementFacts(String runId) {
        OrderFacts order = orderFacts(runId);
        boolean granted = hasBenefit(order.orderId());
        return new EntitlementFacts(order.orderId(), BENEFIT, granted, eligibility(order, granted));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    Map<String, Object> grant(String id) {
        var locked = db.queryForList("""
                SELECT order_id, decision FROM tickets WHERE id = ? FOR UPDATE
                """, id);
        if (locked.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var ticket = locked.getFirst();
        var existing = operation(id);
        if (existing != null) {
            return existing;
        }
        if (!"APPROVE".equals(ticket.get("decision"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "缺少此工单的补发批准");
        }
        String orderId = (String) ticket.get("order_id");
        // All grants for this order take the same lock before reading its entitlement.
        OrderFacts order = readOrder(orderId, true);
        String reason = eligibility(order, hasBenefit(orderId));
        String outcome = switch (reason) {
            case "MISSING" -> "GRANTED";
            case "ALREADY_GRANTED" -> "ALREADY_GRANTED";
            default -> "BLOCKED";
        };
        if (outcome.equals("GRANTED")) {
            db.update("INSERT INTO entitlements (order_id, benefit) VALUES (?, ?)", orderId, BENEFIT);
        }
        db.update("INSERT INTO operations (run_id, order_id, benefit, outcome, reason) VALUES (?, ?, ?, ?, ?)",
                id, orderId, BENEFIT, outcome, reason);
        return operation(id);
    }

    Map<String, Object> operation(String id) {
        var rows = db.queryForList("SELECT * FROM operations WHERE run_id = ?", id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private OrderFacts readOrder(String orderId, boolean lock) {
        var rows = db.query("""
                SELECT plan_code, payment_status, paid_at FROM orders WHERE id = ?
                """ + (lock ? " FOR UPDATE" : ""), (row, index) -> {
            Timestamp paidAt = row.getTimestamp("paid_at");
            return new OrderFacts(orderId, true, row.getString("plan_code"), row.getString("payment_status"),
                    paidAt == null ? null : paidAt.toInstant(), (int) SYNC_WINDOW.toMinutes());
        }, orderId);
        return rows.isEmpty() ? new OrderFacts(orderId, false, null, null, null, (int) SYNC_WINDOW.toMinutes())
                : rows.getFirst();
    }

    private boolean hasBenefit(String orderId) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM entitlements WHERE order_id = ? AND benefit = ?)
                """, Boolean.class, orderId, BENEFIT));
    }

    private String eligibility(OrderFacts order, boolean granted) {
        if (!order.found()) return "ORDER_UNKNOWN";
        if (!"PAID".equals(order.payment()) || order.paidAt() == null) return "UNPAID";
        if (!"PRO".equals(order.plan())) return "PLAN_EXCLUDED";
        if (granted) return "ALREADY_GRANTED";
        if (order.paidAt().plus(SYNC_WINDOW).isAfter(Instant.now())) return "SYNC_PENDING";
        return "MISSING";
    }
}
