package com.banka1.transaction_service.domain.base;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;


/**
 * Bazni JPA entitet koji sadrzi zajednicka polja za entitete u aplikaciji.
 * Pruza automatsko upravljanje primarnim kljucem, verzijom za optimisticko zakljucavanje,
 * zastavom za soft delete i vremenskim markama kreiranja i azuriranja.
 */

@MappedSuperclass
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class BaseEntityWithoutDelete {
    /** Primarni kljuc entiteta, automatski generisan. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Verzija za optimisticko zakljucavanje – Hibernate automatski povecava vrednost pri svakom azuriranju. */
    @Version
    private Long version;


    /** Vreme kreiranja entiteta – postavljeno automatski i ne moze se menjati. */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** Vreme poslednjeg azuriranja entiteta – automatski se osvezava pri svakoj izmeni. */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
