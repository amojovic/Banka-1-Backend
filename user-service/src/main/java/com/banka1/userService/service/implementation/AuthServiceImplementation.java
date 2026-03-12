package com.banka1.userService.service.implementation;

import com.banka1.userService.domain.ConfirmationToken;
import com.banka1.userService.domain.RefreshToken;
import com.banka1.userService.domain.Zaposlen;
import com.banka1.userService.dto.rabbitmq.EmailDto;
import com.banka1.userService.dto.rabbitmq.EmailType;
import com.banka1.userService.dto.requests.ActivateDto;
import com.banka1.userService.dto.requests.ForgotPasswordDto;
import com.banka1.userService.dto.requests.LoginRequestDto;
import com.banka1.userService.dto.requests.RefreshTokenRequestDto;
import com.banka1.userService.dto.responses.TokenResponseDto;
import com.banka1.userService.exception.BusinessException;
import com.banka1.userService.exception.ErrorCode;
import com.banka1.userService.rabbitMQ.RabbitClient;
import com.banka1.userService.repository.ConfirmationTokenRepository;
import com.banka1.userService.repository.TokenRepository;
import com.banka1.userService.repository.ZaposlenRepository;
import com.banka1.userService.security.JWTService;
import com.banka1.userService.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/**
 * Implementacija {@link AuthService} koja upravlja celokupnim zivotnim ciklusom autentifikacije:
 * prijava, odjava, obnavljanje tokena, aktivacija naloga i reset lozinke.
 * Email notifikacije se salju asinhorno putem RabbitMQ-a tek nakon uspesnog commit-a transakcije.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImplementation implements AuthService {

    /**
     * Repozitorijum za pristup entitetima zaposlenih.
     */
    private final ZaposlenRepository zaposlenRepository;

    /**
     * Enkoder lozinki koji se koristi za verifikaciju i hesiranje.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Servis za generisanje i hesiranje JWT i jednokratnih tokena.
     */
    private final JWTService jwtService;

    /**
     * Repozitorijum za pristup refresh tokenima.
     */
    private final TokenRepository tokenRepository;

    /**
     * Repozitorijum za pristup confirmation tokenima.
     */
    private final ConfirmationTokenRepository confirmationTokenRepository;

    /**
     * Klijent za slanje email notifikacija putem RabbitMQ-a.
     */
    private final RabbitClient rabbitClient;

    /**
     * Bazni URL za reset lozinke (token se dodaje kao query parametar).
     */
    @Value("${url.reset-password}")
    private String urlResetPassword;

    /**
     * Bazni URL za aktivaciju naloga (token se dodaje kao query parametar).
     */
    @Value("${url.activate-account}")
    private String urlActivateAccount;

    /**
     * Trajanje refresh tokena u mesecima.
     */
    @Value("${token.refresh.expiration-time}")
    private Long refreshTokenExpiration;

    /**
     * Trajanje confirmation tokena u minutima.
     */
    @Value("${token.confirmation.expiration-time}")
    private Long confirmationTokenExpiration;

    /**
     * Generise novi pristupni JWT token i rotira refresh token.
     * Pokusava do 3 puta u slucaju kolizije jedinstvene vrednosti tokena.
     *
     * @param zaposlen     korisnik kome se izdaju tokeni
     * @param refreshToken entitet refresh tokena koji se upisuje ili azurira
     * @return odgovor sa JWT i nehesiranim refresh tokenom
     * @throws BusinessException ako generisanje ne uspe ni posle 3 pokusaja
     */
    private TokenResponseDto generate(Zaposlen zaposlen, RefreshToken refreshToken) {
        refreshToken.setExpirationDateTime(LocalDateTime.now().plusMonths(refreshTokenExpiration));
        for (int i = 0; i < 3; i++) {
            String result = jwtService.generateRandomToken();
            refreshToken.setValue(jwtService.sha256Hex(result));
            try {
                tokenRepository.save(refreshToken);
                return new TokenResponseDto(jwtService.generateJwtToken(zaposlen), result, zaposlen.getRole(), zaposlen.getPermissionSet());
            } catch (DataIntegrityViolationException e) {
                // kada se dogodi kolizija tokena, pokusamo ponovo
            }
        }
        throw new BusinessException(ErrorCode.TOKEN_GENERATION_FAILED, "Greska sa generisanjem tokena");
    }

    /**
     * Validira korisnicke kredencijale i izdaje novi par tokena.
     *
     * @param loginDto podaci za prijavu
     * @return odgovor sa pristupnim i refresh tokenom
     * @throws BusinessException ako su kredencijali neispravni ili je nalog neaktivan
     */
    @Transactional
    @Override
    public TokenResponseDto login(LoginRequestDto loginDto) {
        Zaposlen zaposlen = zaposlenRepository.findByEmail(loginDto.getEmail()).orElse(null);
        if (zaposlen == null || !passwordEncoder.matches(loginDto.getPassword(), zaposlen.getPassword()))
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Greska pri loginovanju");
        if (!zaposlen.isAktivan())
            throw new BusinessException(ErrorCode.USER_INACTIVE, "Korisnik nije aktivan");
        return generate(zaposlen, new RefreshToken(zaposlen));
    }

    /**
     * Validira refresh token i vrsi njegovu rotaciju.
     *
     * @param refreshToken zahtev sa nehesiranim refresh tokenom
     * @return odgovor sa novim tokenima
     * @throws BusinessException ako je token nevazeci, istekao ili korisnik obrisan
     */
    @Transactional
    @Override
    public TokenResponseDto refreshToken(RefreshTokenRequestDto refreshToken) {
        RefreshToken refreshTokenCur = tokenRepository.findByValue(jwtService.sha256Hex(refreshToken.getRefreshToken())).orElse(null);
        if (refreshTokenCur == null || refreshTokenCur.getExpirationDateTime().isBefore(LocalDateTime.now()))
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "Pogresan token");
        if (refreshTokenCur.getZaposlen().isDeleted())
            throw new BusinessException(ErrorCode.USER_DELETED, "Korisnik je obrisan");
        return generate(refreshTokenCur.getZaposlen(), refreshTokenCur);
    }

    /**
     * Proverava da li je confirmation token za aktivaciju ili reset lozinke validan.
     *
     * @param confirmationToken token iz korisnickog linka (nehesirani, duzine 43 znaka)
     * @return identifikator confirmation tokena ako je validan
     * @throws BusinessException ako je token nevazeci, istekao ili korisnik obrisan
     */
    @Transactional
    @Override
    public Long check(String confirmationToken) {
        if (confirmationToken == null || confirmationToken.isBlank() || confirmationToken.length() != 43)
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "Pogresan token");
        ConfirmationToken confirmationTokenCur = confirmationTokenRepository.findByValue(jwtService.sha256Hex(confirmationToken)).orElse(null);
        if (confirmationTokenCur == null || confirmationTokenCur.getExpirationDateTime() != null && confirmationTokenCur.getExpirationDateTime().isBefore(LocalDateTime.now()))
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "Pogresan token");
        if (confirmationTokenCur.getZaposlen().isDeleted())
            throw new BusinessException(ErrorCode.USER_DELETED, "Korisnik je obrisan");
        return confirmationTokenCur.getId();
    }

    /**
     * Menja lozinku korisnika, a po potrebi i aktivira nalog.
     * Nakon uspesne operacije confirmation token se brise.
     *
     * @param activateDto podaci sa identifikatorom potvrde, tokenom i novom lozinkom
     * @param aktiviraj   {@code true} za aktivaciju naloga, {@code false} za reset lozinke
     * @return poruka o uspesnom zavrsetku operacije
     * @throws BusinessException ako je token nevazeci ili korisnik obrisan/neaktivan
     */
    @Transactional
    @Override
    public String editPassword(ActivateDto activateDto, boolean aktiviraj) {
        ConfirmationToken confirmationTokenCur = confirmationTokenRepository.findById(activateDto.getId()).orElse(null);
        if (confirmationTokenCur == null || !confirmationTokenCur.getValue().equals(jwtService.sha256Hex(activateDto.getConfirmationToken())) || confirmationTokenCur.getExpirationDateTime() != null && confirmationTokenCur.getExpirationDateTime().isBefore(LocalDateTime.now()))
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "Pogresan token");
        Zaposlen zaposlen = confirmationTokenCur.getZaposlen();
        if (zaposlen.isDeleted())
            throw new BusinessException(ErrorCode.USER_DELETED, "Ne moze se editovati obrisani korisnik");
        if (!(aktiviraj || zaposlen.isAktivan()))
            throw new BusinessException(ErrorCode.USER_INACTIVE, "Korisnik nije aktivan");

        zaposlen.setPassword(passwordEncoder.encode(activateDto.getPassword()));
        zaposlen.setConfirmationToken(null);
        confirmationTokenRepository.delete(confirmationTokenCur);
        if (aktiviraj) {
            zaposlen.setAktivan(true);
            return "Uspesno aktiviranje usera";
        }
        return "Uspesna promena lozinke";
    }

    /**
     * Generise i salje token za reset lozinke na email korisnika.
     * Ako token vec postoji, osvezava njegovu vrednost i rok vazenja.
     * Email se salje putem RabbitMQ-a nakon commit-a transakcije.
     *
     * @param forgotPasswordDto zahtev sa email adresom korisnika
     * @return poruka o rezultatu operacije
     * @throws BusinessException ako korisnik ne postoji ili je neaktivan
     */
    @Transactional
    @Override
    public String forgotPassword(ForgotPasswordDto forgotPasswordDto) {
        Zaposlen zaposlen = zaposlenRepository.findByEmail(forgotPasswordDto.getEmail()).orElse(null);
        if (zaposlen == null)
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "Ne postoji korisnik sa ovim emailom");
        if (!zaposlen.isAktivan())
            throw new BusinessException(ErrorCode.USER_INACTIVE, "Korisnik nije aktivan");

        String generated = jwtService.generateRandomToken();
        if (zaposlen.getConfirmationToken() != null) {
            zaposlen.getConfirmationToken().setValue(jwtService.sha256Hex(generated));
            zaposlen.getConfirmationToken().setExpirationDateTime(LocalDateTime.now().plusMinutes(confirmationTokenExpiration));
        } else {
            ConfirmationToken confirmationToken = new ConfirmationToken(jwtService.sha256Hex(generated), LocalDateTime.now().plusMinutes(15), zaposlen);
            zaposlen.setConfirmationToken(confirmationToken);
            confirmationTokenRepository.save(confirmationToken);
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rabbitClient.sendEmailNotification(new EmailDto(zaposlen.getIme(), zaposlen.getEmail(), EmailType.EMPLOYEE_PASSWORD_RESET, urlResetPassword + generated));
            }
        });
        return "Poslat mejl";
    }

    /**
     * Brise refresh token korisnika (odjava).
     * Ako token ne postoji u bazi, operacija se tihо preskace.
     *
     * @param rawRefreshToken nehesirani refresh token koji treba obrisati
     */
    @Transactional
    @Override
    public void logout(String rawRefreshToken) {
        tokenRepository.findByValue(jwtService.sha256Hex(rawRefreshToken))
                .ifPresent(tokenRepository::delete);
    }

    /**
     * Ponovo salje aktivacioni mejl za nalog koji jos nije aktiviran.
     * Regenerise ili azurira confirmation token i salje mejl putem RabbitMQ-a.
     *
     * @param email email adresa korisnika
     * @return poruka o rezultatu operacije
     * @throws BusinessException ako korisnik ne postoji ili je obrisan
     */
    @Transactional
    @Override
    public String resendActivation(String email) {
        Zaposlen zaposlen = zaposlenRepository.findByEmail(email).orElse(null);
        if (zaposlen == null)
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "Ne postoji korisnik sa ovim emailom");
        if (zaposlen.isDeleted())
            throw new BusinessException(ErrorCode.USER_DELETED, "Korisnik je obrisan");
        if (zaposlen.isAktivan())
            return "Nalog je vec aktivan";

        String generated = jwtService.generateRandomToken();
        if (zaposlen.getConfirmationToken() != null) {
            zaposlen.getConfirmationToken().setValue(jwtService.sha256Hex(generated));
        } else {
            ConfirmationToken confirmationToken = new ConfirmationToken(jwtService.sha256Hex(generated), zaposlen);
            zaposlen.setConfirmationToken(confirmationToken);
            confirmationTokenRepository.save(confirmationToken);
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rabbitClient.sendEmailNotification(new EmailDto(zaposlen.getIme(), zaposlen.getEmail(), EmailType.EMPLOYEE_CREATED, urlActivateAccount + generated));
            }
        });
        return "Poslat mejl";
    }
}
