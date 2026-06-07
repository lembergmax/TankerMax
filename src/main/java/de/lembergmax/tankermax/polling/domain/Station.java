package de.lembergmax.tankermax.polling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Stammdaten einer Tankstelle.
 *
 * <p>Der Primärschlüssel entspricht der von der Tankerkönig-API vergebenen UUID,
 * sodass dieselbe Tankstelle bei wiederholten Abfragen aktualisiert statt
 * dupliziert wird. Gleichheit und Hashwert leiten sich allein aus dieser stabilen,
 * von Anfang an vergebenen Kennung ab.</p>
 */
@Entity
@Table(name = "station", indexes = @Index(name = "idx_station_brand", columnList = "brand_id"))
@Getter
@Setter
@EqualsAndHashCode(of = "id")
public class Station {

    /** Von der Tankerkönig-API vergebene eindeutige Kennung der Tankstelle. */
    @Id
    @Column(length = 36)
    private String id;

    /** Marke der Tankstelle; {@code null}, wenn die API keine Marke liefert. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    /** Anzeigename der Tankstelle. */
    @Column(nullable = false)
    private String name;

    /** Straße der Tankstellenadresse. */
    private String street;

    /** Hausnummer der Tankstellenadresse. */
    @Column(name = "house_number")
    private String houseNumber;

    /** Fünfstellige Postleitzahl der Tankstellenadresse. */
    @Column(name = "post_code", length = 5)
    private String postCode;

    /** Ort der Tankstellenadresse. */
    private String place;

    /** Breitengrad des Tankstellenstandorts. */
    private double latitude;

    /** Längengrad des Tankstellenstandorts. */
    private double longitude;

    /** Bundesland der Tankstelle; von der API nicht immer befüllt. */
    private String state;

    /** Gibt an, ob die Tankstelle durchgehend rund um die Uhr geöffnet ist. */
    @Column(name = "whole_day")
    private boolean wholeDay;

    /** Zeitpunkt, zu dem die Tankstelle erstmals erfasst wurde. */
    @Column(name = "first_imported_at")
    private Instant firstImportedAt;

    /** Zeitpunkt der letzten Stammdatenaktualisierung. */
    @Column(name = "last_updated_at")
    private Instant lastUpdatedAt;

    /** Zeitpunkt der letzten Anreicherung um Detaildaten; {@code null}, solange noch keine Detaildaten geladen wurden. */
    @Column(name = "details_fetched_at")
    private Instant detailsFetchedAt;

}
