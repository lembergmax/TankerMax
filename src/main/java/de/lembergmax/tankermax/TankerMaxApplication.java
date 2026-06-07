package de.lembergmax.tankermax;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Einstiegspunkt der TankerMax-Anwendung.
 *
 * <p>Die Anwendung fragt die Tankerkönig-API periodisch ab und persistiert die
 * gelieferten Stammdaten und Preisbeobachtungen in dritter Normalform. Sie läuft
 * als reiner Hintergrunddienst ohne Web-Oberfläche; die nicht als Daemon laufenden
 * Threads des Aufgabenplaners halten die Anwendung am Leben.</p>
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class TankerMaxApplication {

    /**
     * Startet die Spring-Boot-Anwendung.
     *
     * @param args Kommandozeilenargumente, die an Spring weitergereicht werden
     */
    public static void main(final String[] args) {
        SpringApplication.run(TankerMaxApplication.class, args);
    }

}
