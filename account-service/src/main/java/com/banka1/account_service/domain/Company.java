package com.banka1.account_service.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "company_table"
)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Company extends BaseEntity{
    @Column(nullable = false)
    private String naziv;
    @Column(nullable = false,unique = true)
    private String maticni_broj;
    @Column(nullable = false,unique = true)
    private String poreski_broj;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "sifra_delatnosti_id", nullable = false)
    private SifraDelatnosti sifraDelatnosti;
    private String adresa;
    @Column(nullable = false)
    private Long vlasnik;



}
