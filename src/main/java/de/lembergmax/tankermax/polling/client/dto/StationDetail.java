package de.lembergmax.tankermax.polling.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Detaildaten einer Tankstelle aus der Detail-Schnittstelle ({@code detail.php}).
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class StationDetail {

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

    /** Bundesland der Tankstelle; von der API nicht immer befüllt. */
    private String state;

    /** Breitengrad des Tankstellenstandorts. */
    private double lat;

    /** Längengrad des Tankstellenstandorts. */
    private double lng;

    /** Gibt an, ob die Tankstelle durchgehend rund um die Uhr geöffnet ist. */
    private boolean wholeDay;

    /** Reguläre Öffnungszeiten der Tankstelle. */
    private List<OpeningTimeDto> openingTimes;

    /** Abweichende Öffnungsregeln als frei formulierte Textbausteine. */
    private List<String> overrides;

}
