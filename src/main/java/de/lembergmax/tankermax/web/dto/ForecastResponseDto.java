package de.lembergmax.tankermax.web.dto;

import java.util.List;

/**
 * Antwort des Vorhersage-Endpunkts für eine Tankstelle und Kraftstoffart.
 *
 * <p>Enthält die Vorhersagekurve und – falls berechnet – das Unsicherheitsband, dazu den Tanktipp des
 * heutigen Tages und eine kompakte Treffer-Statistik der jüngsten Vergangenheit. Ist keine Vorhersage
 * vorhanden, sind die Punktlisten leer und die übrigen Felder {@code null}.</p>
 *
 * @param available   {@code true}, wenn für diese Tankstelle und Kraftstoffart eine Vorhersage vorliegt
 * @param generatedAt Erzeugungszeitpunkt der Vorhersage als Chart-Sekunden (0, wenn keine vorliegt)
 * @param points      Vorhersagekurve (Zeit als Chart-Sekunden, Preis €/L)
 * @param lower       unteres Unsicherheitsband (q10); leer, wenn keine Quantile berechnet wurden
 * @param upper       oberes Unsicherheitsband (q90); leer, wenn keine Quantile berechnet wurden
 * @param tip         Tanktipp des heutigen Tages oder {@code null}
 * @param accuracy    Treffer-Statistik der jüngsten Vergangenheit oder {@code null}
 */
public record ForecastResponseDto(boolean available, long generatedAt, List<PointDto> points,
                                  List<PointDto> lower, List<PointDto> upper, ForecastTipDto tip,
                                  ForecastAccuracyDto accuracy) {

}
