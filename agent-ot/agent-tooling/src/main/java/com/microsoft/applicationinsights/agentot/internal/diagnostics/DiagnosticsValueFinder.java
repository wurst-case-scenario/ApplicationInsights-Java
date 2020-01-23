package com.microsoft.applicationinsights.agentot.internal.diagnostics;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface DiagnosticsValueFinder {

    @Nonnull
    String getName();

    @Nullable
    String getValue();
}
