package de.lembergmax.tankermax.polling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Preis einer einzelnen Kraftstoffart innerhalb einer Preisbeobachtung.
 *
 * <p>Je Beobachtung und Kraftstoffart darf es höchstens einen Preis geben; dies
 * sichert eine Eindeutigkeitsbedingung auf {@code (observation_id, fuel_type_id)}
 * auf Datenbankebene ab und verhindert doppelte Preiseinträge.</p>
 *
 * <p>Der zusätzliche eigenständige Index auf {@code fuel_type_id} unterstützt die nach
 * Kraftstoffart filternden Preisabfragen: Die führende Spalte der Eindeutigkeitsbedingung ist
 * {@code observation_id}, sodass diese für einen primär über {@code fuel_type_id} gehenden Zugriff
 * nicht genutzt werden kann.</p>
 */
@Entity
@Table(
        name = "fuel_price",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_fuel_price_observation_fuel",
                columnNames = {"observation_id", "fuel_type_id"}),
        indexes = @Index(
                name = "idx_fuel_price_fuel_type",
                columnList = "fuel_type_id")
)
@Getter
@Setter
public class FuelPrice {

    /** Technischer Primärschlüssel des Preiseintrags. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Preisbeobachtung, zu der dieser Eintrag gehört. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "observation_id", nullable = false)
    private PriceObservation observation;

    /** Kraftstoffart, auf die sich der Preis bezieht. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fuel_type_id", nullable = false)
    private FuelType fuelType;

    /** Preis je Liter in Euro mit drei Nachkommastellen. */
    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal amount;

}
