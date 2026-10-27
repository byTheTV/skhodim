package ru.ifmo.skhodim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка входа ИС «Сходим» (Этап 3: уровень хранения + уровень бизнес-логики).
 * Уровень представления появится на Этапе 4.
 */
@SpringBootApplication
@EnableScheduling
public class SkhodimApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkhodimApplication.class, args);
    }
}
