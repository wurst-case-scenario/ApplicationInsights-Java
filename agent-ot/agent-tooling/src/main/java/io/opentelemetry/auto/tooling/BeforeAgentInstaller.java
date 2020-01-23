package io.opentelemetry.auto.tooling;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;

import com.microsoft.applicationinsights.agentot.internal.MainEntryPoint;
import io.opentelemetry.auto.api.Config;

public class BeforeAgentInstaller {

    public static void beforeInstallBytebuddyAgent(Instrumentation inst, URL bootstrapURL) {
        if (Config.get().isTraceEnabled()) {
            try {
                File javaagentFile = new File(bootstrapURL.toURI());
                MainEntryPoint.premain(inst, javaagentFile);
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }
    }
}
