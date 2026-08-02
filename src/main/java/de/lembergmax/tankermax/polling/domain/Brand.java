package de.lembergmax.tankermax.polling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Marke einer Tankstelle (zum Beispiel {@code Aral} oder {@code Shell}).
 *
 * <p>Die Marke wird in eine eigene Tabelle ausgelagert, damit der mehrfach
 * auftretende Markenname nicht an jeder Tankstelle redundant gespeichert wird.</p>
 */
@Entity
@Table(name = "brand")
@Getter
@Setter
public class Brand {

    /** Technischer Primärschlüssel der Marke. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Eindeutiger Name der Marke. */
    @Column(nullable = false, unique = true, length = 128)
    private String name;

}
