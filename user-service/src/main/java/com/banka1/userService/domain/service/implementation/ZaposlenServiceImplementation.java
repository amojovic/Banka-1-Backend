package com.banka1.userService.domain.service.implementation;

import com.banka1.userService.configuration.AppProperties;
import com.banka1.userService.domain.Zaposlen;
import com.banka1.userService.domain.enums.Role;
import com.banka1.userService.domain.service.ZaposlenService;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;

/**
 * Implementacija {@link ZaposlenService} koja koristi konfiguraciju iz {@link AppProperties}
 * za dinamicko dodeljivanje permisija zaposlenima.
 */
@Service
@AllArgsConstructor
@Getter
@Setter
public class ZaposlenServiceImplementation implements ZaposlenService {

    /** Konfiguracione vrednosti aplikacije, ukljucujuci mapiranje uloga na permisije. */
    private AppProperties appProperties;

    /**
     * Postavlja permisije zaposlenog akumulacijom svih permisija za uloge
     * ciji je nivo snage manji ili jednak nivou uloge zaposlenog.
     *
     * @param zaposlen zaposleni kome se postavljaju permisije
     */
    @Override
    public void setovanjePermisija(Zaposlen zaposlen) {
        for (Role r : Role.values()) {
            if (r.getPower() <= zaposlen.getRole().getPower()) {
                zaposlen.getPermissionSet().addAll(appProperties.getPermissions().get(r));
            }
        }
    }
}
