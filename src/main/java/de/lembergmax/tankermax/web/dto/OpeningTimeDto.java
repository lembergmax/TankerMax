package de.lembergmax.tankermax.web.dto;

/**
 * Eine reguläre Öffnungszeit einer Tankstelle für die Detailansicht.
 *
 * <p>Stammt aus der Tabelle {@code opening_time} und wird nur für angereicherte
 * Tankstellen (gesetztes {@code details_fetched_at}) geliefert.</p>
 *
 * @param days  Tagesbereich, wie von der API geliefert (zum Beispiel {@code Mo-Fr})
 * @param open  Öffnungszeit im Format {@code HH:mm} oder {@code null}
 * @param close Schließzeit im Format {@code HH:mm} oder {@code null}
 */
public record OpeningTimeDto(String days, String open, String close) {

}
