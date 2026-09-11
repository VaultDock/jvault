package dev.jvault.content.support;

import dev.jvault.content.ContentMetadataRepository;
import dev.jvault.content.ContentRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryContentMetadataRepository implements ContentMetadataRepository {

    private final Map<String, List<ContentRecord>> byContentRef = new LinkedHashMap<>();

    @Override
    public void record(ContentRecord record) {
        byContentRef.computeIfAbsent(record.contentRef(), k -> new ArrayList<>()).add(record);
    }

    @Override
    public Optional<ContentRecord> findCurrent(String contentRef) {
        return versionsOf(contentRef).stream().max(Comparator.comparingInt(ContentRecord::versionNo));
    }

    @Override
    public Optional<ContentRecord> findVersion(String contentRef, int versionNo) {
        return versionsOf(contentRef).stream().filter(r -> r.versionNo() == versionNo).findFirst();
    }

    @Override
    public List<ContentRecord> versionsOf(String contentRef) {
        return List.copyOf(byContentRef.getOrDefault(contentRef, List.of()));
    }

    @Override
    public List<ContentRecord> partsOf(String ticketRef) {
        return byContentRef.values().stream()
                .flatMap(List::stream)
                .filter(r -> ticketRef.equals(r.ticketRef()))
                .toList();
    }
}
