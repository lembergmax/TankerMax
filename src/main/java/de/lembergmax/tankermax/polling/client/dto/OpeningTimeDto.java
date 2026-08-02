package de.lembergmax.tankermax.polling.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Öffnungszeit einer Tankstelle aus der Detail-Schnittstelle ({@code detail.php}).
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpeningTimeDto {

    /** Tagesbereich der Öffnungszeit (zum Beispiel {@code Mo-Fr}). */
    private String text;

    /** Öffnungsuhrzeit im Format {@code HH:mm:ss}. */
    private String start;

    /** Schließuhrzeit im Format {@code HH:mm:ss}. */
    private String end;

}
