package Server;

import java.util.Collections;
import java.util.List;

public class UserPlaylistsResponse {
    private final List<PlaylistSummary> items;
    private final int total;
    private final int offset;
    private final int limit;
    private final boolean hasMore;
    private final int nextOffset;

    public UserPlaylistsResponse(List<PlaylistSummary> items, int total, int offset, int limit) {
        this.items = (items != null) ? List.copyOf(items) : Collections.emptyList();
        this.total = Math.max(0, total);
        this.offset = Math.max(0, offset);
        this.limit = Math.max(1, limit);
        int loaded = this.items.size();
        this.nextOffset = Math.min(this.offset + loaded, this.total);
        this.hasMore = this.nextOffset < this.total;
    }

    public List<PlaylistSummary> getItems() {
        return items;
    }

    public int getTotal() {
        return total;
    }

    public int getOffset() {
        return offset;
    }

    public int getLimit() {
        return limit;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public int getNextOffset() {
        return nextOffset;
    }
}
