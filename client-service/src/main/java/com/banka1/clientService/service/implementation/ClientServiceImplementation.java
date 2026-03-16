package com.banka1.clientService.service.implementation;

import com.banka1.clientService.domain.Klijent;
import com.banka1.clientService.dto.requests.ClientCreateRequestDto;
import com.banka1.clientService.dto.requests.ClientUpdateRequestDto;
import com.banka1.clientService.dto.responses.ClientIdResponseDto;
import com.banka1.clientService.dto.responses.ClientResponseDto;
import com.banka1.clientService.exception.BusinessException;
import com.banka1.clientService.exception.ErrorCode;
import com.banka1.clientService.mappers.ClientMapper;
import com.banka1.clientService.repository.KlijentRepository;
import com.banka1.clientService.service.ClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementacija {@link ClientService} koja upravlja CRUD operacijama nad entitetom klijenta.
 * Sve pretrage koriste LIKE escapovanje radi zastite od SQL injection putem metakaraktera.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ClientServiceImplementation implements ClientService {

    /**
     * Repozitorijum za pristup entitetima klijenata.
     */
    private final KlijentRepository klijentRepository;

    /**
     * Mapper za konverziju izmedju DTO i JPA entiteta klijenta.
     */
    private final ClientMapper clientMapper;

    /**
     * Kreira novog klijenta.
     * Proverava jedinstvenost email-a i JMBG-a pre cuvanja.
     *
     * @param dto podaci za kreiranje klijenta
     * @return kreirani klijent mapiran u odgovor
     * @throws BusinessException ako email ili JMBG vec postoje u sistemu
     */
    @Override
    public ClientResponseDto createClient(ClientCreateRequestDto dto) {
        if (klijentRepository.existsByEmail(dto.getEmail()))
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email: " + dto.getEmail());

        if (klijentRepository.existsByJmbg(dto.getJmbg()))
            throw new BusinessException(ErrorCode.JMBG_ALREADY_EXISTS, "JMBG je vec u upotrebi");

        Klijent klijent = clientMapper.toEntity(dto);
        Klijent saved = klijentRepository.save(klijent);
        return clientMapper.toDto(saved);
    }

    /**
     * Pretrazuje klijente po kombinaciji filtera uz paginaciju.
     * Null vrednosti se tretiraju kao wildcard (prazan string); LIKE metakarakteri se eskejpuju.
     *
     * @param ime filter po imenu
     * @param prezime filter po prezimenu
     * @param email filter po email adresi
     * @param pageable parametri paginacije
     * @return stranica klijenata mapirana u DTO objekte
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ClientResponseDto> searchClients(String ime, String prezime, String email, Pageable pageable) {
        String safeIme = (ime != null) ? escapeLike(ime) : "";
        String safePrezime = (prezime != null) ? escapeLike(prezime) : "";
        String safeEmail = (email != null) ? escapeLike(email) : "";

        return klijentRepository.searchClients(safeIme, safePrezime, safeEmail, pageable)
                .map(clientMapper::toDto);
    }

    /**
     * Vrsi globalnu pretragu klijenata jednim tekstualnim upitom.
     * Upit se poredi sa imenom, prezimenom i emailom.
     *
     * @param query tekstualni upit za pretragu
     * @param pageable parametri paginacije
     * @return stranica rezultata mapirana u DTO objekte
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ClientResponseDto> globalSearchClients(String query, Pageable pageable) {
        String safeQuery = (query != null) ? escapeLike(query) : "";
        return klijentRepository.globalSearchClients(safeQuery, pageable)
                .map(clientMapper::toDto);
    }

    /**
     * Azurira podatke klijenta.
     * JMBG i password se ne mogu menjati.
     * Pri promeni emaila proverava jedinstvenost u bazi.
     *
     * @param id identifikator klijenta koji se menja
     * @param dto podaci za azuriranje
     * @return azurirani klijent
     * @throws BusinessException ako klijent nije pronadjen ili novi email vec postoji
     */
    @Override
    public ClientResponseDto updateClient(Long id, ClientUpdateRequestDto dto) {
        Klijent klijent = klijentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND, "ID: " + id));

        // Provera jedinstvenosti emaila ako se menja
        if (dto.getEmail() != null && !dto.getEmail().equals(klijent.getEmail())) {
            if (klijentRepository.existsByEmail(dto.getEmail()))
                throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email: " + dto.getEmail());
            klijent.setEmail(dto.getEmail());
        }

        clientMapper.updateEntityFromDto(klijent, dto);
        return clientMapper.toDto(klijentRepository.save(klijent));
    }

    /**
     * Soft-brise klijenta po identifikatoru.
     *
     * @param id identifikator klijenta koji se brise
     * @throws BusinessException ako klijent nije pronadjen
     */
    @Override
    public void deleteClient(Long id) {
        Klijent klijent = klijentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND, "ID: " + id));
        klijentRepository.delete(klijent);
    }

    /**
     * Vraca ID klijenta na osnovu JMBG-a.
     *
     * @param jmbg JMBG klijenta
     * @return DTO sa ID-em klijenta
     * @throws BusinessException ako klijent sa zadatim JMBG-om nije pronadjen
     */
    @Override
    @Transactional(readOnly = true)
    public ClientIdResponseDto getIdByJmbg(String jmbg) {
        Klijent klijent = klijentRepository.findByJmbg(jmbg)
                .orElseThrow(() -> new BusinessException(ErrorCode.JMBG_NOT_FOUND, "JMBG: [PROTECTED]"));
        return new ClientIdResponseDto(klijent.getId());
    }

    /**
     * Eskejpuje SQL LIKE metakaraktere radi sprecavanja neocekivanih wildcard podudaranja.
     *
     * @param s ulazni string koji se eskejpuje
     * @return eskejpovan string bezbedan za upotrebu u LIKE klauzuli
     */
    private String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
