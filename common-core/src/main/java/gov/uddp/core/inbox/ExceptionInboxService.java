package gov.uddp.core.inbox;

import gov.uddp.core.inbox.ExceptionSource.Item;
import gov.uddp.core.statepack.StatePackLoader;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** One list of everything that needs a person, aggregated from every {@link ExceptionSource} (Track C4). */
@Service
public class ExceptionInboxService {
    /** An item with its department, as shown in the inbox. */
    public record Entry(Item item, String department) {
    }

    /** Filters; any may be null. {@code minAgeDays} keeps items at least that old. */
    public record Filter(String department, String type, String priority, Integer minAgeDays) {
    }

    private static final Map<String, Integer> PRIORITY_ORDER = Map.of("HIGH", 0, "MEDIUM", 1, "LOW", 2);

    private final List<ExceptionSource> sources;
    private final StatePackLoader packs;
    private final Clock clock;

    @Autowired
    public ExceptionInboxService(ObjectProvider<ExceptionSource> sources, StatePackLoader packs) {
        this(sources.orderedStream().toList(), packs, Clock.systemUTC());
    }

    public ExceptionInboxService(List<ExceptionSource> sources, StatePackLoader packs, Clock clock) {
        this.sources = sources;
        this.packs = packs;
        this.clock = clock;
    }

    /** Items the caller may see ({@code stateAllowed}), filtered, highest priority and oldest first. */
    public List<Entry> list(Filter filter, Predicate<String> stateAllowed) {
        List<Entry> entries = new ArrayList<>();
        for (ExceptionSource source : sources) {
            for (Item item : source.items()) {
                if (!stateAllowed.test(item.stateCode())) {
                    continue;
                }
                String department = department(item.stateCode());
                if (matches(filter, item, department)) {
                    entries.add(new Entry(item, department));
                }
            }
        }
        entries.sort(Comparator.<Entry>comparingInt(e -> PRIORITY_ORDER.getOrDefault(e.item().priority(), 3))
                .thenComparing(e -> e.item().createdAt()));
        return entries;
    }

    /** Count per item type for the same scope and filters, zero for the types that have no open item. */
    public Map<String, Integer> counts(Filter filter, Predicate<String> stateAllowed) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        sources.forEach(source -> source.types().forEach(type -> counts.put(type, 0)));
        list(filter, stateAllowed).forEach(e -> counts.merge(e.item().type(), 1, Integer::sum));
        return counts;
    }

    private boolean matches(Filter filter, Item item, String department) {
        if (filter == null) {
            return true;
        }
        if (filter.department() != null && !filter.department().isBlank()
                && !filter.department().equalsIgnoreCase(department)) {
            return false;
        }
        if (filter.type() != null && !filter.type().isBlank() && !filter.type().equals(item.type())) {
            return false;
        }
        if (filter.priority() != null && !filter.priority().isBlank() && !filter.priority().equals(item.priority())) {
            return false;
        }
        return filter.minAgeDays() == null
                || Duration.between(item.createdAt(), clock.instant()).toDays() >= filter.minAgeDays();
    }

    private String department(String stateCode) {
        return stateCode == null ? null : packs.find(stateCode).map(p -> p.manifest().departmentCode()).orElse(null);
    }
}
