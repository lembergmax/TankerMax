package de.lembergmax.tankermax.polling.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.lembergmax.tankermax.polling.client.support.FlexiblePriceDeserializer;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;

/**
 * Einzelne Tankstelle samt aktueller Preise aus der Antwort der Listen-Schnittstelle
 * ({@code list.php}).
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class StationListItem {

    /** Eindeutige Kennung der Tankstelle. */
    private String id;

    /** Anzeigename der Tankstelle. */
    private String name;

    /** Marke der Tankstelle. */
    private String brand;

    /** Straße der Tankstellenadresse. */
    private String street;

    /** Hausnummer der Tankstellenadresse. */
    private String houseNumber;

    /** Postleitzahl der Tankstellenadresse. */
    private String postCode;

    /** Ort der Tankstellenadresse. */
    private String place;

    /** Breitengrad des Tankstellenstandorts. */
    private double lat;

    /** Längengrad des Tankstellenstandorts. */
    private double lng;

    /** Gibt an, ob die Tankstelle zum Abfragezeitpunkt geöffnet ist. */
    @JsonProperty("isOpen")
    private boolean open;

    /** Preis für Super E5 oder {@code null}, falls nicht verfügbar. */
    @JsonDeserialize(using = FlexiblePriceDeserializer.class)
    private BigDecimal e5;

    /** Preis für Super E10 oder {@code null}, falls nicht verfügbar. */
    @JsonDeserialize(using = FlexiblePriceDeserializer.class)
    private BigDecimal e10;

    /** Preis für Diesel oder {@code null}, falls nicht verfügbar. */
    @JsonDeserialize(using = FlexiblePriceDeserializer.class)
    private BigDecimal diesel;

}
