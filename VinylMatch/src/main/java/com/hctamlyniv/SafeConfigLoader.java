import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.Properties;

public final class SafeConfig {
  private final Properties props = new Properties();

  public SafeConfig() {
    String cfgPath = System.getenv("CONFIG_FILE"); // optionaler Pfad zu einer lokalen Datei
    if (cfgPath != null && !cfgPath.isBlank()) {
      try (var in = new FileInputStream(cfgPath)) {
        props.load(in);
      } catch (Exception e) {
        throw new IllegalStateException("Konnte Konfigurationsdatei nicht laden: " + cfgPath, e);
      }
    }
  }

  public String get(String key, boolean required) {
    String val = System.getenv(key);
    if (val == null || val.isBlank()) {
      val = props.getProperty(key);
    }
    if (required && (val == null || val.isBlank())) {
      throw new IllegalStateException("Fehlender Konfigurationswert: " + key);
    }
    return val;
  }

  // Beispielnutzung
  public static void main(String[] args) {
    var cfg = new SafeConfig();
    String clientId = cfg.get("SPOTIFY_CLIENT_ID", true);
    String clientSecret = cfg.get("SPOTIFY_CLIENT_SECRET", true);
    String redirectUri = cfg.get("REDIRECT_URI", true);
    System.out.println("Konfiguration geladen (Werte nicht ausgeben!).");
  }
}
