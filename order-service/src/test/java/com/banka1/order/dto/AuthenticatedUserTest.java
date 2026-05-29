package com.banka1.order.dto;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AuthenticatedUser record.
 * Verifies role checking, permission checking, and user classification logic.
 */
class AuthenticatedUserTest {

    @Test
    void hasRole_returnsTrueForExistingRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("CLIENT_TRADING", "AGENT"), Set.of());

        assertThat(user.hasRole("CLIENT_TRADING")).isTrue();
        assertThat(user.hasRole("AGENT")).isTrue();
    }

    @Test
    void hasRole_returnsFalseForMissingRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("CLIENT_TRADING"), Set.of());

        assertThat(user.hasRole("SUPERVISOR")).isFalse();
        assertThat(user.hasRole("ADMIN")).isFalse();
    }

    @Test
    void hasRole_isCaseInsensitive() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("CLIENT_TRADING"), Set.of());

        assertThat(user.hasRole("client_trading")).isTrue();
        assertThat(user.hasRole("CLIENT_trading")).isTrue();
        assertThat(user.hasRole("Client_Trading")).isTrue();
    }

    @Test
    void hasRole_withEmptyRoles() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of(), Set.of());

        assertThat(user.hasRole("AGENT")).isFalse();
    }

    @Test
    void hasMarginPermission_returnsTrueWhenMarginPermissionExists() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of(), Set.of("MARGIN_TRADE", "SECURITIES_TRADE"));

        assertThat(user.hasMarginPermission()).isTrue();
    }

    @Test
    void hasMarginPermission_returnsTrueForVariantNames() {
        AuthenticatedUser user1 = new AuthenticatedUser(1L, Set.of(), Set.of("MARGIN_TRADE"));
        AuthenticatedUser user2 = new AuthenticatedUser(2L, Set.of(), Set.of("SECURITIES_TRADE_MARGIN"));
        AuthenticatedUser user3 = new AuthenticatedUser(3L, Set.of(), Set.of("MARGIN"));

        assertThat(user1.hasMarginPermission()).isTrue();
        assertThat(user2.hasMarginPermission()).isTrue();
        assertThat(user3.hasMarginPermission()).isTrue();
    }

    @Test
    void hasMarginPermission_returnsFalseWhenNoMarginPermission() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of(), Set.of("SECURITIES_TRADE", "view:portfolio"));

        assertThat(user.hasMarginPermission()).isFalse();
    }

    @Test
    void hasMarginPermission_withEmptyPermissions() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of(), Set.of());

        assertThat(user.hasMarginPermission()).isFalse();
    }

    @Test
    void hasTradingPermission_returnsTrueForClientTradingRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("CLIENT_TRADING"), Set.of());

        assertThat(user.hasTradingPermission()).isTrue();
    }

    @Test
    void hasTradingPermission_returnsTrueForTradingPermission() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of(), Set.of("SECURITIES_TRADE"));

        assertThat(user.hasTradingPermission()).isTrue();
    }

    @Test
    void hasTradingPermission_returnsTrueForBothRoleAndPermission() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("CLIENT_TRADING"), Set.of("TRADING_ADVANCED"));

        assertThat(user.hasTradingPermission()).isTrue();
    }

    @Test
    void hasTradingPermission_returnsFalseWhenNeitherRoleNorPermission() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("CLIENT_BASIC"), Set.of("view:portfolio"));

        assertThat(user.hasTradingPermission()).isFalse();
    }

    @Test
    void isClient_returnsTrueForClientBasicRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("CLIENT_BASIC"), Set.of());

        assertThat(user.isClient()).isTrue();
    }

    @Test
    void isClient_returnsTrueForClientTradingRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("CLIENT_TRADING"), Set.of());

        assertThat(user.isClient()).isTrue();
    }

    @Test
    void isClient_returnsTrueForClientRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("CLIENT"), Set.of());

        assertThat(user.isClient()).isTrue();
    }

    @Test
    void isClient_returnsFalseForAgentRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("AGENT"), Set.of());

        assertThat(user.isClient()).isFalse();
    }

    @Test
    void isClient_returnsFalseForSupervisorRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("SUPERVISOR"), Set.of());

        assertThat(user.isClient()).isFalse();
    }

    @Test
    void isAgent_returnsTrueForAgentRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("AGENT"), Set.of());

        assertThat(user.isAgent()).isTrue();
    }

    @Test
    void isAgent_returnsTrueForSupervisorRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("SUPERVISOR"), Set.of());

        assertThat(user.isAgent()).isTrue();
    }

    @Test
    void isAgent_returnsTrueForAdminRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("ADMIN"), Set.of());

        assertThat(user.isAgent()).isTrue();
    }

    @Test
    void isAgent_returnsFalseForClientRole() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of("CLIENT_TRADING"), Set.of());

        assertThat(user.isAgent()).isFalse();
    }

    @Test
    void isAgent_returnsFalseForEmptyRoles() {
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of(), Set.of());

        assertThat(user.isAgent()).isFalse();
    }

    @Test
    void multipleRoles_allChecksWork() {
        AuthenticatedUser user = new AuthenticatedUser(
                1L,
                Set.of("CLIENT_TRADING", "AGENT"),
                Set.of("SECURITIES_TRADE", "MARGIN_TRADE")
        );

        assertThat(user.hasRole("CLIENT_TRADING")).isTrue();
        assertThat(user.hasRole("AGENT")).isTrue();
        assertThat(user.hasMarginPermission()).isTrue();
        assertThat(user.hasTradingPermission()).isTrue();
        assertThat(user.isClient()).isTrue();
        assertThat(user.isAgent()).isTrue();
    }

    @Test
    void userId_isStored() {
        AuthenticatedUser user = new AuthenticatedUser(42L, Set.of("CLIENT_TRADING"), Set.of());

        assertThat(user.userId()).isEqualTo(42L);
    }

    @Test
    void roles_isImmutable() {
        Set<String> originalRoles = Set.of("CLIENT_TRADING");
        AuthenticatedUser user = new AuthenticatedUser(1L, originalRoles, Set.of());

        assertThat(user.roles()).isEqualTo(originalRoles);
        assertThat(user.roles()).isUnmodifiable();
    }

    @Test
    void permissions_isImmutable() {
        Set<String> originalPermissions = Set.of("trading:execute");
        AuthenticatedUser user = new AuthenticatedUser(1L, Set.of(), originalPermissions);

        assertThat(user.permissions()).isEqualTo(originalPermissions);
        assertThat(user.permissions()).isUnmodifiable();
    }
}

