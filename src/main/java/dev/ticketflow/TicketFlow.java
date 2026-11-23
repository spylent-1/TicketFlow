package dev.ticketflow;

import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.time.Duration;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class TicketFlow {
    public static void main(String[] args) {
        SpringApplication.run(TicketFlow.class, args);
    }

    @Bean
    InMemoryUserDetailsManager users(
            @Value("${ticketflow.support-password}") String support,
            @Value("${ticketflow.approver-password}") String approver) {
        return new InMemoryUserDetailsManager(
                User.withUsername("support").password("{noop}" + support).roles("SUPPORT").build(),
                User.withUsername("approver").password("{noop}" + approver).roles("APPROVER").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, @Value("${ticketflow.internal-key}") String key)
            throws Exception {
        byte[] expected = key.getBytes(StandardCharsets.UTF_8);
        return http.csrf(csrf -> csrf.ignoringRequestMatchers("/internal/**"))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/internal/**").access((user, context) -> {
                            String supplied = context.getRequest().getHeader("X-Internal-Key");
                            return new AuthorizationDecision(supplied != null && MessageDigest.isEqual(
                                    expected, supplied.getBytes(StandardCharsets.UTF_8)));
                        })
                        .requestMatchers("/tickets/*/decision").hasRole("APPROVER")
                        .requestMatchers(HttpMethod.POST, "/tickets").hasRole("SUPPORT")
                        .anyRequest().authenticated())
                .formLogin(login -> login.defaultSuccessUrl("/", true).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login"))
                .build();
    }

    @Bean
    RestClient agent(@Value("${ticketflow.agent-url}") String url, @Value("${ticketflow.internal-key}") String key) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(75));
        return RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-Internal-Key", key).build();
    }
}
