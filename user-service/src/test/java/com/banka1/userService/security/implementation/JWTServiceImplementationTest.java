package com.banka1.userService.security.implementation;

import com.banka1.userService.domain.Zaposlen;
import com.banka1.userService.domain.enums.Permission;
import com.banka1.userService.domain.enums.Pol;
import com.banka1.userService.domain.enums.Role;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.text.ParseException;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JWTServiceImplementationTest {

    private JWTServiceImplementation jwtService;

    @BeforeEach
    void setUp() throws KeyLengthException {
        jwtService = new JWTServiceImplementation("OvoJeNekaDugackaTajnaSifraZaJwtKojuNeStavljamoUProperties");
        ReflectionTestUtils.setField(jwtService, "role", "roles");
        ReflectionTestUtils.setField(jwtService, "permission", "permissions");
        ReflectionTestUtils.setField(jwtService, "id", "id");
        ReflectionTestUtils.setField(jwtService, "issuer", "banka1");
        ReflectionTestUtils.setField(jwtService, "expirationTime", 3_600_000L);
    }

    @Test
    void generateRandomTokenReturns43CharUrlSafeString() {
        String token = jwtService.generateRandomToken();
        assertThat(token).hasSize(43);
        assertThat(token).doesNotContain("+", "/", "=");
    }

    @Test
    void generateRandomTokenProducesDifferentValuesEachTime() {
        String first = jwtService.generateRandomToken();
        String second = jwtService.generateRandomToken();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void sha256HexReturnsDeterministicHash() {
        String hash1 = jwtService.sha256Hex("hello");
        String hash2 = jwtService.sha256Hex("hello");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
        assertThat(hash1).matches("[0-9a-f]+");
    }

    @Test
    void sha256HexProducesDifferentHashesForDifferentInputs() {
        String hash1 = jwtService.sha256Hex("hello");
        String hash2 = jwtService.sha256Hex("world");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void generateJwtTokenProducesValidSignedJwt() throws ParseException {
        Zaposlen employee = employee();

        String token = jwtService.generateJwtToken(employee);

        assertThat(token).isNotBlank();
        assertThatCode(() -> SignedJWT.parse(token)).doesNotThrowAnyException();

        SignedJWT parsed = SignedJWT.parse(token);
        assertThat(parsed.getJWTClaimsSet().getSubject()).isEqualTo("pera@banka.com");
        assertThat(parsed.getJWTClaimsSet().getIssuer()).isEqualTo("banka1");
        assertThat(parsed.getJWTClaimsSet().getClaim("roles")).isEqualTo("AGENT");
        assertThat(parsed.getJWTClaimsSet().getLongClaim("id")).isEqualTo(1L);
    }

    @Test
    void generateJwtTokenIncludesPermissions() throws ParseException {
        Zaposlen employee = employee();
        employee.setPermissionSet(Set.of(Permission.BANKING_BASIC, Permission.CLIENT_MANAGE));

        String token = jwtService.generateJwtToken(employee);
        SignedJWT parsed = SignedJWT.parse(token);

        assertThat(parsed.getJWTClaimsSet().getStringListClaim("permissions"))
                .containsExactlyInAnyOrder("BANKING_BASIC", "CLIENT_MANAGE");
    }

    @Test
    void generateJwtTokenWithEmptyPermissionSetHasEmptyPermissions() throws ParseException {
        Zaposlen employee = employee();
        employee.setPermissionSet(Set.of());

        String token = jwtService.generateJwtToken(employee);
        SignedJWT parsed = SignedJWT.parse(token);

        assertThat(parsed.getJWTClaimsSet().getStringListClaim("permissions")).isEmpty();
    }

    @Test
    void generateJwtTokenHasExpirationInFuture() throws ParseException {
        Zaposlen employee = employee();
        String token = jwtService.generateJwtToken(employee);
        SignedJWT parsed = SignedJWT.parse(token);

        assertThat(parsed.getJWTClaimsSet().getExpirationTime())
                .isAfter(new java.util.Date());
    }

    @Test
    void sha256HexProducesConsistentHashForSameInput() {
        // Verify that the same input always produces the same 64-char hex output
        String hash1 = jwtService.sha256Hex("abc");
        String hash2 = jwtService.sha256Hex("abc");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
        assertThat(hash1).matches("[0-9a-f]+");
    }

    @Test
    void generateRandomTokensAreAlwaysUrlSafe() {
        for (int i = 0; i < 20; i++) {
            String token = jwtService.generateRandomToken();
            assertThat(token).hasSize(43);
            assertThat(token).doesNotContain("+", "/", "=");
        }
    }

    private Zaposlen employee() {
        Zaposlen emp = new Zaposlen();
        emp.setId(1L);
        emp.setIme("Pera");
        emp.setPrezime("Peric");
        emp.setEmail("pera@banka.com");
        emp.setUsername("pera");
        emp.setDatumRodjenja(LocalDate.of(1990, 1, 1));
        emp.setPol(Pol.M);
        emp.setPozicija("Agent");
        emp.setDepartman("Prodaja");
        emp.setAktivan(true);
        emp.setRole(Role.AGENT);
        return emp;
    }
}
