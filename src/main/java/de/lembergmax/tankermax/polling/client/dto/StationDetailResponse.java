package de.lembergmax.tankermax.polling.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Antwort der Detail-Schnittstelle ({@code detail.php}) der Tankerkönig-API.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class StationDetailResponse {

    /** Gibt an, ob die Anfrage erfolgreich verarbeitet wurde. */
    private boolean ok;

    /** Statustext der Antwort. */
    private String status;

    /** Detaildaten der angefragten Tankstelle. */
    private StationDetail station;

}
