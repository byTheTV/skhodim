package ru.ifmo.skhodim.domain;

/** Элемент пластинки вкуса (taste_plate): category / metro / place. */
public record TasteSlice(String kind, String itemName, long weight) {
}
