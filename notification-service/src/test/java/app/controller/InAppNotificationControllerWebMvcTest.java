package app.controller;

import app.dto.InAppNotificationDto;
import app.entities.RecipientType;
import app.service.InAppNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link InAppNotificationController}.
 *
 * <p>The tests protect the JWT-driven scoping contract: the caller id comes
 * from the {@code id} claim and the id space (client vs employee) is derived
 * from the {@code roles} claim. The {@link InAppNotificationService} is mocked,
 * so the assertions focus on identity extraction, HTTP status mapping, and
 * paging-parameter clamping.
 */
@WebMvcTest(InAppNotificationController.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class InAppNotificationControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InAppNotificationService service;

    private static InAppNotificationDto sampleDto() {
        return new InAppNotificationDto(
                7L, "TRANSACTION_COMPLETED", "Transakcija",
                "Vasa transakcija je izvrsena.", false, "tx-1", Instant.now());
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listScopesToClientCallerFromIdAndRolesClaims() throws Exception {
        Page<InAppNotificationDto> page = new PageImpl<>(
                List.of(sampleDto()), PageRequest.of(0, 20), 1);
        when(service.getNotifications(eq(42L), eq(RecipientType.CLIENT), eq(0), eq(20)))
                .thenReturn(page);

        mockMvc.perform(get("/notifications")
                        .with(jwt().jwt(j -> j.claim("id", 42)
                                .claim("roles", List.of("CLIENT_TRADING")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].type").value("TRANSACTION_COMPLETED"));

        verify(service).getNotifications(42L, RecipientType.CLIENT, 0, 20);
    }

    @Test
    void listScopesToEmployeeCallerForNonClientRole() throws Exception {
        when(service.getNotifications(eq(9L), eq(RecipientType.EMPLOYEE), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/notifications")
                        .with(jwt().jwt(j -> j.claim("id", 9)
                                .claim("roles", List.of("ADMIN")))))
                .andExpect(status().isOk());

        verify(service).getNotifications(9L, RecipientType.EMPLOYEE, 0, 20);
    }

    @Test
    void listClampsPagingParameters() throws Exception {
        when(service.getNotifications(eq(1L), eq(RecipientType.EMPLOYEE), eq(0), eq(100)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        mockMvc.perform(get("/notifications")
                        .param("page", "-5")
                        .param("size", "5000")
                        .with(jwt().jwt(j -> j.claim("id", 1)
                                .claim("roles", List.of("BASIC")))))
                .andExpect(status().isOk());

        verify(service).getNotifications(1L, RecipientType.EMPLOYEE, 0, 100);
    }

    @Test
    void listFallsBackToSubjectWhenIdClaimMissing() throws Exception {
        when(service.getNotifications(eq(55L), eq(RecipientType.EMPLOYEE), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/notifications")
                        .with(jwt().jwt(j -> j.subject("55")
                                .claim("roles", List.of("SUPERVISOR")))))
                .andExpect(status().isOk());

        verify(service).getNotifications(55L, RecipientType.EMPLOYEE, 0, 20);
    }

    @Test
    void unreadCountReturnsCountObject() throws Exception {
        when(service.unreadCount(42L, RecipientType.CLIENT)).thenReturn(3L);

        mockMvc.perform(get("/notifications/unread-count")
                        .with(jwt().jwt(j -> j.claim("id", 42)
                                .claim("roles", List.of("CLIENT_BASIC")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void markReadReturnsNoContentWhenOwned() throws Exception {
        when(service.markRead(7L, 42L, RecipientType.CLIENT)).thenReturn(true);

        mockMvc.perform(patch("/notifications/7/read")
                        .with(jwt().jwt(j -> j.claim("id", 42)
                                .claim("roles", List.of("CLIENT_BASIC")))))
                .andExpect(status().isNoContent());

        verify(service).markRead(7L, 42L, RecipientType.CLIENT);
    }

    @Test
    void markReadReturnsNotFoundWhenNotOwned() throws Exception {
        when(service.markRead(7L, 42L, RecipientType.CLIENT)).thenReturn(false);

        mockMvc.perform(patch("/notifications/7/read")
                        .with(jwt().jwt(j -> j.claim("id", 42)
                                .claim("roles", List.of("CLIENT_BASIC")))))
                .andExpect(status().isNotFound());
    }

    @Test
    void markReadRequiresAuthentication() throws Exception {
        mockMvc.perform(patch("/notifications/7/read"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).markRead(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void markAllReadReturnsNoContent() throws Exception {
        when(service.markAllRead(42L, RecipientType.CLIENT)).thenReturn(4);

        mockMvc.perform(patch("/notifications/read-all")
                        .with(jwt().jwt(j -> j.claim("id", 42)
                                .claim("roles", List.of("CLIENT_TRADING")))))
                .andExpect(status().isNoContent());

        verify(service).markAllRead(42L, RecipientType.CLIENT);
    }
}
