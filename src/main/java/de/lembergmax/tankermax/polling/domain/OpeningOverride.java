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
import lombok.Getter;
import lombok.Setter;

/**
 * Abweichende Öffnungsregel einer Tankstelle, etwa zu Feiertagen.
 *
 * <p>Die Tankerkönig-API liefert solche Ausnahmen als frei formulierte
 * Textbausteine, die hier unverändert gespeichert werden.</p>
 */
@Entity
@Table(name = "opening_override", indexes = @Index(name = "idx_opening_override_station", columnList = "station_id"))
@Getter
@Setter
public class OpeningOverride {

    /** Technischer Primärschlüssel der Ausnahmeregel. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tankstelle, zu der diese Ausnahmeregel gehört. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    /** Wortlaut der Ausnahmeregel, wie von der API geliefert. */
    @Column(nullable = false, length = 512)
    private String description;

}
