import com.chessai.engine.Move;
import com.chessai.vision.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Measures board detection and recognition sanity on unlabelled internet images. */
public class InternetBenchmark {
    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args.length > 0 ? args[0] : "dataset/internet_manifest.json");
        Path overlayDir = Path.of(args.length > 1 ? args[1] : ".agent-work/outputs/internet_overlays");
        String text = Files.readString(manifest);
        Pattern pathPattern = Pattern.compile("\"local_path\"\\s*:\\s*\"([^\"]+)\"");
        Pattern titlePattern = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]*)\"");
        List<String> paths = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        Matcher pm = pathPattern.matcher(text);
        Matcher tm = titlePattern.matcher(text);
        while (pm.find()) paths.add(pm.group(1));
        while (tm.find()) titles.add(tm.group(1));
        int total = paths.size();
        int analyzed = 0, failed = 0;
        int above50 = 0, above70 = 0, above90 = 0, empty = 0;
        double scoreSum = 0, confidenceSum = 0;
        List<Double> scores = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Map.Entry<Double, String>> overlays = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String path = paths.get(i);
            String title = i < titles.size() ? titles.get(i) : path;
            File file = new File(path);
            if (!file.isFile()) {
                failed++;
                failures.add(path + " missing");
                continue;
            }
            try {
                Recognition r = ScreenshotAnalyzer.analyze(file);
                analyzed++;
                double score = r.boardScore;
                scoreSum += score;
                confidenceSum += r.confidence;
                scores.add(score);
                if (score >= 0.5) above50++;
                if (score >= 0.7) above70++;
                if (score >= 0.9) above90++;
                int pieces = 0;
                for (char[] row : r.squares) for (char c : row) if (c != '.') pieces++;
                if (pieces == 0) empty++;
                overlays.add(Map.entry(score, path + "\t" + title + "\t" + r.method
                        + "\t" + String.format(Locale.ROOT, "%.3f", score)
                        + "\t" + String.format(Locale.ROOT, "%.3f", r.confidence)
                        + "\t" + pieces));
            } catch (Exception e) {
                failed++;
                failures.add(path + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        Collections.sort(scores);
        double median = scores.isEmpty() ? 0 : scores.get(scores.size() / 2);
        System.out.printf(Locale.ROOT, "images=%d analyzed=%d failed=%d%n", total, analyzed, failed);
        System.out.printf(Locale.ROOT, "boardScore mean=%.3f median=%.3f%n",
                analyzed == 0 ? 0 : scoreSum / analyzed, median);
        System.out.printf(Locale.ROOT, "boardScore>=0.5 %d (%.1f%%)  >=0.7 %d (%.1f%%)  >=0.9 %d (%.1f%%)%n",
                above50, 100.0 * above50 / Math.max(1, analyzed),
                above70, 100.0 * above70 / Math.max(1, analyzed),
                above90, 100.0 * above90 / Math.max(1, analyzed));
        System.out.printf(Locale.ROOT, "confidence mean=%.3f emptyBoards=%d%n",
                analyzed == 0 ? 0 : confidenceSum / analyzed, empty);
        overlays.sort((a, b) -> Double.compare(b.getKey(), a.getKey()));
        if (args.length > 1 && !overlayDir.toString().isBlank()) {
            Files.createDirectories(overlayDir);
            for (int i = 0; i < Math.min(12, overlays.size()); i++) {
                String path = overlays.get(i).getValue().split("\t", 2)[0];
                try {
                    Recognition r = ScreenshotAnalyzer.analyze(new File(path));
                    Path target = overlayDir.resolve(String.format(Locale.ROOT, "top_%02d.png", i + 1));
                    ScreenshotAnalyzer.saveOverlay(new File(path), target.toFile(), r, Move.NONE);
                } catch (Exception ignored) {
                }
            }
            try (BufferedWriter writer = Files.newBufferedWriter(overlayDir.resolve("summary.tsv"))) {
                writer.write("score\tpath\ttitle\tmethod\tboardScore\tconfidence\tpieces\n");
                for (Map.Entry<Double, String> entry : overlays) {
                    writer.write(entry.getValue());
                    writer.newLine();
                }
            }
        }
        System.out.println("failures:");
        for (String failure : failures) System.out.println("  " + failure);
    }
}
