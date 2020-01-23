package com.microsoft.applicationinsights.agentot;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.google.common.base.Strings;
import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.internal.schemav2.ExceptionDetails;
import com.microsoft.applicationinsights.telemetry.Duration;
import com.microsoft.applicationinsights.telemetry.ExceptionTelemetry;
import com.microsoft.applicationinsights.telemetry.RemoteDependencyTelemetry;
import com.microsoft.applicationinsights.telemetry.RequestTelemetry;
import com.microsoft.applicationinsights.telemetry.Telemetry;
import io.opentelemetry.sdk.trace.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.trace.AttributeValue;
import io.opentelemetry.trace.AttributeValue.Type;
import io.opentelemetry.trace.Span.Kind;
import io.opentelemetry.trace.SpanId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class Exporter implements SpanExporter {

    private static final Logger logger = LoggerFactory.getLogger(Exporter.class);

    private final TelemetryClient telemetryClient;

    public Exporter(TelemetryClient telemetryClient) {
        this.telemetryClient = telemetryClient;
    }

    @Override
    public ResultCode export(List<SpanData> spans) {
        // System.out.println("EXPORT " + spans.size() + " SPANS");
        try {
            for (SpanData span : spans) {
                // System.out.println(span);
                export(span);
            }
            return ResultCode.SUCCESS;
        } catch (Throwable t) {
            t.printStackTrace();
            return ResultCode.FAILED_NOT_RETRYABLE;
        }
    }

    private void export(SpanData span) {
        Kind kind = span.getKind();
        // FIXME this is needed until correct span kind sent from auto-instrumentation
        if (span.getName().equals("netty.request")) {
            kind = Kind.SERVER;
        }
        if (span.getName().equals("jms.onMessage")) {
            kind = Kind.SERVER;
        }
        if (kind == Kind.INTERNAL) {
            // FIXME for now OpenTelemetry auto-instrumentation is not using CLIENT
            // in future, INTERNAL should map to telemetry.setType("InProc");
            exportRemoteDependency(span);
        } else if (kind == Kind.CLIENT) {
            exportRemoteDependency(span);
        } else if (kind == Kind.SERVER) {
            exportRequest(span);
        } else {
            throw new UnsupportedOperationException(kind.name());
        }
    }

    private void exportRequest(SpanData span) {

        RequestTelemetry telemetry = new RequestTelemetry();

        if (span.getName().equals("servlet.request") || span.getName().equals("netty.request")) {

            AttributeValue httpStatusCode = span.getAttributes().get("http.status_code");

            String component = getString(span, "component"); // e.g. "java-web-servlet"
            String spanKind = getString(span, "span.kind"); // "server"
            String servletContext = getString(span, "servlet.context");

            String httpUrl = getString(span, "http.url");
            String peerHostname = getString(span, "peer.hostname");
            String peerIPv4 = getString(span, "peer.ipv4");

            String httpMethod = getString(span, "http.method");

            String spanType = getString(span, "span.type"); // "web"

            String spanOriginType =
                    getString(span, "span.origin.type"); // e.g. "org.apache.catalina.core.ApplicationFilterChain"


            if (isNonNullLong(httpStatusCode)) {
                telemetry.setResponseCode(Long.toString(httpStatusCode.getLongValue()));
            }

            if (httpUrl != null) {
                telemetry.setUrl(httpUrl);
            }
        }
        telemetry.setName(span.getName());

        String id = setContext(span, telemetry);
        telemetry.setId(id);

        telemetry.setTimestamp(new Date(NANOSECONDS.toMillis(span.getStartEpochNanos())));
        telemetry.setDuration(new Duration(NANOSECONDS.toMillis(span.getEndEpochNanos() - span.getStartEpochNanos())));

        telemetry.setSuccess(span.getStatus().isOk());
        String description = span.getStatus().getDescription();
        if (description != null) {
            telemetry.getProperties().put("statusDescription", description);
        }

        telemetryClient.track(telemetry);

        trackExceptionIfNeeded(span, telemetry, telemetry.getId());
    }

    private void exportRemoteDependency(SpanData span) {

        RemoteDependencyTelemetry telemetry = new RemoteDependencyTelemetry();


        // FIXME better to use component = "http" once that is set correctly in auto-instrumentation
        // http.method is required for http requests, see
        // https://github.com/open-telemetry/opentelemetry-specification/blob/master/specification/data-http.md
        if (span.getAttributes().containsKey("http.method")) {
            applyHttpRequestSpan(span, telemetry);
        } else if (span.getName().equals("database.query") || span.getName().equals("redis.query")) {
            applyDatabaseQuerySpan(span, telemetry);
        } else {
            telemetry.setName(span.getName());
        }

        String id = setContext(span, telemetry);
        telemetry.setId(id);

        telemetry.setTimestamp(new Date(NANOSECONDS.toMillis(span.getStartEpochNanos())));
        telemetry.setDuration(new Duration(NANOSECONDS.toMillis(span.getEndEpochNanos() - span.getStartEpochNanos())));

        // TODO what is command name, why doesn't dot net exporter use it?
        // https://raw.githubusercontent.com/open-telemetry/opentelemetry-dotnet/master/src/OpenTelemetry.Exporter
        // .ApplicationInsights/ApplicationInsightsTraceExporter.cs
        // telemetry.setCommandName(uri);

        telemetry.setSuccess(span.getStatus().isOk());
        String description = span.getStatus().getDescription();
        if (description != null) {
            telemetry.getProperties().put("statusDescription", description);
        }

        telemetryClient.track(telemetry);

        trackExceptionIfNeeded(span, telemetry, telemetry.getId());
    }

    private void applyHttpRequestSpan(SpanData span, RemoteDependencyTelemetry telemetry) {

        telemetry.setType("Http (tracked component)");

        String method = getString(span, "http.method");
        String url = getString(span, "http.url");

        String httpMethod = getString(span, "http.method");
        if (httpMethod != null) {
            String httpUrl = getString(span, "http.url");
            // TODO handle if no http.url
            if (httpUrl != null) {
                // TODO is this right, overwriting name?
                telemetry.setName(httpMethod + " " + httpUrl);
            }
        }

        AttributeValue httpStatusCode = span.getAttributes().get("http.status_code");
        if (httpStatusCode != null && httpStatusCode.getType() == Type.LONG) {
            long statusCode = httpStatusCode.getLongValue();
            telemetry.setResultCode(Long.toString(statusCode));
            // success is handled more generally now
            // telemetry.setSuccess(statusCode < 400);
        }

        if (method != null) {
            // FIXME can drop this now?
            // for backward compatibility (same comment from CoreAgentNotificationsHandler)
            telemetry.getProperties().put("Method", method);
        }
        if (url != null) {
            try {
                URI uriObject = new URI(url);
                String target = createTarget(uriObject);
                // TODO can drop this now?
//                if (requestContext != null) {
//                    String incomingTarget = TraceContextCorrelationCore.generateChildDependencyTarget(requestContext);
//                    if (incomingTarget != null && !incomingTarget.isEmpty()) {
//                        target += " | " + incomingTarget;
//                    }
//                }
                telemetry.setTarget(target);
                String path = uriObject.getPath();
                if (Strings.isNullOrEmpty(path)) {
                    telemetry.setName(method + " /");
                } else {
                    telemetry.setName(method + " " + path);
                }
            } catch (URISyntaxException e) {
                logger.error(e.getMessage());
                logger.debug(e.getMessage(), e);
            }
            telemetry.setCommandName(url);
            // FIXME can drop this now?
            // for backward compatibility (same comment from CoreAgentNotificationsHandler)
            telemetry.getProperties().put("URI", url);
        }
    }

    private void applyDatabaseQuerySpan(SpanData span, RemoteDependencyTelemetry telemetry) {
        String dbInstance = getString(span, "db.instance");
        String dbType = getString(span, "db.type"); // e.g. "hsqldb"
        String dbUser = getString(span, "db.user");
        String component = getString(span, "component");
        String serviceName = getString(span, "service.name"); // same as db.type
        String spanKind = getString(span, "span.kind"); // "client"
        String resourceName = getString(span, "resource.name"); // same as db.statement
        String dbStatement = getString(span, "db.statement");
        String spanType = getString(span, "span.type"); // "sql"
        String spanOriginType =
                getString(span, "span.origin.type"); // e.g. "com.zaxxer.hikari.pool.HikariProxyStatement"

        if (dbType != null) {
            // ???
            telemetry.setName(dbType);
        }

        if (resourceName != null) { // same as dbStatement (but more generally applicable?)
            telemetry.setCommandName(resourceName);
        }
        if (spanType != null) {
            if (spanType.equals("sql")) {
                telemetry.setType("SQL");
            } else if (spanType.equals("redis")) {
                telemetry.setType("Redis");
            } else {
                telemetry.setType(spanType);
            }
        }
    }

    private static String getString(SpanData span, String attributeName) {
        AttributeValue attributeValue = span.getAttributes().get(attributeName);
        if (attributeValue == null) {
            return null;
        } else if (attributeValue.getType() == AttributeValue.Type.STRING) {
            return attributeValue.getStringValue();
        } else {
            // TODO log debug warning
            return null;
        }
    }

    private void trackExceptionIfNeeded(SpanData span, Telemetry telemetry, String id) {
        String errorStack = getString(span, "error.stack");
        if (errorStack != null) {
            // FIXME this is just hacked to make tests pass for now
            int endOfLine = errorStack.indexOf(System.lineSeparator());
            if (endOfLine != -1) {
                errorStack = errorStack.substring(0, endOfLine);
            }
            int sep = errorStack.indexOf(": ");
            ExceptionDetails details = new ExceptionDetails();
            if (sep == -1) {
                details.setTypeName(errorStack);
            } else {
                details.setTypeName(errorStack.substring(0, sep));
                details.setMessage(errorStack.substring(sep + 2));
            }
            ExceptionTelemetry exceptionTelemetry = new ExceptionTelemetry();
            exceptionTelemetry.getData().setExceptions(Collections.singletonList(details));
            exceptionTelemetry.getContext().getOperation().setId(telemetry.getContext().getOperation().getId());
            exceptionTelemetry.getContext().getOperation().setParentId(id);
            exceptionTelemetry.setTimestamp(new Date(NANOSECONDS.toMillis(span.getEndEpochNanos())));
            telemetryClient.track(exceptionTelemetry);
        }
    }

    @Override
    public void shutdown() {
    }

    private static String setContext(SpanData span, Telemetry telemetry) {
        String traceId = span.getTraceId().toLowerBase16();
        // TODO optimize with fixed length StringBuilder
        String id = "|" + traceId + "." + span.getSpanId().toLowerBase16() + ".";
        telemetry.getContext().getOperation().setId(traceId);
        SpanId parentSpanId = span.getParentSpanId();
        if (parentSpanId.isValid()) {
            telemetry.getContext().getOperation().setParentId("|" + traceId + "." + parentSpanId.toLowerBase16() + ".");
        } else {
            telemetry.getContext().getOperation().setParentId(id);
        }
        return id;
    }

    private static boolean isNonNullLong(AttributeValue attributeValue) {
        return attributeValue != null && attributeValue.getType() == AttributeValue.Type.LONG;
    }

    private static String createTarget(URI uriObject) {
        String target = uriObject.getHost();
        if (uriObject.getPort() != 80 && uriObject.getPort() != 443 && uriObject.getPort() != -1) {
            target += ":" + uriObject.getPort();
        }
        return target;
    }

    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
    //
}
