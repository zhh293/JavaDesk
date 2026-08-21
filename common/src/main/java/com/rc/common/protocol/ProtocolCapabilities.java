package com.rc.common.protocol;

import java.util.Collections;
import java.util.Set;

/** Negotiated feature names carried during registration. */
public record ProtocolCapabilities(Set<String> values) {
    public ProtocolCapabilities {
        values = values == null ? Set.of() : Collections.unmodifiableSet(Set.copyOf(values));
    }

    public boolean supports(String capability) {
        return values.contains(capability);
    }
}
