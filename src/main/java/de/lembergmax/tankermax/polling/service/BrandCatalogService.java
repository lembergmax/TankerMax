package de.lembergmax.tankermax.polling.service;

import de.lembergmax.tankermax.polling.domain.Brand;
import de.lembergmax.tankermax.polling.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Liefert Marken anhand ihres Namens und legt sie bei Bedarf an.
 *
 * <p>Die Anlage läuft bewusst in einer eigenen Transaktion ({@link Propagation#REQUIRES_NEW}),
 * sodass eine durch zwei gleichzeitig laufende Erfassungsprozesse ausgelöste
 * Eindeutigkeitsverletzung die umgebende, je Tankstelle geführte Transaktion nicht beschädigt. Der
 * aufrufende {@link StationCatalogService} fängt die Verletzung ab und liest die inzwischen vom
 * anderen Prozess angelegte Marke in einem erneuten Aufruf – ebenfalls in einer eigenen
 * Transaktion. Dass dies eine eigene Bean ist, ist Voraussetzung: Nur über die Spring-Proxy-Grenze
 * greift die {@code REQUIRES_NEW}-Weitergabe.</p>
 */
@Service
@Profile("ingest")
@RequiredArgsConstructor
public class BrandCatalogService {

    /** Repository für Marken. */
    private final BrandRepository brandRepository;

    /**
     * Liefert die Marke zum Namen oder legt sie in einer eigenen Transaktion an.
     *
     * @param brandName Name der Marke
     * @return die gefundene oder neu angelegte Marke
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Brand getOrCreate(final String brandName) {
        return brandRepository.findByName(brandName)
                .orElseGet(() -> createBrand(brandName));
    }

    /**
     * Legt eine neue Marke an.
     *
     * @param brandName Name der Marke
     * @return die gespeicherte Marke
     */
    private Brand createBrand(final String brandName) {
        final Brand brand = new Brand();
        brand.setName(brandName);
        return brandRepository.save(brand);
    }

}
