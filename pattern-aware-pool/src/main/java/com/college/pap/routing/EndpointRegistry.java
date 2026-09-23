package com.college.pap.routing;

import com.college.pap.model.EndpointId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Registered endpoints the pool can route to.
 * One primary + ordered backups (failover preference order).
 */
public final class EndpointRegistry {

    private final EndpointId primary;
    private final List<EndpointId> backups;

    public EndpointRegistry(EndpointId primary, List<EndpointId> backups) {
        this.primary = Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(backups, "backups");
        Set<EndpointId> unique = new LinkedHashSet<>();
        unique.add(primary);
        for (EndpointId backup : backups) {
            Objects.requireNonNull(backup, "backup");
            if (!unique.add(backup)) {
                throw new IllegalArgumentException("duplicate endpoint: " + backup);
            }
        }
        this.backups = List.copyOf(backups);
    }

    public static EndpointRegistry of(EndpointId primary, EndpointId... backups) {
        return new EndpointRegistry(primary, List.of(backups));
    }

    public EndpointId primary() {
        return primary;
    }

    public List<EndpointId> backups() {
        return backups;
    }

    /** Primary first, then backups in registration order. */
    public List<EndpointId> all() {
        List<EndpointId> all = new ArrayList<>(1 + backups.size());
        all.add(primary);
        all.addAll(backups);
        return Collections.unmodifiableList(all);
    }

    public boolean contains(EndpointId endpointId) {
        return primary.equals(endpointId) || backups.contains(endpointId);
    }

    public Optional<EndpointId> firstBackup() {
        return backups.isEmpty() ? Optional.empty() : Optional.of(backups.get(0));
    }
}
