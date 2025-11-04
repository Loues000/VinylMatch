package Server;

public class PlaylistSummary {
    private final String id;
    private final String name;
    private final String coverUrl;
    private final Integer trackCount;
    private final String owner;

    public PlaylistSummary(String id, String name, String coverUrl, Integer trackCount, String owner) {
        this.id = id;
        this.name = name;
        this.coverUrl = coverUrl;
        this.trackCount = trackCount;
        this.owner = owner;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public Integer getTrackCount() {
        return trackCount;
    }

    public String getOwner() {
        return owner;
    }
}
