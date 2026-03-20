package com.banka1.employeeService.configuration;

import com.banka1.employeeService.domain.enums.Permission;
import com.banka1.employeeService.domain.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Konfiguracione vrednosti aplikacije ucitane iz {@code application.yaml} pod prefiksom {@code employees}.
 * Sadrzi mapu kojom se svakoj roli dodeljuje skup dozvoljenih permisija.
 */
@ConfigurationProperties(prefix = "employees")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class AppProperties {

    /**
     * Mapa koja svakoj roli ({@link Role}) pridruzuje listu dozvoljenih permisija ({@link Permission}).
     */
    private Map<Role, List<Permission>> permissions;
}
