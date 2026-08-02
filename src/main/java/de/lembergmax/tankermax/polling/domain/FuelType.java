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
 * Kraftstoffart, für die Preise erfasst werden (zum Beispiel {@code E5}).
 *
 * <p>Die Auslagerung in eine eigene Tabelle vermeidet, den Kraftstoffschlüssel an
 * jedem Preiseintrag redundant abzulegen.</p>
 */
@Entity
@Table(name = "fuel_type")
@Getter
@Setter
public class FuelType {

    /** Technischer Primärschlüssel der Kraftstoffart. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Eindeutiger technischer Schlüssel der Kraftstoffart (zum Beispiel {@code E5}). */
    @Column(nullable = false, unique = true, length = 16)
    private String code;

    /** Lesbare Bezeichnung der Kraftstoffart. */
    @Column(nullable = false)
    private String label;

}
