package de.lembergmax.tankermax.forecast.training;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import de.lembergmax.tankermax.forecast.ml.TrainedModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Legt die trainierten Vorhersagemodelle im Dateisystem ab und lädt sie wieder.
 *
 * <p>Das Training läuft nur alle paar Tage, die Vorhersagekurve dagegen täglich. Damit die Kurve auch
 * nach einem Neustart ohne erneutes Training erzeugt werden kann, wird das Modell je Kraftstoffart
 * serialisiert abgelegt.</p>
 *
 * <p>Ein gespeichertes Modell wird nur dann verwendet, wenn sein Merkmalsvektor mit dem aktuellen
 * übereinstimmt. Nach einem Programm-Update, das die Merkmale ändert, würde ein altes Modell sonst
 * mit falsch belegten Spalten weiterrechnen; stattdessen wird es verworfen und beim nächsten Lauf neu
 * trainiert. Ebenso führt eine beschädigte oder unlesbare Datei nicht zum Abbruch, sondern lediglich
 * zu einem Neutraining.</p>
 *
 * <p>Gelesen werden ausschließlich Dateien, die dieser Dienst zuvor selbst geschrieben hat; das
 * Verzeichnis gehört zum Anwendungsdatenbestand und ist nicht für fremde Inhalte vorgesehen. Nur im
 * Profil {@code ingest} aktiv.</p>
 */
@Service
@Profile("ingest")
@RequiredArgsConstructor
public class ForecastModelStore {

    /** Logger dieser Klasse. */
    private static final Logger LOG = LoggerFactory.getLogger(ForecastModelStore.class);

    /** Namensvorsatz der Modelldateien. */
    private static final String FILE_PREFIX = "forecast-model-";

    /** Namensnachsatz der Modelldateien. */
    private static final String FILE_SUFFIX = ".ser";

    /** Namensnachsatz der Datei, in die zuerst geschrieben wird. */
    private static final String TEMP_SUFFIX = ".tmp";

    /** Konfiguration der Vorhersage. */
    private final ForecastProperties props;

    /**
     * Lädt das gespeicherte Modell einer Kraftstoffart, sofern es vorhanden und verwendbar ist.
     *
     * @param fuelCode Datenbank-Code des Kraftstoffs
     * @return das Modell oder ein leeres Ergebnis
     */
    public Optional<TrainedModel> load(final String fuelCode) {
        final Path file = fileFor(fuelCode);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (ObjectInputStream stream = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            final TrainedModel trained = (TrainedModel) stream.readObject();
            if (!trained.model().matchesCurrentFeatures()) {
                LOG.info("Gespeichertes Modell für {} passt nicht mehr zum aktuellen Merkmalsvektor – "
                        + "es wird neu trainiert.", fuelCode);
                return Optional.empty();
            }
            return Optional.of(trained);
        } catch (final IOException | ClassNotFoundException | ClassCastException ex) {
            LOG.warn("Gespeichertes Modell für {} ist nicht lesbar ({}) – es wird neu trainiert.",
                    fuelCode, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Speichert das Modell einer Kraftstoffart.
     *
     * <p>Es wird zunächst in eine Nebendatei geschrieben und diese anschließend über die Zieldatei
     * geschoben, damit ein Abbruch mitten im Schreiben kein halbes Modell hinterlässt.</p>
     *
     * @param fuelCode Datenbank-Code des Kraftstoffs
     * @param trained  zu speicherndes Modell
     */
    public void save(final String fuelCode, final TrainedModel trained) {
        final Path file = fileFor(fuelCode);
        final Path temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            try (ObjectOutputStream stream = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temp)))) {
                stream.writeObject(trained);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Modell für {} gespeichert ({} kB).", fuelCode, Files.size(file) / 1024);
        } catch (final IOException ex) {
            LOG.warn("Modell für {} konnte nicht gespeichert werden ({}) – der nächste Lauf trainiert "
                    + "erneut.", fuelCode, ex.getMessage());
        }
    }

    /**
     * Bestimmt den Dateipfad des Modells einer Kraftstoffart.
     *
     * @param fuelCode Datenbank-Code des Kraftstoffs
     * @return Pfad der Modelldatei
     */
    private Path fileFor(final String fuelCode) {
        return Path.of(props.getModelStorePath()).resolve(FILE_PREFIX + fuelCode + FILE_SUFFIX);
    }

}
