package com.hctamlyniv.curation;

import com.hctamlyniv.discogs.model.CuratedLink;

import java.util.List;
import java.util.Optional;

public interface CuratedLinkStore {
    
    Optional<CuratedLink> find(String normalizedKey);
    
    Optional<CuratedLink> findByBarcode(String barcode);
    
    void save(CuratedLink link);
    
    void delete(String normalizedKey);
    
    List<CuratedLink> listAll();
    
    static String normalizeKey(String artist, String album, Integer year) {
        String a = artist == null ? "" : artist.toLowerCase().trim().replaceAll("[^a-z0-9]", "");
        String b = album == null ? "" : album.toLowerCase().trim().replaceAll("[^a-z0-9]", "");
        String y = year == null ? "" : String.valueOf(year);
        return a + "|" + b + "|" + y;
    }
}
