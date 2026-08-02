package de.lembergmax.tankermax.polling.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * Konfigurierbarer Ort, für den Tankstellen und Preise abgefragt werden.
 *
 * <p>Die Werte werden ausschließlich aus der Konfigurationsdatei gelesen und nicht
 * im Code festgelegt. Sie werden über Bean-Validation beim Anwendungsstart geprüft,
 * sodass eine vergessene oder unsinnige Koordinate sofort auffällt.</p>
 */
@Getter
@Setter
public class Location {

    /** Sprechender Name des Ortes für Protokollausgaben. */
    @NotBlank
    private String name;

    /** Breitengrad des Mittelpunkts des Suchradius. */
    @DecimalMin("-90")
    @DecimalMax("90")
    private double latitude;

    /** Längengrad des Mittelpunkts des Suchradius. */
    @DecimalMin("-180")
    @DecimalMax("180")
    private double longitude;

    /** Suchradius in Kilometern (von der API auf maximal 25 begrenzt). */
    @DecimalMin("0")
    @DecimalMax("25")
    private double radiusKm;

    /** Kraftstoffart für die Listenabfrage ({@code all}, {@code e5}, {@code e10} oder {@code diesel}). */
    @Pattern(regexp = "all|e5|e10|diesel")
    private String type = "all";

}
