package ru.ifmo.skhodim.service;

import org.springframework.stereotype.Service;
import ru.ifmo.skhodim.domain.Event;
import ru.ifmo.skhodim.domain.EventSession;
import ru.ifmo.skhodim.domain.Place;
import ru.ifmo.skhodim.domain.UserProfile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UC-08 / FR-16: сервер собирает PNG 9:16 «Иду на …, кто с нами».
 */
@Service
public class ShareCardService {

    private static final int WIDTH = 540;
    private static final int HEIGHT = 960;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"));

    public byte[] render(UserProfile profile, CatalogService.EventCard card) {
        Event event = card.event();
        Place place = card.place();
        EventSession session = card.sessions().stream()
                .filter(s -> !s.isCancelled())
                .findFirst()
                .orElse(null);

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(24, 16, 58));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(255, 90, 54));
        g.fillRect(0, 0, WIDTH, 18);

        int y = 90;
        g.setColor(new Color(196, 170, 255));
        g.setFont(new Font("SansSerif", Font.PLAIN, 22));
        g.drawString("Сходим · Санкт-Петербург", 40, y);
        y += 70;

        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 36));
        y = drawWrapped(g, event.title(), 40, y, WIDTH - 80, 44);

        y += 30;
        g.setFont(new Font("SansSerif", Font.PLAIN, 24));
        g.setColor(new Color(230, 220, 255));
        if (session != null && session.startsAt() != null) {
            y = drawWrapped(g, DATE.format(session.startsAt()), 40, y, WIDTH - 80, 32);
        }
        y += 10;
        String metro = place == null || place.metro() == null ? "метро не указано" : "м. " + place.metro();
        String venue = place == null ? metro : place.name() + " · " + metro;
        y = drawWrapped(g, venue, 40, y, WIDTH - 80, 32);

        y += 60;
        g.setColor(new Color(198, 241, 53));
        g.setFont(new Font("SansSerif", Font.BOLD, 32));
        String nick = profile.nickname() == null ? "я" : profile.nickname();
        y = drawWrapped(g, nick + " идёт. Кто с нами?", 40, y, WIDTH - 80, 40);

        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        g.setColor(new Color(170, 150, 210));
        g.drawString("Карточка для сторис 9:16", 40, HEIGHT - 50);
        g.dispose();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot render share card", e);
        }
    }

    private static int drawWrapped(Graphics2D g, String text, int x, int y, int maxWidth, int lineHeight) {
        FontMetrics metrics = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        for (String item : lines) {
            g.drawString(item, x, y);
            y += lineHeight;
        }
        return y;
    }
}
