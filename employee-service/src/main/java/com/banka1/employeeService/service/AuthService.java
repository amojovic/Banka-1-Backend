package com.banka1.employeeService.service;

import com.banka1.employeeService.dto.requests.ActivateDto;
import com.banka1.employeeService.dto.requests.ForgotPasswordDto;
import com.banka1.employeeService.dto.requests.LoginRequestDto;
import com.banka1.employeeService.dto.requests.RefreshTokenRequestDto;
import com.banka1.employeeService.dto.responses.TokenResponseDto;

/**
 * Servis koji upravlja autentifikacijom korisnika.
 * Pokriva prijavu, odjavu, obnavljanje tokena, reset i promenu lozinke,
 * kao i aktivaciju naloga putem jednokratnih confirmacion tokena.
 */
public interface AuthService {

    /**
     * Autentifikuje korisnika i izdaje novi par tokena.
     *
     * @param loginDto podaci za prijavu
     * @return pristupni JWT i refresh token
     */
    TokenResponseDto login(LoginRequestDto loginDto);

    /**
     * Rotira postojeci refresh token i izdaje novi par tokena.
     *
     * @param refreshToken zahtev sa postojecim refresh tokenom
     * @return novi pristupni JWT i refresh token
     */
    TokenResponseDto refreshToken(RefreshTokenRequestDto refreshToken);

    /**
     * Proverava validnost aktivacionog ili reset tokena.
     *
     * @param confirmationToken token dobijen iz korisnickog linka
     * @return identifikator {@code ConfirmationToken} entiteta ako je token validan
     */
    Long check(String confirmationToken);

    /**
     * Aktivira nalog ili menja lozinku u zavisnosti od prosledjenog moda rada.
     *
     * @param activateDto podaci sa identifikatorom potvrde i novom lozinkom
     * @param aktiviraj {@code true} ako se radi o aktivaciji naloga, {@code false} za reset lozinke
     * @return poruka o rezultatu operacije
     */
    String editPassword(ActivateDto activateDto, boolean aktiviraj);

    /**
     * Pokrece postupak zaboravljene lozinke za zadatu email adresu.
     * Generise confirmation token i salje reset link putem RabbitMQ.
     *
     * @param forgotPasswordDto zahtev sa email adresom korisnika
     * @return poruka o rezultatu slanja reset linka
     */
    String forgotPassword(ForgotPasswordDto forgotPasswordDto);

    /**
     * Brise refresh token korisnika (odjava).
     * Ako token ne postoji, operacija se tihо preskace.
     *
     * @param rawRefreshToken nehesirani refresh token koji treba obrisati
     */
    void logout(String rawRefreshToken);

    /**
     * Ponovo salje aktivacioni mejl za nalog koji jos nije aktiviran.
     * Regenerise confirmation token ako vec postoji.
     *
     * @param email email adresa korisnika
     * @return poruka o rezultatu operacije
     */
    String resendActivation(String email);
}
