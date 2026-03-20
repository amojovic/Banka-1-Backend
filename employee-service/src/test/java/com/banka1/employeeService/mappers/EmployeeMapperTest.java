package com.banka1.employeeService.mappers;

import com.banka1.employeeService.domain.Zaposlen;
import com.banka1.employeeService.domain.enums.Pol;
import com.banka1.employeeService.domain.enums.Role;
import com.banka1.employeeService.domain.service.ZaposlenService;
import com.banka1.employeeService.dto.requests.EmployeeCreateRequestDto;
import com.banka1.employeeService.dto.requests.EmployeeEditRequestDto;
import com.banka1.employeeService.dto.requests.EmployeeUpdateRequestDto;
import com.banka1.employeeService.dto.responses.EmployeeResponseDto;
import com.banka1.employeeService.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmployeeMapperTest {

    @Mock
    private ZaposlenService zaposlenService;

    private EmployeeMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new EmployeeMapper(zaposlenService);
    }

    @Test
    void toEntityMapsAllFieldsAndCallsPermissionService() {
        EmployeeCreateRequestDto dto = new EmployeeCreateRequestDto();
        dto.setIme("Nikola");
        dto.setPrezime("Nikolic");
        dto.setDatumRodjenja(LocalDate.of(1995, 5, 5));
        dto.setPol(Pol.M);
        dto.setEmail("nikola@banka.com");
        dto.setBrojTelefona("+381601234567");
        dto.setAdresa("Ulica 1");
        dto.setUsername("nikola");
        dto.setPozicija("Agent");
        dto.setDepartman("Prodaja");
        dto.setRole(Role.BASIC);

        Zaposlen result = mapper.toEntity(dto);

        assertThat(result.getIme()).isEqualTo("Nikola");
        assertThat(result.getPrezime()).isEqualTo("Nikolic");
        assertThat(result.getEmail()).isEqualTo("nikola@banka.com");
        assertThat(result.getUsername()).isEqualTo("nikola");
        assertThat(result.getBrojTelefona()).isEqualTo("+381601234567");
        assertThat(result.getAdresa()).isEqualTo("Ulica 1");
        assertThat(result.getPozicija()).isEqualTo("Agent");
        assertThat(result.getDepartman()).isEqualTo("Prodaja");
        assertThat(result.getRole()).isEqualTo(Role.BASIC);
        verify(zaposlenService).setovanjePermisija(result);
    }

    @Test
    void toDtoMapsAllResponseFields() {
        Zaposlen emp = employee();

        EmployeeResponseDto result = mapper.toDto(emp);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getIme()).isEqualTo("Ana");
        assertThat(result.getPrezime()).isEqualTo("Anic");
        assertThat(result.getEmail()).isEqualTo("ana@banka.com");
        assertThat(result.getUsername()).isEqualTo("ana");
        assertThat(result.getPozicija()).isEqualTo("Broker");
        assertThat(result.getDepartman()).isEqualTo("Prodaja");
        assertThat(result.isAktivan()).isTrue();
        assertThat(result.getRole()).isEqualTo(Role.AGENT);
    }

    @Test
    void updateEntityFromDtoAppliesOnlyNonNullFields() {
        Zaposlen emp = employee();
        EmployeeUpdateRequestDto dto = new EmployeeUpdateRequestDto();
        dto.setDepartman("IT");

        mapper.updateEntityFromDto(emp, dto, Role.ADMIN);

        assertThat(emp.getDepartman()).isEqualTo("IT");
        assertThat(emp.getIme()).isEqualTo("Ana");   // unchanged
        assertThat(emp.getPozicija()).isEqualTo("Broker"); // unchanged
    }

    @Test
    void updateEntityFromDtoUpdatesAktivan() {
        Zaposlen emp = employee();
        EmployeeUpdateRequestDto dto = new EmployeeUpdateRequestDto();
        dto.setAktivan(false);

        mapper.updateEntityFromDto(emp, dto, Role.ADMIN);

        assertThat(emp.isAktivan()).isFalse();
    }

    @Test
    void updateEntityFromDtoThrowsWhenRoleTooStrong() {
        Zaposlen emp = employee();
        EmployeeUpdateRequestDto dto = new EmployeeUpdateRequestDto();
        dto.setRole(Role.ADMIN);

        assertThatThrownBy(() -> mapper.updateEntityFromDto(emp, dto, Role.AGENT))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Ne mozes da mu das jacu rolu od svoje");
    }

    @Test
    void updateEntityFromDtoAllowsRoleDowngrade() {
        Zaposlen emp = employee();
        EmployeeUpdateRequestDto dto = new EmployeeUpdateRequestDto();
        dto.setRole(Role.BASIC);

        mapper.updateEntityFromDto(emp, dto, Role.ADMIN);

        assertThat(emp.getRole()).isEqualTo(Role.BASIC);
    }

    @Test
    void updateEntityFromEditDtoAppliesOnlyNonNullFields() {
        Zaposlen emp = employee();
        EmployeeEditRequestDto dto = new EmployeeEditRequestDto("Novak", null, "0601234567", null, null, null);

        mapper.updateEntityFromDto(emp, dto);

        assertThat(emp.getIme()).isEqualTo("Novak");
        assertThat(emp.getBrojTelefona()).isEqualTo("0601234567");
        assertThat(emp.getPrezime()).isEqualTo("Anic");   // unchanged
        assertThat(emp.getPozicija()).isEqualTo("Broker"); // unchanged
    }

    @Test
    void updateEntityFromEditDtoDoesNotChangeRoleOrStatus() {
        Zaposlen emp = employee();
        EmployeeEditRequestDto dto = new EmployeeEditRequestDto("Novak", "Novakovic", null, null, "Senior", "IT");

        mapper.updateEntityFromDto(emp, dto);

        assertThat(emp.getRole()).isEqualTo(Role.AGENT);
        assertThat(emp.isAktivan()).isTrue();
    }

    @Test
    void updateEntityFromDtoThrowsWhenRoleEqualToCaller() {
        Zaposlen emp = employee(); // role = AGENT (power=2)
        EmployeeUpdateRequestDto dto = new EmployeeUpdateRequestDto();
        dto.setRole(Role.ADMIN); // power=4 > AGENT power=2, should throw

        assertThatThrownBy(() -> mapper.updateEntityFromDto(emp, dto, Role.AGENT))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Ne mozes da mu das jacu rolu od svoje");
    }

    @Test
    void updateEntityFromDtoAllowsSameOrLowerRole() {
        Zaposlen emp = employee(); // role = AGENT (power=2)
        EmployeeUpdateRequestDto dto = new EmployeeUpdateRequestDto();
        dto.setRole(Role.AGENT); // power=2 == AGENT power=2, should NOT throw (only > throws)

        // Should not throw — same power is allowed
        mapper.updateEntityFromDto(emp, dto, Role.AGENT);
        assertThat(emp.getRole()).isEqualTo(Role.AGENT);
    }

    @Test
    void toEntityMapsDateAndPol() {
        EmployeeCreateRequestDto dto = new EmployeeCreateRequestDto();
        dto.setIme("Petar");
        dto.setPrezime("Petrovic");
        dto.setDatumRodjenja(LocalDate.of(1988, 3, 15));
        dto.setPol(Pol.Z);
        dto.setEmail("petar@banka.com");
        dto.setUsername("petar");
        dto.setPozicija("Supervisor");
        dto.setDepartman("Risk");
        dto.setRole(Role.SUPERVISOR);

        Zaposlen result = mapper.toEntity(dto);

        assertThat(result.getDatumRodjenja()).isEqualTo(LocalDate.of(1988, 3, 15));
        assertThat(result.getPol()).isEqualTo(Pol.Z);
        assertThat(result.getRole()).isEqualTo(Role.SUPERVISOR);
    }

    @Test
    void toEntityHandlesNullOptionalFields() {
        EmployeeCreateRequestDto dto = new EmployeeCreateRequestDto();
        dto.setIme("Jovan");
        dto.setPrezime("Jovic");
        dto.setDatumRodjenja(LocalDate.of(1992, 6, 10));
        dto.setPol(Pol.M);
        dto.setEmail("jovan@banka.com");
        dto.setUsername("jovan");
        dto.setPozicija("Agent");
        dto.setDepartman("Prodaja");
        dto.setRole(Role.BASIC);
        dto.setBrojTelefona(null);
        dto.setAdresa(null);

        Zaposlen result = mapper.toEntity(dto);

        assertThat(result.getBrojTelefona()).isNull();
        assertThat(result.getAdresa()).isNull();
    }

    @Test
    void updateEntityFromDtoUpdatesAllFields() {
        Zaposlen emp = employee();
        EmployeeUpdateRequestDto dto = new EmployeeUpdateRequestDto();
        dto.setIme("Novak");
        dto.setPrezime("Novakovic");
        dto.setBrojTelefona("0641234567");
        dto.setAdresa("Nova adresa 1");
        dto.setPozicija("Senior");
        dto.setDepartman("Finance");

        mapper.updateEntityFromDto(emp, dto, Role.ADMIN);

        assertThat(emp.getIme()).isEqualTo("Novak");
        assertThat(emp.getPrezime()).isEqualTo("Novakovic");
        assertThat(emp.getBrojTelefona()).isEqualTo("0641234567");
        assertThat(emp.getAdresa()).isEqualTo("Nova adresa 1");
        assertThat(emp.getPozicija()).isEqualTo("Senior");
        assertThat(emp.getDepartman()).isEqualTo("Finance");
    }

    private Zaposlen employee() {
        Zaposlen emp = new Zaposlen();
        emp.setId(1L);
        emp.setIme("Ana");
        emp.setPrezime("Anic");
        emp.setDatumRodjenja(LocalDate.of(1991, 1, 1));
        emp.setPol(Pol.M);
        emp.setEmail("ana@banka.com");
        emp.setUsername("ana");
        emp.setPozicija("Broker");
        emp.setDepartman("Prodaja");
        emp.setAktivan(true);
        emp.setRole(Role.AGENT);
        return emp;
    }
}
