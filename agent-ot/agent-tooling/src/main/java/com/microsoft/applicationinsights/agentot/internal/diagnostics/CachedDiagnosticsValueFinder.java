package com.microsoft.applicationinsights.agentot.internal.diagnostics;

import javax.annotation.Nullable;

public abstract class CachedDiagnosticsValueFinder implements DiagnosticsValueFinder {
    private volatile String value;

    @Nullable
    @Override
    public String getValue() {
        if (value == null) {
            value = populateValue();
        }
        return value;
    }

    @Nullable
    protected abstract String populateValue();
}
