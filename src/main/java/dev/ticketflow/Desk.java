package dev.ticketflow;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
class Desk {
    record NewTicket(@NotBlank @Size(max = 48) String orderId, @NotBlank @Size(max = 1200) String description) {}
    enum Decision { APPROVE, REJECT }
    record Review(@NotNull Decision decision, @NotBlank @Size(max = 300) String reason) {}
    record Investigation(String status, String reason, List<Map<String, Object>> citations,
                         List<Map<String, Object>> timeline, String model, String promptVersion,
                         long durationMs, Integer inputTokens, Integer outputTokens, String traceId) {}

    private final Business business;
    private final RestClient agent;

    Desk(Business business, RestClient agent) {
        this.business = business;
        this.agent = agent;
    }

    @GetMapping("/session")
    Map<String, Object> session(Principal user, HttpServletRequest request, CsrfToken token) {
        return Map.of("user", user.getName(), "approver", request.isUserInRole("APPROVER"),
                "csrf", token.getToken(), "csrfHeader", token.getHeaderName());
    }

    @GetMapping("/tickets")
    List<Map<String, Object>> tickets() {
        return business.tickets();
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@Valid @RequestBody NewTicket input) {
        String id = UUID.randomUUID().toString();
        business.create(id, input.orderId().strip(), input.description().strip());
        return run(id);
    }

    @GetMapping("/tickets/{id}")
    Map<String, Object> ticket(@PathVariable String id) {
        return business.ticket(id);
    }

    @PostMapping("/tickets/{id}/run")
    Map<String, Object> run(@PathVariable String id) {
        var ticket = business.ticket(id);
        String status = (String) ticket.get("status");
        if (!List.of("INVESTIGATING", "APPROVED", "DECLINED").contains(status)) {
            return ticket;
        }
        Investigation result;
        try {
            result = agent.post().uri("/runs/{id}", id)
                    .body(Map.of("order_id", ticket.get("order_id"), "description", ticket.get("description"),
                            "decision", status.equals("APPROVED") ? "APPROVE" :
                                    status.equals("DECLINED") ? "REJECT" : ""))
                    .retrieve().body(Investigation.class);
        } catch (RestClientException error) {
            // The ticket remains resumable with its original run ID.
            ticket.put("runError", "Agent 服务暂未返回结果，可以继续运行。");
            return ticket;
        }
        business.updateFromAgent(id, status, result);
        return business.ticket(id);
    }

    @PostMapping("/tickets/{id}/decision")
    Map<String, Object> decision(@PathVariable String id, @Valid @RequestBody Review review, Principal user) {
        business.decide(id, review, user.getName());
        return run(id);
    }

    @GetMapping("/internal/runs/{id}/order")
    Business.OrderFacts order(@PathVariable String id) {
        return business.orderFacts(id);
    }

    @GetMapping("/internal/runs/{id}/entitlement")
    Business.EntitlementFacts entitlement(@PathVariable String id) {
        return business.entitlementFacts(id);
    }

    @PostMapping("/internal/runs/{id}/grant")
    Map<String, Object> grant(@PathVariable String id) {
        return business.grant(id);
    }

    @GetMapping("/internal/runs/{id}/operation")
    Map<String, Object> operation(@PathVariable String id) {
        var result = business.operation(id);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return result;
    }
}
