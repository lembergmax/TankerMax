package de.lembergmax.tankermax.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Antwort der Listen-Schnittstelle ({@code list.php}) der Tankerkönig-API.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class StationListResponse {

    /** Gibt an, ob die Anfrage erfolgreich verarbeitet wurde. */
    private boolean ok;

    /** Statustext der Antwort. */
    private String status;

    /** Liste der gefundenen Tankstellen. */
    private List<StationListItem> stations;

}
