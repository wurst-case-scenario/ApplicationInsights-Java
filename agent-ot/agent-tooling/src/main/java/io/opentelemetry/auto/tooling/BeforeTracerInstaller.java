package io.opentelemetry.auto.tooling;

import com.microsoft.applicationinsights.agentot.Exporter;
import com.microsoft.applicationinsights.agentot.internal.Global;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.export.SimpleSpansProcessor;

public class BeforeTracerInstaller {

    public static void beforeInstallAgentTracer() {
        OpenTelemetrySdk.getTracerFactory()
                .addSpanProcessor(SimpleSpansProcessor.newBuilder(new Exporter(Global.getTelemetryClient())).build());
    }
}
