package com.banka1.tradingservice.audit.controller;

import com.banka1.tradingservice.audit.domain.AuditActionType;
import com.banka1.tradingservice.audit.dto.AuditLogDto;
import com.banka1.tradingservice.audit.service.AuditQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-2: WebMvc slice test za {@link AuditLogController} — verifikuje
 * {@code @PreAuthorize} gating, parsiranje filtera i strukturu odgovora.
 */
@WebMvcTest(controllers = AuditLogController.class)
@AutoConfigureMockMvc
@Import({AuditLogController.class, AuditLogControllerWebMvcTest.TestSecurityConfig.class})
@ActiveProfiles("test")
class AuditLogControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditQueryService auditQueryService;

    private Page<AuditLogDto> samplePage() {
        AuditLogDto dto = new AuditLogDto(
                1L, 7L, "Marko", "ORDER_APPROVED", "ORDER", "42", "d",
                LocalDateTime.of(2026, 5, 18, 12, 0));
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        return new PageImpl<>(List.of(dto), pageable, 1);
    }

    @Test
    void returnsPageForAdmin() throws Exception {
        when(auditQueryService.search(isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(samplePage());

        mockMvc.perform(get("/audit")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].actionType").value("ORDER_APPROVED"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void returnsPageForSupervisor() throws Exception {
        when(auditQueryService.search(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(samplePage());

        mockMvc.perform(get("/audit")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))))
                .andExpect(status().isOk());
    }

    @Test
    void forbidsClientRole() throws Exception {
        mockMvc.perform(get("/audit")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))))
                .andExpect(status().isForbidden());

        verify(auditQueryService, never()).search(any(), any(), any(), any(), any());
    }

    @Test
    void forbidsUnauthenticated() throws Exception {
        mockMvc.perform(get("/audit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void parsesActionTypeAndActorIdFilters() throws Exception {
        when(auditQueryService.search(eq(AuditActionType.ORDER_DECLINED), eq(9L),
                isNull(), isNull(), any(Pageable.class)))
                .thenReturn(samplePage());

        mockMvc.perform(get("/audit")
                        .param("actionType", "ORDER_DECLINED")
                        .param("actorId", "9")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        verify(auditQueryService).search(eq(AuditActionType.ORDER_DECLINED), eq(9L),
                isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void rejectsUnknownActionType() throws Exception {
        mockMvc.perform(get("/audit")
                        .param("actionType", "BOGUS")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());

        verify(auditQueryService, never()).search(any(), any(), any(), any(), any());
    }

    @Test
    void parsesBareDateRangeBounds() throws Exception {
        when(auditQueryService.search(isNull(), isNull(),
                eq(LocalDateTime.of(2026, 5, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 5, 31, 23, 59, 59).withNano(999_999_999)),
                any(Pageable.class)))
                .thenReturn(samplePage());

        mockMvc.perform(get("/audit")
                        .param("from", "2026-05-01")
                        .param("to", "2026-05-31")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        verify(auditQueryService).search(isNull(), isNull(),
                eq(LocalDateTime.of(2026, 5, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 5, 31, 23, 59, 59).withNano(999_999_999)),
                any(Pageable.class));
    }

    @Test
    void parsesDateTimeRangeBounds() throws Exception {
        when(auditQueryService.search(isNull(), isNull(),
                eq(LocalDateTime.of(2026, 5, 1, 8, 30, 0)), isNull(), any(Pageable.class)))
                .thenReturn(samplePage());

        mockMvc.perform(get("/audit")
                        .param("from", "2026-05-01T08:30:00")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidDate() throws Exception {
        mockMvc.perform(get("/audit")
                        .param("from", "not-a-date")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration
    @EnableMethodSecurity
    @EnableWebSecurity
    static class TestSecurityConfig {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
                    .build();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "test-user")
                    .build();
        }
    }
}
